package dev.librecybernetics.noesis.reasoner

import dev.librecybernetics.noesis.logic.*

/** A saturated set of facts, each with its minimal justifications (SPEC §3.4).
  *
  * This single value backs entailment checks, explanations, disclosure filtering (§3.3.1) and
  * derived belief (§4.4) — which is precisely why the reasoner tracks justifications rather than
  * just facts. A closure with facts but no justifications would satisfy queries and quietly break
  * the privacy model.
  */
final case class Closure(
    facts: Map[Axiom, Set[Justification]],
    iterations: Int,
    /** False when the iteration cap was hit — the closure is sound but possibly incomplete. */
    saturated: Boolean,
    /** Incompleteness inherited by a restricted or otherwise projected closure. */
    inheritedIncompleteReasons: Set[String] = Set.empty
):
  def contains(axiom: Axiom): Boolean = facts.contains(axiom)

  /** True only when both the fixpoint and every retained derivation are complete. */
  def complete: Boolean =
    saturated && inheritedIncompleteReasons.isEmpty &&
      facts.values.forall(_.forall(_.complete))

  def incompleteReasons: Set[String] =
    inheritedIncompleteReasons ++
      Option.when(!saturated)("iteration limit reached").toSet ++
      Option.when(facts.values.exists(_.exists(!_.complete)))(
        "justification tracking limit reached"
      )

  def size: Int = facts.size

  def axioms: Set[Axiom] = facts.keySet

  def justificationsFor(axiom: Axiom): Set[Justification] = facts.getOrElse(axiom, Set.empty)

  def explain(axiom: Axiom): Option[Explanation] =
    facts.get(axiom).map(js => Explanation(axiom, js.filter(_.complete)))

  /** Facts present in the closure but not asserted in `base` — the entailments proper. */
  def entailed(base: Graph): Map[Axiom, Set[Justification]] =
    facts.filterNot((axiom, _) => base.contains(axiom))

  def assertions: Set[Axiom] = axioms.filter(_.isAssertional)

  def triples: Set[Triple] = axioms.flatMap(Triples.of)

  def view: ClosureView = new ClosureView(facts)

  /** Flattens justifications back into a graph, losing the grouping but keeping provenance. */
  def asGraph: Graph = Graph(facts.map((axiom, js) => axiom -> js.flatMap(_.premises)))

object Closure:
  val empty: Closure = Closure(Map.empty, 0, saturated = true)

/** Semi-naive forward chaining to a fixpoint.
  *
  * Naive rather than incremental: SPEC §10 asks for sub-500ms incremental consistency at 10⁶
  * axioms, which this does not deliver and is not meant to. What it does deliver is the *interface*
  * — [[Closure]] with justifications — that an incremental ELK-backed implementation can satisfy
  * later without any caller changing.
  */
object Reasoner:

  /** Computes the closure of `graph` under `rules`.
    *
    * Base justifications come from the graph's support: an axiom that is both asserted and backed
    * by an ongoing fluent gets two independent one-premise justifications, which is what makes it
    * disclosable under the weaker of the two policies (§3.3.1).
    */
  def closure(
      graph: Graph,
      rules: List[Rule] = RdfsRules.all,
      cfg: ReasonerConfig = ReasonerConfig.default
  ): Closure =
    given ReasonerConfig = cfg

    val base: Map[Axiom, Set[Justification]] =
      graph.support.map: (axiom, supports) =>
        axiom -> supports.map(s => Justification(Set(s)))

    def iterate(facts: Map[Axiom, Set[Justification]], round: Int): Closure =
      // Recursion advances exactly one round at a time from zero, so equality is the cap boundary.
      // Expressing the unreachable `round > maxIterations` case created equivalent mutations.
      if round == cfg.maxIterations then Closure(facts, round, saturated = false)
      else
        val view = new ClosureView(facts)
        val derived = rules.iterator.flatMap(_.derive(view))

        // A round is a no-op unless it adds a fact or a genuinely new justification for one.
        var changed = false
        val next = derived.foldLeft(facts): (acc, entry) =>
          val (axiom, justifications) = entry
          val existing = acc.getOrElse(axiom, Set.empty)
          val merged = Rule.cap(existing ++ justifications)
          if merged == existing then acc
          else
            changed = true
            acc.updated(axiom, merged)

        if changed then iterate(next, round + 1) else Closure(facts, round, saturated = true)

    iterate(base, 0)

  /** Convenience: closure of the current-graph projection of a state. */
  def entails(graph: Graph, axiom: Axiom, rules: List[Rule] = RdfsRules.all): Boolean =
    closure(graph, rules).contains(axiom)
