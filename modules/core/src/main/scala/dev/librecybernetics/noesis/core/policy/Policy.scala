package dev.librecybernetics.noesis.core.policy

import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.core.projection.AxiomRecord

/** Annotation defaults attached to a class or property (SPEC §3.3, cascade level 2). */
final case class TermPolicy(
    sensitivity: Option[Sensitivity] = None,
    recallUtility: Option[Double] = None,
    /** Escalate to this level when the axiom touches a person — e.g. health notes go `sensitive`. */
    escalateTo: Option[Sensitivity] = None
)

object TermPolicy:
  val empty: TermPolicy = TermPolicy()

  def utility(u: Double): TermPolicy = TermPolicy(recallUtility = Some(u))

  def sensitive(s: Sensitivity): TermPolicy = TermPolicy(sensitivity = Some(s))

/** A module's defaults (SPEC §3.3, cascade level 3).
  *
  * `utilityWeight` is the owner-tunable slider from §3.3.2 — relationships high, accounting low —
  * and is the base utility for any term in the module that does not override it.
  */
final case class ModuleDefaults(
    prefix: String,
    sensitivity: Sensitivity = Sensitivity.Personal,
    utilityWeight: Double = 0.5
)

/** Behavioral and temporal signals for one axiom (SPEC §3.3, cascade level 4).
  *
  * Agent reads are counted separately from owner reads, and weighted far below them, because §12.10
  * warns that behavioral boosts self-reinforce — an agent scraping the KB must not be able to talk
  * the system into believing a fact matters to the owner.
  */
final case class Signals(
    ownerViews: Int = 0,
    queryHits: Int = 0,
    briefingInclusions: Int = 0,
    agentReads: Int = 0,
    /** An occasion (birthday, anniversary) is coming up — §7.4 front-loads these. */
    upcomingOccasion: Boolean = false,
    activeGoal: Boolean = false,
    /** Days since the last reinforcing signal; drives slow decay of the boost. */
    daysSinceReinforcement: Double = 0.0
)

object Signals:
  val none: Signals = Signals()

/** The resolved policy configuration: everything the cascade consults besides the axiom itself. */
final case class PolicyBook(
    byProperty: Map[Iri, TermPolicy] = Map.empty,
    byClass: Map[Iri, TermPolicy] = Map.empty,
    modules: Map[String, ModuleDefaults] = Map.empty,
    signals: Map[AxiomId, Signals] = Map.empty
):
  def withProperty(property: Iri, policy: TermPolicy): PolicyBook =
    copy(byProperty = byProperty.updated(property, policy))

  def withClass(cls: Iri, policy: TermPolicy): PolicyBook =
    copy(byClass = byClass.updated(cls, policy))

  def withModule(defaults: ModuleDefaults): PolicyBook =
    copy(modules = modules.updated(defaults.prefix, defaults))

  def withSignals(id: AxiomId, s: Signals): PolicyBook =
    copy(signals = signals.updated(id, s))

  def ++(other: PolicyBook): PolicyBook =
    PolicyBook(
      byProperty ++ other.byProperty,
      byClass ++ other.byClass,
      modules ++ other.modules,
      signals ++ other.signals
    )

object PolicyBook:
  val empty: PolicyBook = PolicyBook()

/** The one cascade every annotation dimension resolves through (SPEC §3.3).
  *
  * Precedence, highest first: explicit owner override → class/property policy → module default →
  * behavioral and temporal signals. There is deliberately a single implementation: the spec unifies
  * annotation resolution precisely so that sensitivity, utility and confidence cannot drift into
  * three subtly different precedence orders.
  */
object PolicyCascade:

  /** Utility below this is "stored but suspended" — in the KB, not in your head (SPEC §4.3). */
  val suspendThreshold: Double = 0.15

  def resolve(record: AxiomRecord, book: PolicyBook): EffectiveAnnotations =
    EffectiveAnnotations(
      truthConfidence = record.annotations.truthConfidence.getOrElse(1.0),
      sensitivity = sensitivity(record, book),
      knowledgeScope = record.annotations.knowledgeScope,
      recallUtility = recallUtility(record, book)
    )

  /** Cascade for sensitivity, with a conservative floor.
    *
    * Assertional axioms default to `personal` rather than `public`: SPEC §12.8 notes the model is
    * only as good as its labels, so an unlabeled personal fact must not leak. Schema axioms default
    * to `public` — an ontology term is vocabulary, not data about anyone.
    */
  def sensitivity(record: AxiomRecord, book: PolicyBook): Sensitivity =
    record.annotations.sensitivity
      .orElse(termPolicies(record, book).flatMap(_.sensitivity).headOption)
      .orElse(moduleDefaults(record, book).map(_.sensitivity))
      .getOrElse(if record.axiom.isAssertional then Sensitivity.Personal else Sensitivity.Public)
      .pipe(escalate(record, book, _))

  /** Applies any class/property escalation, e.g. crm health notes → `sensitive` (SPEC §7.4). */
  private def escalate(record: AxiomRecord, book: PolicyBook, base: Sensitivity): Sensitivity =
    termPolicies(record, book).flatMap(_.escalateTo).foldLeft(base)(Sensitivity.max)

  /** Cascade for recall utility (SPEC §3.3.2).
    *
    * An explicit owner value short-circuits entirely — it is the top of the cascade, so behavioral
    * boosts must not silently move a slider the owner set by hand.
    */
  def recallUtility(record: AxiomRecord, book: PolicyBook): Double =
    record.annotations.recallUtility match
      case Some(owner) => clamp(owner)
      case None =>
        val base = termPolicies(record, book)
          .flatMap(_.recallUtility)
          .headOption
          .orElse(moduleDefaults(record, book).map(_.utilityWeight))
          .getOrElse(0.5)
        val signals = book.signals.getOrElse(record.id, Signals.none)
        clamp(base + decayed(behavioralBoost(signals) + temporalBoost(signals), signals))

  /** Saturating boost from use. Agent reads are worth ~1/25th of an owner view. */
  def behavioralBoost(s: Signals): Double =
    val score =
      0.020 * s.ownerViews +
        0.030 * s.queryHits +
        0.050 * s.briefingInclusions +
        0.0008 * s.agentReads
    0.25 * (1.0 - math.exp(-score))

  /** Upcoming occasions and active goals raise utility while they are relevant (SPEC §3.3.2). */
  def temporalBoost(s: Signals): Double =
    (if s.upcomingOccasion then 0.15 else 0.0) + (if s.activeGoal then 0.10 else 0.0)

  /** Boosts decay slowly without reinforcement; the policy base never decays.
    *
    * Half-life of 90 days: "slowly" in the spec's sense, and slow enough that the "still important?"
    * queue (§3.3.2) is the primary pruning mechanism rather than silent forgetting.
    */
  private def decayed(boost: Double, s: Signals): Double =
    boost * math.exp(-s.daysSinceReinforcement * math.log(2) / 90.0)

  /** Property policy first, then the policies of every class mentioned. */
  private def termPolicies(record: AxiomRecord, book: PolicyBook): List[TermPolicy] =
    val fromProperty = record.axiom.assertedProperty.flatMap(book.byProperty.get).toList
    val fromClasses = record.axiom.signature.toList.sorted.flatMap(book.byClass.get)
    fromProperty ++ fromClasses

  /** The defaults of the module owning this axiom's most specific term. */
  private def moduleDefaults(record: AxiomRecord, book: PolicyBook): Option[ModuleDefaults] =
    val prefixes = record.axiom.assertedProperty.flatMap(_.prefix).toList ++
      record.axiom.signature.toList.sorted.flatMap(_.prefix)
    prefixes.collectFirst(Function.unlift(book.modules.get))

  /** `internal` requires a knowledge scope (SPEC §3.3): without one the grant model cannot work. */
  def validate(record: AxiomRecord, book: PolicyBook): List[String] =
    val effective = resolve(record, book)
    List.concat(
      Option.when(effective.sensitivity == Sensitivity.Internal && effective.knowledgeScope.isEmpty)(
        s"${record.id.value} is internal but carries no knowledgeScope"
      ),
      Option.when(effective.truthConfidence < 0.0 || effective.truthConfidence > 1.0)(
        s"${record.id.value} has truthConfidence outside [0,1]"
      )
    )

  private def clamp(d: Double): Double = d.max(0.0).min(1.0)

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)
