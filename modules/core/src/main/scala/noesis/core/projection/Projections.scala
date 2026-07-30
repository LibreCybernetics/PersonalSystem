package noesis.core.projection

import java.time.LocalDate

import noesis.reasoner.{Graph, Support}

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
