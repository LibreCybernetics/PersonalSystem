package dev.librecybernetics.noesis.gui

import cats.effect.{IO, Ref}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all.*
import fs2.Stream
import org.gnome.glib.GLib

import dev.librecybernetics.noesis.app.OwnerSession

/** Serial fs2 event loop joining GTK callbacks to the pure Model-View-Update reducer. */
final class ReactiveController private (
    queue: Queue[IO, Event],
    state: Ref[IO, Model],
    effects: Effects,
    session: OwnerSession,
    dispatcher: Dispatcher[IO],
    view: DesktopView
):
  def dispatch(event: Event): Unit = dispatcher.unsafeRunAndForget(queue.offer(event))
  def present(): Unit = view.present()

  def start: IO[Unit] =
    val events = Stream.fromQueueUnterminated(queue).evalMap: event =>
      for
        transition <- state.modify: current =>
          val (updated, requested) = Update(current, event)
          (updated, (updated, requested))
        (updated, requested) = transition
        _ <- render(updated)
        _ <- requested.traverse_(effect => effects.run(effect).flatMap(queue.offer))
      yield ()

    val external = session.changes.drop(1).evalMap(_ => queue.offer(Event.ExternalChange))
    (events.concurrently(external).compile.drain).start.void *>
      render(Model()) *>
      queue.offer(Event.Started)

  private def render(model: Model): IO[Unit] =
    IO.delay(GLib.idleAddOnce(() => view.render(model))).void

object ReactiveController:
  def create(
      session: OwnerSession,
      dispatcher: Dispatcher[IO],
      viewFactory: (Event => Unit) => DesktopView
  ): IO[ReactiveController] =
    for
      queue <- Queue.unbounded[IO, Event]
      state <- Ref.of[IO, Model](Model())
      dispatch = (event: Event) => dispatcher.unsafeRunAndForget(queue.offer(event))
      view = viewFactory(dispatch)
      controller = ReactiveController(queue, state, Effects(session), session, dispatcher, view)
    yield controller
