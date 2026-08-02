package dev.librecybernetics.noesis.app

import cats.effect.IO
import fs2.io.file.Files
import munit.CatsEffectSuite

import dev.librecybernetics.noesis.logic.{Axiom, Iri, Vocab}
import dev.librecybernetics.noesis.vocab.NotesModule

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
