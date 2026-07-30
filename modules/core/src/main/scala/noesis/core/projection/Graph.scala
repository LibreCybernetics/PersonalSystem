package noesis.core.projection

import java.time.LocalDate

import noesis.core.model.*

/** A set of axioms, each carrying the journal-backed reasons it is present.
  *
  * Keeping support attached at the graph level rather than recomputing it later is what lets
  * disclosure filtering and derived belief share one notion of "why" (SPEC §3.3.1, §4.4).
  */
final case class Graph(support: Map[Axiom, Set[Support]]):
  def axioms: Set[Axiom] = support.keySet

  def contains(axiom: Axiom): Boolean = support.contains(axiom)

  def supportFor(axiom: Axiom): Set[Support] = support.getOrElse(axiom, Set.empty)

  def size: Int = support.size

  def ++(other: Graph): Graph =
    Graph(other.support.foldLeft(support): (acc, kv) =>
      val (axiom, sup) = kv
      acc.updated(axiom, acc.getOrElse(axiom, Set.empty) ++ sup)
    )

  def withAxiom(axiom: Axiom, sup: Set[Support]): Graph =
    Graph(support.updated(axiom, supportFor(axiom) ++ sup))

  def filter(p: Axiom => Boolean): Graph = Graph(support.filter((a, _) => p(a)))

  /** The triple view, for querying and export. */
  def triples: Set[Triple] = axioms.flatMap(Triples.of)

  def assertions: Set[Axiom] = axioms.filter(_.isAssertional)

  /** Schema axioms only — what a module's ontology diff is computed against. */
  def schema: Set[Axiom] = axioms.filterNot(_.isAssertional)

object Graph:
  val empty: Graph = Graph(Map.empty)

  def of(entries: (Axiom, Set[Support])*): Graph = Graph(entries.toMap)

/** Projections over [[KbState]] (SPEC §3.2).
  *
  * All of these are pure functions of the journal fold. None of them is authoritative; each can be
  * thrown away and recomputed, which is the property the whole architecture leans on.
  */
object Projections:
  /** The asserted graph: exactly what the journal says, with fluents left un-materialized.
    *
    * Disputed axioms are excluded — they are excluded from reasoning by §3.4, and including them
    * here would leak them into every downstream projection.
    */
  def asserted(state: KbState): Graph =
    Graph(
      state.reasonableAxioms
        .map(r => r.axiom -> Set[Support](Support.Asserted(r.id)))
        .toMap
    )

  /** The current graph: asserted axioms plus ongoing fluents materialized as plain triples.
    *
    * This is the graph "now" reasoning runs over (SPEC §3.6). Because a fluent contributes an
    * ordinary assertion, no downstream consumer — reasoner, query, verbalizer — needs to know that
    * fluents exist at all.
    */
  def current(state: KbState): Graph =
    state.ongoingFluents.foldLeft(asserted(state)): (graph, fluent) =>
      graph.withAxiom(fluent.assertion, Set(Support.FromFluent(fluent.id)))

  /** The graph as of a past date: fluents that held on `date` rather than those ongoing now.
    *
    * Note this rewinds *fluents* only. To rewind assertions too, replay the journal to the matching
    * sequence first ([[KbState.replayUntil]]) and pass the result here.
    */
  def asOf(state: KbState, date: LocalDate): Graph =
    state.fluentsHeldOn(date).foldLeft(asserted(state)): (graph, fluent) =>
      graph.withAxiom(fluent.assertion, Set(Support.FromFluent(fluent.id)))
