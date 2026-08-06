package dev.librecybernetics.noesis.gui

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

import cats.effect.{Deferred, IO}
import cats.effect.std.Dispatcher
import fs2.Stream
import munit.CatsEffectSuite

import dev.librecybernetics.noesis.app.*
import dev.librecybernetics.noesis.logic.Iri
import dev.librecybernetics.noesis.lms.{Question, QueueEntry, ReviewOutcome}

/** The controller owns serialization, rendering, and cancellation rather than leaking a fiber. */
class ReactiveControllerSuite extends CatsEffectSuite:
  private final class StubActions(changeStream: Stream[IO, SessionPosition]) extends OwnerActions:
    def initialized = IO.pure(false)
    def initialize = IO.pure(Right(Nil))
    def agenda(on: LocalDate) = IO.pure(Nil)
    def appendToday(text: String, on: LocalDate) = IO.raiseError(new AssertionError("unexpected note"))
    def prepareFact(input: FactInput) = IO.raiseError(new AssertionError("unexpected fact"))
    def commit(preview: CommitPreview) = IO.raiseError(new AssertionError("unexpected commit"))
    def search(query: String) = IO.pure(Nil)
    def entity(iri: Iri) = IO.pure(EntityView(iri.display, iri, Nil, Nil))
    def queue(limit: Int) = IO.pure(Nil)
    def question(entry: QueueEntry) = IO.pure(ReviewPrompt(entry, None))
    def answer(question: Question, response: String, latencyMs: Long): IO[Either[OwnerProblem, ReviewOutcome]] =
      IO.raiseError(new AssertionError("unexpected answer"))
    def changes = changeStream

  private final class RecordingPresentationView(
      models: AtomicReference[List[DesktopPresentation]]
  ) extends GuiView:
    def present(): Unit = ()
    def render(model: DesktopPresentation): Unit =
      val _ = models.updateAndGet(existing => model :: existing)

  private val immediate = new UiScheduler:
    def apply(action: () => Unit): IO[Unit] = IO(action())

  private val fixedClock = new GuiClock:
    def today = IO.pure(LocalDate.of(2026, 8, 5))
    def monotonic = IO.pure(Duration.Zero)

  test("processing is serial and renders the reducer's resulting model"):
    Dispatcher.sequential[IO].use: dispatcher =>
      val rendered = AtomicReference(List.empty[DesktopPresentation])
      val actions = StubActions(Stream.empty)
      for
        controller <- ReactiveController.create(
          actions,
          Effects(actions, fixedClock),
          dispatcher,
          DesktopViewHandle(_ => RecordingPresentationView(rendered)),
          immediate
        )
        _ <- controller.process(Event.NoteChanged("first"))
        _ <- controller.process(Event.NoteChanged("second"))
        current <- controller.current
      yield
        assertEquals(current.noteDraft, "second")
        assertEquals(rendered.get().map(_.clearNoteDraft).reverse, List(false, false))

  test("the start resource cancels both event and external-change streams"):
    Dispatcher.sequential[IO].use: dispatcher =>
      for
        started <- Deferred[IO, Unit]
        finalized <- Deferred[IO, Unit]
        changes = Stream.emits(List(SessionPosition(0L, 0), SessionPosition(1L, 0))) ++
          Stream
            .bracket(started.complete(()))(_ => finalized.complete(()).map(_ => ()))
            .flatMap(_ => Stream.never[IO])
        actions = StubActions(changes)
        rendered = AtomicReference(List.empty[DesktopPresentation])
        controller <- ReactiveController.create(
          actions,
          Effects(actions, fixedClock),
          dispatcher,
          DesktopViewHandle(_ => RecordingPresentationView(rendered)),
          immediate
        )
        _ <- controller.start.use: _ =>
          started.get *> controller.process(Event.Started)
        _ <- finalized.get.timeout(2.seconds)
      yield assert(rendered.get().nonEmpty)
