package dev.librecybernetics.noesis.gui

import java.time.LocalDate

import cats.effect.IO
import cats.effect.std.Dispatcher
import fs2.io.file.Files
import munit.CatsEffectSuite

import dev.librecybernetics.noesis.app.*
import dev.librecybernetics.noesis.logic.Iri
import dev.librecybernetics.noesis.lms.AnswerSpec
import dev.librecybernetics.noesis.vocab.RelationshipsModule

/** The live GUI interpreters are thin delegations over the real disposable owner boundary. */
class LiveBoundarySuite extends CatsEffectSuite:
  private final class RecordingView extends GuiView:
    def present(): Unit = ()
    def render(model: Model): Unit = ()

  test("owner, clock, effect, and controller live interpreters preserve the shared boundary"):
    Files[IO].tempDirectory.use: root =>
      OwnerSession.open(root).use: session =>
        Dispatcher.sequential[IO].use: dispatcher =>
          val day = LocalDate.of(2026, 8, 5)
          val marco = Iri("noesis:e/marco")
          val actions = OwnerActions.live(session)
          for
            before <- actions.initialized
            initialized <- actions.initialize
            agenda <- actions.agenda(day)
            note <- actions.appendToday("Met Marco.", day)
            prepared <- actions.prepareFact(
              FactInput(EntityChoice.New(marco, "Marco"), RelationshipsModule.birthday, "1990-05-12")
            )
            preview <- IO.fromEither(prepared.left.map(problem => new AssertionError(problem.render)))
            committed <- actions.commit(preview)
            found <- actions.search("Marco")
            entity <- actions.entity(marco)
            queued <- actions.queue(10)
            entry <- IO.fromOption(queued.headOption)(new AssertionError("birthday was not queued"))
            prompt <- actions.question(entry)
            question <- IO.fromOption(prompt.question)(new AssertionError("birthday had no question"))
            response <- question.answer match
              case AnswerSpec.Exact(value)  => IO.pure(value)
              case AnswerSpec.AnyOf(values) => IO.fromOption(values.headOption)(new AssertionError("no answer"))
              case AnswerSpec.Rubric(_)     => IO.raiseError(new AssertionError("unexpected rubric"))
            answered <- actions.answer(question, response, 250L)
            position <- actions.changes.take(1).compile.lastOrError
            today <- GuiClock.live.today
            monotonic <- GuiClock.live.monotonic
            agendaEvent <- Effects.live(session).run(Effect.LoadAgenda)
            controller <- ReactiveController.create(session, dispatcher, _ => RecordingView())
            current <- controller.current
          yield
            assertEquals(before, false)
            assert(initialized.isRight)
            assert(agenda.isEmpty)
            assert(note.isRight)
            assert(committed.isRight)
            assert(found.nonEmpty)
            assertEquals(entity.label, "Marco")
            assert(answered.isRight)
            assert(position.journalSequence > 0L)
            assert(today != null)
            assert(monotonic.length >= 0L)
            agendaEvent match
              case Event.AgendaLoaded(_) => ()
              case other                 => fail(s"unexpected event: $other")
            assertEquals(current, Model())
