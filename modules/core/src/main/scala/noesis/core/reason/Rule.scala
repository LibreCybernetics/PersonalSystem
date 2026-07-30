package noesis.core.reason

import noesis.core.model.*

/** Bounds on justification tracking (SPEC §12.4: explanation is worst-case expensive).
  *
  * The caps are the mitigation the spec asks for. Exceeding them degrades explanation quality —
  * fewer alternative justifications are retained — but never correctness of the closure itself,
  * because a fact stays derived as long as it has at least one justification.
  */
final case class ReasonerConfig(
    maxJustifications: Int = 8,
    maxJustificationSize: Int = 12,
    maxIterations: Int = 32
)

object ReasonerConfig:
  val default: ReasonerConfig = ReasonerConfig()

/** A forward-chaining inference rule.
  *
  * Modules contribute rules through this interface (SPEC §5.1), which is how domain inferences like
  * `crm:metamourOf` live in their module instead of bloating the core reasoner. A rule must be
  * monotone — it may only add facts — or the fixpoint will not terminate.
  */
trait Rule:
  def name: String

  /** Facts derivable in one step from `view`, each with the justifications supporting it. */
  def derive(view: ClosureView)(using ReasonerConfig): Iterator[(Axiom, Set[Justification])]

object Rule:
  /** Combines premise justifications: every way of satisfying premise A crossed with every way of
    * satisfying premise B. Capped and minimized per [[ReasonerConfig]].
    */
  def combine(a: Set[Justification], b: Set[Justification])(using
      cfg: ReasonerConfig
  ): Set[Justification] =
    val merged =
      for
        x <- a.iterator
        y <- b.iterator
        j = x.merge(y)
        if j.size <= cfg.maxJustificationSize
      yield j
    cap(merged.toSet)

  def combineAll(sets: Seq[Set[Justification]])(using ReasonerConfig): Set[Justification] =
    sets match
      case Seq()        => Set(Justification.empty)
      case head +: rest => rest.foldLeft(head)(combine)

  /** Keeps only minimal justifications, then the smallest `maxJustifications` of those. */
  def cap(js: Set[Justification])(using cfg: ReasonerConfig): Set[Justification] =
    Justification.minimal(js).toList.sorted.take(cfg.maxJustifications).toSet

/** The RDFS-style rule set: transitive class and property hierarchies, domain and range, plus the
  * OWL role constructs the relationship module needs.
  *
  * SPEC §3.1 names OWL 2 DL as the ceiling and §11 anticipates delegating to ELK or HermiT. What
  * matters for the MVP is that everything downstream consumes [[Closure]] rather than this rule
  * set, so swapping in a real reasoner is a change of one implementation.
  */
object RdfsRules:

  /** `A ⊑ B, B ⊑ C ⟹ A ⊑ C` */
  val subClassTransitivity: Rule = new Rule:
    val name = "subClassTransitivity"
    def derive(view: ClosureView)(using ReasonerConfig) =
      for
        ((a, b), j1) <- view.subClassEdges.iterator
        (c, j2) <- view.subClassBySub.getOrElse(b, Nil).iterator
        if a != c
      yield Axiom.SubClassOf(a, c) -> Rule.combine(j1, j2)

  /** `x : A, A ⊑ B ⟹ x : B` — the realization rule. */
  val classAssertionPropagation: Rule = new Rule:
    val name = "classAssertionPropagation"
    def derive(view: ClosureView)(using ReasonerConfig) =
      for
        ((a, b), j1) <- view.subClassEdges.iterator
        (individual, j2) <- view.instancesOf.getOrElse(a, Nil).iterator
      yield Axiom.ClassAssertion(individual, b) -> Rule.combine(j1, j2)

  /** `p ⊑ q, q ⊑ r ⟹ p ⊑ r` */
  val subPropertyTransitivity: Rule = new Rule:
    val name = "subPropertyTransitivity"
    def derive(view: ClosureView)(using ReasonerConfig) =
      for
        ((p, q), j1) <- view.subPropertyEdges.iterator
        (r, j2) <- view.subPropertyBySub.getOrElse(q, Nil).iterator
        if p != r
      yield Axiom.SubPropertyOf(p, r) -> Rule.combine(j1, j2)

  /** `x p y, p ⊑ q ⟹ x q y`, for both object and data assertions. */
  val subPropertyPropagation: Rule = new Rule:
    val name = "subPropertyPropagation"
    def derive(view: ClosureView)(using ReasonerConfig) =
      val objects =
        for
          ((p, q), j1) <- view.subPropertyEdges.iterator
          (s, o, j2) <- view.objectByProperty.getOrElse(p, Nil).iterator
        yield Axiom.ObjectAssertion(s, q, o) -> Rule.combine(j1, j2)
      val data =
        for
          ((p, q), j1) <- view.subPropertyEdges.iterator
          (s, v, j2) <- view.dataByProperty.getOrElse(p, Nil).iterator
        yield Axiom.DataAssertion(s, q, v) -> Rule.combine(j1, j2)
      objects ++ data

  /** `∃p.⊤ ⊑ C, x p y ⟹ x : C` */
  val domainRule: Rule = new Rule:
    val name = "domain"
    def derive(view: ClosureView)(using ReasonerConfig) =
      val fromObjects =
        for
          (p, entries) <- view.domains.iterator
          (cls, j1) <- entries.iterator
          (s, _, j2) <- view.objectByProperty.getOrElse(p, Nil).iterator
        yield Axiom.ClassAssertion(s, cls) -> Rule.combine(j1, j2)
      val fromData =
        for
          (p, entries) <- view.domains.iterator
          (cls, j1) <- entries.iterator
          (s, _, j2) <- view.dataByProperty.getOrElse(p, Nil).iterator
        yield Axiom.ClassAssertion(s, cls) -> Rule.combine(j1, j2)
      fromObjects ++ fromData

  /** `⊤ ⊑ ∀p.C, x p y ⟹ y : C` */
  val rangeRule: Rule = new Rule:
    val name = "range"
    def derive(view: ClosureView)(using ReasonerConfig) =
      for
        (p, entries) <- view.ranges.iterator
        (cls, j1) <- entries.iterator
        (_, o, j2) <- view.objectByProperty.getOrElse(p, Nil).iterator
      yield Axiom.ClassAssertion(o, cls) -> Rule.combine(j1, j2)

  /** `p symmetric, x p y ⟹ y p x` */
  val symmetryRule: Rule = new Rule:
    val name = "symmetry"
    def derive(view: ClosureView)(using ReasonerConfig) =
      for
        (p, j1) <- view.symmetric.iterator
        (s, o, j2) <- view.objectByProperty.getOrElse(p, Nil).iterator
      yield Axiom.ObjectAssertion(o, p, s) -> Rule.combine(j1, j2)

  /** `p transitive, x p y, y p z ⟹ x p z` */
  val transitivityRule: Rule = new Rule:
    val name = "transitivity"
    def derive(view: ClosureView)(using ReasonerConfig) =
      for
        (p, j0) <- view.transitive.iterator
        (x, y, j1) <- view.objectByProperty.getOrElse(p, Nil).iterator
        (z, j2) <- view.objectBySubjectProperty.getOrElse((y, p), Nil).iterator
        if x != z
      yield Axiom.ObjectAssertion(x, p, z) -> Rule.combineAll(Seq(j0, j1, j2))

  /** `p inverseOf q, x p y ⟹ y q x` (and the mirror). */
  val inverseRule: Rule = new Rule:
    val name = "inverse"
    def derive(view: ClosureView)(using ReasonerConfig) =
      for
        ((p, q), j0) <- view.inverses.iterator
        (from, to) <- Iterator((p, q), (q, p))
        (s, o, j1) <- view.objectByProperty.getOrElse(from, Nil).iterator
      yield Axiom.ObjectAssertion(o, to, s) -> Rule.combine(j0, j1)

  /** `p₁ ∘ … ∘ pₙ ⊑ q ⟹ x q z` for every path `x p₁ … pₙ z`.
    *
    * Self-loops are suppressed when `q` is declared irreflexive, which is what makes the spec's
    * `worksAt ∘ worksAt⁻ ⊑ colleagueOf` behave as intended rather than making everyone their own
    * colleague.
    */
  val propertyChainRule: Rule = new Rule:
    val name = "propertyChain"

    def derive(view: ClosureView)(using cfg: ReasonerConfig) =
      for
        ((steps, sup), j0) <- view.chains.iterator
        if steps.nonEmpty
        (x, z, jPath) <- composed(view, steps).iterator
        if !(x == z && view.irreflexive.contains(sup))
      yield Axiom.ObjectAssertion(x, sup, z) -> Rule.combine(j0, jPath)

    /** Left-to-right relational composition of the chain's steps. */
    private def composed(view: ClosureView, steps: List[ChainStep])(using
        ReasonerConfig
    ): List[(Iri, Iri, Set[Justification])] =
      steps.tail.foldLeft(view.relationFor(steps.head)): (acc, step) =>
        val next = view.relationFor(step).groupMap(_._1)(e => (e._2, e._3))
        for
          (x, mid, jLeft) <- acc
          (z, jRight) <- next.getOrElse(mid, Nil)
        yield (x, z, Rule.combine(jLeft, jRight))

  /** The default rule set. */
  val all: List[Rule] = List(
    subClassTransitivity,
    classAssertionPropagation,
    subPropertyTransitivity,
    subPropertyPropagation,
    domainRule,
    rangeRule,
    symmetryRule,
    transitivityRule,
    inverseRule,
    propertyChainRule
  )
