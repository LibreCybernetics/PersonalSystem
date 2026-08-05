package dev.librecybernetics.noesis.app

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.std.UUIDGen
import fs2.io.file.Files
import munit.CatsEffectSuite

import dev.librecybernetics.noesis.logic.{Axiom, AxiomAnnotations, Iri, Sensitivity, Vocab}
import dev.librecybernetics.noesis.lms.{AnswerSpec, QueueMode}
import dev.librecybernetics.noesis.vocab.*

/** Shared owner workflow contract used by both presentation adapters (PRODUCT.md J16). */
class OwnerSessionSuite extends CatsEffectSuite:
  test("initialize, preview, commit, and reopen through the durable workspace"):
    Files[IO].tempDirectory.use: root =>
      OwnerSession.open(root).use: session =>
        val marco = Iri("noesis:e/marco")
        for
          before <- session.initialized
          _ <- session.initialize
          after <- session.initialized
          prepared <- session.prepareFact(
            FactInput(EntityChoice.New(marco, "Marco"), Vocab.label, "Marco")
          )
          preview <- IO.fromEither(prepared.left.map(problem => new AssertionError(problem.render)))
          committed <- session.commit(preview)
          _ <- IO.fromEither(committed.left.map(problem => new AssertionError(problem.render)))
          found <- session.search("Marco")
          entity <- session.entity(marco)
        yield
          assertEquals(before, false)
          assertEquals(after, true)
          assert(found.exists {
            case SearchHit.Entity(iri, label) => iri == marco && label == "Marco"
            case _                            => false
          })
          assertEquals(entity.label, "Marco")
          assert(entity.facts.exists(_.id == preview.axiom.id))

  test("blank notes fail with what, why, and next guidance"):
    Files[IO].tempDirectory.use: root =>
      OwnerSession.open(root).use: session =>
        session.appendToday("   ", java.time.LocalDate.of(2026, 8, 1)).map: result =>
          val problem = result.swap.toOption.getOrElse(fail("blank note was accepted"))
          assertEquals(problem.what, "note not saved")
          assert(problem.why.nonEmpty)
          assert(problem.next.nonEmpty)

  test("new entities and note links are one explicit, replayable owner workflow"):
    Files[IO].tempDirectory.use: root =>
      OwnerSession.open(root).use: session =>
        val marco = Iri("noesis:e/marco")
        for
          _ <- session.initialize
          prepared <- session.prepareFact(
            FactInput(EntityChoice.New(marco, "Marco"), Vocab.rdfType, "crm:Person")
          )
          preview <- IO.fromEither(prepared.left.map(problem => new AssertionError(problem.render)))
          _ = assertEquals(preview.axioms.length, 2)
          committed <- session.commit(preview)
          _ <- IO.fromEither(committed.left.map(problem => new AssertionError(problem.render)))
          note <- session.appendToday("Met [[Marco]].", java.time.LocalDate.of(2026, 8, 1))
          _ <- IO.fromEither(note.left.map(problem => new AssertionError(problem.render)))
          state <- Workspace.open(root).flatMap(_.kb.state)
        yield assert(
          state.activeAxioms.exists:
            _.axiom match
              case Axiom.ObjectAssertion(_, property, target) =>
                property == NotesModule.mentions && target == marco
              case _ => false
        )

  test("read-only owner projections are total for a newly initialized workspace"):
    Files[IO].tempDirectory.use: root =>
      OwnerSession.open(root).use: session =>
        val unknown = Iri("noesis:e/unknown")
        for
          initial <- session.position
          first <- session.initialize
          second <- session.initialize
          installed <- session.position
          blankSearch <- session.search("   ")
          termSearch = session.vocabularySearch("birthday")
          search <- session.search("birthday")
          agenda <- session.agenda(java.time.LocalDate.of(2026, 8, 5))
          entity <- session.entity(unknown)
          queue <- session.queue(limit = 1)
        yield
          assertEquals(initial, SessionPosition(0L, 0))
          assert(first.isRight)
          assertEquals(second, Right(List("ontology already installed; nothing to do")))
          assert(installed.journalSequence > 0L)
          assertEquals(blankSearch, Nil)
          assert(termSearch.nonEmpty)
          assert(search.exists {
            case SearchHit.Term(_) => true
            case _                 => false
          })
          assertEquals(agenda, Nil)
          assertEquals(entity.iri, unknown)
          assertEquals(queue, Nil)

  test("a rejected preview is rendered through the shared owner failure contract"):
    Files[IO].tempDirectory.use: root =>
      OwnerSession.open(root).use: session =>
        val same = Iri("noesis:e/same")
        val invalid = Axiom.DataAssertion(same, Vocab.label, dev.librecybernetics.noesis.logic.Literal.string("Same"))
        val invalidAnnotations = AxiomAnnotations.ownerConfirmed.copy(truthConfidence = Some(2.0))
        val preview = new CommitPreview(
          NonEmptyList.one(invalid),
          invalidAnnotations,
          invalid.manchester,
          Sensitivity.Public,
          0.5,
          1.0
        )
        for
          _ <- session.initialize
          result <- session.commit(preview)
        yield
          val rejected = result.swap.toOption.getOrElse(fail("invalid preview was committed"))
          assertEquals(rejected.what, "commit rejected")
          assert(rejected.why.nonEmpty)
          assert(rejected.next.contains("change"))

  test("unresolved note links are committed with a concrete follow-up question"):
    Files[IO].tempDirectory.use: root =>
      OwnerSession.open(root).use: session =>
        for
          _ <- session.initialize
          result <- session.appendToday("Ask [[Nobody]].", java.time.LocalDate.of(2026, 8, 5))
        yield
          val committed = result.toOption.getOrElse(fail("note was not committed"))
          assert(committed.messages.exists(_.contains("matches nothing")))

  test("agenda, note search, and change polling are projections of durable state"):
    Files[IO].tempDirectory.use: root =>
      OwnerSession.open(root).use: session =>
        val owner = Iri("noesis:e/marco")
        val reminder = Iri("noesis:e/reminder")
        val on = java.time.LocalDate.of(2026, 8, 5)
        for
          _ <- session.initialize
          prepared <- session.prepareFact(
            FactInput(EntityChoice.Existing(owner), Vocab.label, "Marco")
          )
          preview <- IO.fromEither(prepared.left.map(problem => new AssertionError(problem.render)))
          _ <- session.commit(preview)
          _ <- Workspace.open(root).flatMap: workspace =>
            for
              intents <- IO.fromEither(
                PrmCapture.reminder(
                ReminderInput(
                  reminder,
                  owner,
                  dev.librecybernetics.noesis.logic.Literal.date(
                    dev.librecybernetics.noesis.logic.PartialDate.from(on)
                  ),
                  "call"
                )
                ).left.map(problems => new AssertionError(problems.mkString("; ")))
              )
              result <- workspace.kb.commit(intents)
            yield result
          _ <- session.appendToday("A searchable thought.", on)
          agenda <- session.agenda(on)
          search <- session.search("searchable")
          positions <- session.changes.take(1).compile.toList
        yield
          assert(agenda.exists(entry => entry.subject == owner && entry.summary == "call"))
          assert(search.exists {
            case SearchHit.NoteBlock(_, _, _, text) => text == "A searchable thought."
            case _                                  => false
          })
          assertEquals(positions.length, 1)

  test("review questions record exact answers and refuse rubric guessing"):
    Files[IO].tempDirectory.use: root =>
      OwnerSession.open(root).use: session =>
        val marco = Iri("noesis:e/marco")
        for
          _ <- session.initialize
          prepared <- session.prepareFact(
            FactInput(EntityChoice.New(marco, "Marco"), RelationshipsModule.birthday, "1990-05-12")
          )
          preview <- IO.fromEither(prepared.left.map(problem => new AssertionError(problem.render)))
          _ <- session.commit(preview)
          queued <- session.queue(QueueMode.Retention, 10)
          entry <- IO.fromOption(queued.headOption)(new AssertionError("birthday was not queued"))
          prompt <- session.question(entry)
          question <- IO.fromOption(prompt.question)(new AssertionError("birthday had no question"))
          response <- question.answer match
            case AnswerSpec.Exact(value)  => IO.pure(value)
            case AnswerSpec.AnyOf(values) => IO.fromOption(values.headOption)(new AssertionError("no answer"))
            case AnswerSpec.Rubric(_)     => IO.raiseError(new AssertionError("unexpected rubric"))
          recorded <- session.answer(question, response, 250L)
          refused <- session.answer(question.copy(answer = AnswerSpec.Rubric("owner judgment")), response, 250L)
          position <- session.position
        yield
          assert(recorded.isRight)
          assertEquals(refused.swap.toOption.map(_.what), Some("review not recorded"))
          assertEquals(position.reviews, 1)

  test("the package seam supplies deterministic UUID generation without global unsafe initialization"):
    Files[IO].tempDirectory.use: root =>
      val fixed = new UUIDGen[IO]:
        def randomUUID = IO.pure(UUID.fromString("00000000-0000-0000-0000-000000000001"))
      OwnerSession.open(root, fixed).use: session =>
        for
          _ <- session.initialize
          result <- session.appendToday("one paragraph", java.time.LocalDate.of(2026, 8, 5))
          state <- Workspace.open(root, fixed).flatMap(_.kb.state)
        yield
          assert(result.isRight)
          assert(state.activeAxioms.nonEmpty)
