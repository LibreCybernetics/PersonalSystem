package noesis.core.event

import cats.effect.Concurrent
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import noesis.logic.*

/** The event vocabulary from SPEC §2.
  *
  * One bus, not one per subsystem: the learning engine reacts to `AxiomAdded` and `StateChanged`
  * without the Knowledge Core knowing the learning engine exists, which is what keeps modules from
  * having to be wired into the core.
  */
enum Event:
  case AxiomAdded(id: AxiomId, axiom: Axiom)
  case AxiomRetracted(id: AxiomId, axiom: Axiom)
  case AnnotationsChanged(id: AxiomId)
  case AxiomStatusChanged(id: AxiomId, status: AxiomStatus)

  /** Materialized entailments changed — invalidates derived-belief and disclosure caches. */
  case EntailmentChanged(added: Set[Axiom], removed: Set[Axiom])

  /** A fluent opened, closed or was superseded (SPEC §3.6).
    *
    * Carries both values because that is what the learning engine needs to retire the old
    * question and raise a *change* item for the new one at elevated priority.
    */
  case StateChanged(
      fluent: FluentId,
      subject: Iri,
      property: Iri,
      previous: Option[Node],
      current: Option[Node]
  )

  case BeliefUpdated(item: String, belief: Double)
  case ReviewCompleted(item: String, grade: Double)
  case AgendaDue(item: String, description: String)

  def name: String = this match
    case _: AxiomAdded          => "axiom.added"
    case _: AxiomRetracted      => "axiom.retracted"
    case _: AnnotationsChanged  => "axiom.annotated"
    case _: AxiomStatusChanged  => "axiom.status.changed"
    case _: EntailmentChanged   => "entailment.changed"
    case _: StateChanged        => "state.changed"
    case _: BeliefUpdated       => "belief.updated"
    case _: ReviewCompleted     => "review.completed"
    case _: AgendaDue           => "agenda.due"

/** An in-process publish/subscribe bus (SPEC §11 names an in-process bus as the reference choice). */
trait EventBus[F[_]]:
  def publish(events: List[Event]): F[Unit]
  def subscribe(bufferSize: Int = 128): Stream[F, Event]

object EventBus:
  def create[F[_]: Concurrent]: F[EventBus[F]] =
    Topic[F, Event].map: topic =>
      new EventBus[F]:
        def publish(events: List[Event]): F[Unit] = events.traverse_(topic.publish1).void
        def subscribe(bufferSize: Int): Stream[F, Event] = topic.subscribe(bufferSize)

  /** A bus that drops everything — for tests and one-shot CLI commands with no subscribers. */
  def noop[F[_]: cats.Applicative]: EventBus[F] =
    new EventBus[F]:
      def publish(events: List[Event]): F[Unit] = ().pure[F]
      def subscribe(bufferSize: Int): Stream[F, Event] = Stream.empty
