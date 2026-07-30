package noesis.core.event

import noesis.core.journal.{JournalEntry, Operation}
import noesis.core.model.*
import noesis.core.projection.KbState

/** Derives the event stream implied by journal operations.
  *
  * Split out from the commit path because events are how every downstream projection is built, and
  * the learning engine's items are one of those projections (SPEC §4.1). A process that starts fresh
  * — a CLI invocation, a restart — rebuilds its items by replaying the journal through here, which is
  * only sound because the same function produced the events the first time round.
  */
object Events:

  /** Axiom and state events for one operation, given the state *before* it applied.
    *
    * The prior state is required, not a convenience: a `state.changed` carries the value being
    * replaced, and that value only exists before the operation lands.
    */
  def forOperation(before: KbState, operation: Operation): List[Event] = operation match
    case Operation.Assert(id, axiom, _) => List(Event.AxiomAdded(id, axiom))

    case Operation.Retract(id, _) =>
      before.axiom(id).map(record => Event.AxiomRetracted(id, record.axiom)).toList

    case Operation.Annotate(id, _)      => List(Event.AnnotationsChanged(id))
    case Operation.Reclassify(id, _, _) => List(Event.AnnotationsChanged(id))
    case Operation.Dispute(id, _)       => List(Event.AnnotationsChanged(id))
    case Operation.Undispute(id)        => List(Event.AnnotationsChanged(id))

    case Operation.OpenFluent(fluent) =>
      List(
        Event.StateChanged(
          fluent.id,
          fluent.statedSubject,
          fluent.statedProperty,
          previous = None,
          current = Some(fluent.statedValue)
        )
      )

    case Operation.CloseFluent(id, _, _) =>
      before
        .fluent(id)
        .map: fluent =>
          Event.StateChanged(
            id,
            fluent.statedSubject,
            fluent.statedProperty,
            previous = Some(fluent.statedValue),
            current = None
          )
        .toList

    case Operation.SupersedeFluent(oldId, replacement, _) =>
      // One event carrying both values, so a subscriber can retire the old question and raise a
      // change item for the new one without correlating two events (SPEC §3.6).
      List(
        Event.StateChanged(
          replacement.id,
          replacement.statedSubject,
          replacement.statedProperty,
          previous = before.fluent(oldId).map(_.statedValue),
          current = Some(replacement.statedValue)
        )
      )

  /** Events for a bundle, threading the state forward so each operation sees its predecessors. */
  def forOperations(before: KbState, operations: List[Operation]): List[Event] =
    operations
      .foldLeft((before, List.empty[Event])):
        case ((state, emitted), operation) =>
          val events = forOperation(state, operation)
          val advanced = KbState.step(
            state,
            JournalEntry(state.seq + 1, java.time.Instant.EPOCH, operation)
          )
          (advanced, emitted ++ events)
      ._2

  /** Every axiom and state event the journal implies, in order — used to rebuild projections. */
  def replay(entries: List[JournalEntry]): List[Event] =
    entries
      .foldLeft((KbState.empty, List.empty[Event])):
        case ((state, emitted), entry) =>
          val events = forOperation(state, entry.operation)
          (KbState.step(state, entry), emitted ++ events)
      ._2
