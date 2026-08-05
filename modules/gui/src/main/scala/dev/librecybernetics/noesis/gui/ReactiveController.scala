package dev.librecybernetics.noesis.gui

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all.*
import fs2.Stream
import org.gnome.glib.GLib

import dev.librecybernetics.noesis.app.OwnerSession

/** Minimal view contract owned by the controller; GTK remains one live interpreter. */
private[gui] trait GuiView:
  def present(): Unit
  def render(model: Model): Unit

/** Schedules view work on its owning UI thread. */
private[gui] trait UiScheduler:
  def apply(action: () => Unit): IO[Unit]

private[gui] object UiScheduler:
  val glib: UiScheduler = new UiScheduler:
    def apply(action: () => Unit): IO[Unit] = IO.delay(GLib.idleAddOnce(() => action())).void

/** Serial fs2 event loop joining GTK callbacks to the pure Model-View-Update reducer. */
final class ReactiveController private (
    queue: Queue[IO, Event],
    state: Ref[IO, Model],
    effects: Effects,
    changes: Stream[IO, dev.librecybernetics.noesis.app.SessionPosition],
    dispatcher: Dispatcher[IO],
    view: GuiView,
    scheduler: UiScheduler
):
  def dispatch(event: Event): Unit = dispatcher.unsafeRunAndForget(queue.offer(event))
  def present(): Unit = view.present()

  /** Owns the event-loop fiber for exactly as long as the window lifecycle owns this resource. */
  def start: Resource[IO, Unit] =
    val events = Stream.fromQueueUnterminated(queue).evalMap: event =>
      process(event)

    val external = changes.drop(1).evalMap(_ => queue.offer(Event.ExternalChange))
    val loop = events.concurrently(external).compile.drain
    Resource.make(render(Model()) *> queue.offer(Event.Started) *> loop.start)(_.cancel).void

  private[gui] def process(event: Event): IO[Unit] =
    state
      .modify: current =>
        val (updated, requested) = Update(current, event)
        (updated, (updated, requested))
      .flatMap: transition =>
        val (updated, requested) = transition
        render(updated) *>
          requested.traverse_(effect => effects.run(effect).flatMap(queue.offer))

  private[gui] def current: IO[Model] = state.get

  private def render(model: Model): IO[Unit] =
    scheduler(() => view.render(model))

object ReactiveController:
  def create(
      session: OwnerSession,
      dispatcher: Dispatcher[IO],
      viewFactory: (Event => Unit) => GuiView
  ): IO[ReactiveController] =
    val actions = OwnerActions.live(session)
    create(actions, Effects(actions, GuiClock.live), dispatcher, viewFactory, UiScheduler.glib)

  private[gui] def create(
      actions: OwnerActions,
      effects: Effects,
      dispatcher: Dispatcher[IO],
      viewFactory: (Event => Unit) => GuiView,
      scheduler: UiScheduler
  ): IO[ReactiveController] =
    for
      queue <- Queue.unbounded[IO, Event]
      state <- Ref.of[IO, Model](Model())
      dispatch = (event: Event) => dispatcher.unsafeRunAndForget(queue.offer(event))
      view = viewFactory(dispatch)
      controller = ReactiveController(queue, state, effects, actions.changes, dispatcher, view, scheduler)
    yield controller
