package dev.librecybernetics.noesis.vocab

import java.time.LocalDate

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.std.{SecureRandom, UUIDGen}
import cats.syntax.all.*
import munit.CatsEffectSuite

import dev.librecybernetics.noesis.core.capture.Intent
import dev.librecybernetics.noesis.core.kb.{KbConfig, KnowledgeBase}
import dev.librecybernetics.noesis.journal.InMemoryJournal
import dev.librecybernetics.noesis.logic.*

/** Reading a note back out of state (SPEC §8.5.1).
  *
  * A note has no stored structure, so every test here is really the same claim: the outline the
  * owner sees is a projection, and it agrees with what was committed. The interesting cases are
  * where the axiom language cannot enforce what an outline needs — a cycle, a parent in another
  * note — because those are the ones a projection has to survive rather than assume away.
  */
class OutlineSuite extends CatsEffectSuite:

  given SecureRandom[IO] =
    SecureRandom.javaSecuritySecureRandom[IO].unsafeRunSync()(using cats.effect.unsafe.implicits.global)
  given UUIDGen[IO] = UUIDGen.fromSecureRandom[IO]

  private val today = Iri("noesis:e/n-today")
  private val other = Iri("noesis:e/n-other")
  private val alpha = Iri("noesis:e/b-alpha")
  private val beta = Iri("noesis:e/b-beta")
  private val gamma = Iri("noesis:e/b-gamma")

  private val modules = Modules.all
  private val config = Modules.configure(KbConfig.default, modules)

  private def installed: IO[KnowledgeBase[IO]] =
    for
      journal <- InMemoryJournal.create[IO]
      base <- KnowledgeBase[IO](journal, config)
      ontology = Modules.ontology(modules).distinct
      result <- base.commit(NonEmptyList.fromListUnsafe(ontology.map(Intent.Assert(_))))
      _ <- IO.raiseWhen(result.isLeft)(new AssertionError(s"ontology failed to install: $result"))
    yield base

  extension (base: KnowledgeBase[IO])
    private def expect(intents: NonEmptyList[Intent]): IO[Unit] =
      base.commit(intents).flatMap: result =>
        IO.fromEither(result.leftMap(rejected => new AssertionError(rejected.render))).void

  /** A block in `note`, positioned by an order key, optionally under a parent. */
  private def block(
      id: Iri,
      note: Iri,
      text: String,
      order: String,
      parent: Option[Iri] = None
  ): List[Intent] =
    List(
      Intent.Assert(Axiom.ClassAssertion(id, NotesModule.Block)),
      Intent.Assert(Axiom.ObjectAssertion(id, NotesModule.blockOf, note)),
      Intent.Assert(Axiom.DataAssertion(id, NotesModule.text, Literal.string(text))),
      Intent.Assert(Axiom.DataAssertion(id, NotesModule.order, Literal.string(order)))
    ) ++ parent.map(p => Intent.Assert(Axiom.ObjectAssertion(id, NotesModule.parentBlock, p)))

  private def page: List[Intent] =
    List(
      Intent.Assert(Axiom.ClassAssertion(today, NotesModule.Daily)),
      Intent.Assert(Axiom.DataAssertion(today, NotesModule.title, Literal.string("2026-07-31")))
    )

  // ── The shape of a note ───────────────────────────────────────────────────

  test("a note's outline is read back from its blocks, in the order they were positioned"):
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.fromListUnsafe(
          page
            ++ block(beta, today, "second", "a1")
            ++ block(alpha, today, "first", "a0")
        )
      )
      state <- base.state
    yield
      val note = Outline.of(state, today)
      assertEquals(note.title, Some("2026-07-31"))
      assertEquals(note.roots.map(_.text), List("first", "second"), "order keys decide, not commit order")
      assertEquals(note.detached, Nil)

  test("nesting follows parentBlock, and a child is not also a root"):
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.fromListUnsafe(
          page
            ++ block(alpha, today, "parent", "a0")
            ++ block(beta, today, "child", "a0", parent = Some(alpha))
            ++ block(gamma, today, "sibling", "a1")
        )
      )
      state <- base.state
    yield
      val note = Outline.of(state, today)
      assertEquals(note.roots.map(_.text), List("parent", "sibling"))
      assertEquals(note.roots.flatMap(_.children).map(_.text), List("child"))
      assertEquals(note.blocks.length, 3, "every block appears exactly once in the tree")

  test("a block belongs to the note it was asserted into, and to no other"):
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.fromListUnsafe(
          page
            ++ List(Intent.Assert(Axiom.ClassAssertion(other, NotesModule.Daily)))
            ++ block(alpha, today, "mine", "a0")
            ++ block(beta, other, "theirs", "a0")
        )
      )
      state <- base.state
    yield
      assertEquals(Outline.of(state, today).roots.map(_.text), List("mine"))
      assertEquals(Outline.of(state, other).roots.map(_.text), List("theirs"))

  test("a block whose parent lives in another note is placed here, not reached across"):
    // `note:parentBlock` has no per-note constraint to enforce this, so the projection decides —
    // and the honest answer is that an outline never arranges itself out of another page.
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.fromListUnsafe(
          page
            ++ List(Intent.Assert(Axiom.ClassAssertion(other, NotesModule.Daily)))
            ++ block(alpha, other, "elsewhere", "a0")
            ++ block(beta, today, "orphan", "a0", parent = Some(alpha))
        )
      )
      state <- base.state
    yield
      val note = Outline.of(state, today)
      assertEquals(note.roots.map(_.text), List("orphan"))
      assertEquals(note.detached, Nil, "a foreign parent is no parent, not a broken block")

  test("a cycle is placed at the root and reported, never walked"):
    // Irreflexivity rules out a block parenting itself, but the axiom language cannot say that a
    // longer chain is a tree. A projection that trusted it would not terminate.
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.fromListUnsafe(
          page
            ++ block(alpha, today, "a", "a0", parent = Some(beta))
            ++ block(beta, today, "b", "a1", parent = Some(alpha))
            ++ block(gamma, today, "c", "a2")
        )
      )
      state <- base.state
    yield
      val note = Outline.of(state, today)
      assertEquals(note.detached, List(alpha, beta).sortBy(_.value))
      assertEquals(
        note.roots.map(_.text).sorted,
        List("a", "b", "c"),
        "nothing written disappears because the outline was malformed"
      )

  test("a block with no recorded text or position is still part of the note"):
    // A line just typed has neither yet; dropping it would lose the block the owner is writing.
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.fromListUnsafe(
          page
            ++ block(alpha, today, "positioned", "a0")
            ++ List(
              Intent.Assert(Axiom.ClassAssertion(beta, NotesModule.Block)),
              Intent.Assert(Axiom.ObjectAssertion(beta, NotesModule.blockOf, today))
            )
        )
      )
      state <- base.state
    yield
      val note = Outline.of(state, today)
      assertEquals(note.roots.map(_.text), List("positioned", ""))
      assertEquals(note.roots.map(_.id), List(alpha, beta), "an unpositioned block sorts last")

  test("a note with nothing in it is a note, not a failure"):
    for
      base <- installed
      _ <- base.expect(NonEmptyList.fromListUnsafe(page))
      state <- base.state
    yield assertEquals(Outline.of(state, today), Outline.Note(today, Some("2026-07-31"), Nil, Nil))

  test("an untitled note projects without a title rather than inventing one"):
    // The note carries another fact of its own — a creation date — so "no title" has to mean the
    // title property specifically, not "nothing was ever said about this note".
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.fromListUnsafe(
          List(
            Intent.Assert(Axiom.ClassAssertion(today, NotesModule.Permanent)),
            Intent.Assert(
              Axiom.DataAssertion(
                today,
                NotesModule.createdOn,
                Literal.date(PartialDate.of(2026, 7, 31))
              )
            )
          )
            ++ block(alpha, today, "a thought", "a0")
        )
      )
      state <- base.state
    yield assertEquals(Outline.of(state, today).title, None)

  // ── Time travel, from §3.6 and nothing else ───────────────────────────────

  test("as-of reconstructs the wording that held on a date"):
    for
      base <- installed
      _ <- base.expect(NonEmptyList.fromListUnsafe(page ++ block(alpha, today, "first draft", "a0")))
      _ <- base.expect(
        NonEmptyList.one(
          Intent.Supersede(
            alpha,
            NotesModule.text,
            Node.Lit(Literal.string("second draft")),
            Some(PartialDate.of(2026, 7, 20))
          )
        )
      )
      state <- base.state
    yield
      assertEquals(Outline.of(state, today).roots.map(_.text), List("second draft"))
      assertEquals(
        Outline.asOf(state, today, LocalDate.of(2026, 7, 10)).roots.map(_.text),
        List("first draft"),
        "the superseded wording keeps its interval, so the past is still answerable"
      )

  test("as-of reconstructs the arrangement that held, not only the words"):
    // Order is a fluent for exactly this reason: a note that moved a paragraph reads back in the
    // arrangement it had, without the projection knowing anything about dates itself.
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.fromListUnsafe(
          page ++ block(alpha, today, "was first", "a0") ++ block(beta, today, "was second", "a1")
        )
      )
      _ <- base.expect(
        NonEmptyList.one(
          Intent.Supersede(
            alpha,
            NotesModule.order,
            Node.Lit(Literal.string("a2")),
            Some(PartialDate.of(2026, 7, 20))
          )
        )
      )
      state <- base.state
    yield
      assertEquals(Outline.of(state, today).roots.map(_.text), List("was second", "was first"))
      assertEquals(
        Outline.asOf(state, today, LocalDate.of(2026, 7, 10)).roots.map(_.text),
        List("was first", "was second")
      )

  test("as-of reconstructs the nesting that held"):
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.fromListUnsafe(
          page
            ++ block(alpha, today, "heading", "a0")
            ++ block(beta, today, "was nested", "a1", parent = Some(alpha))
        )
      )
      _ <- base.expect(
        NonEmptyList.one(
          Intent.CloseState(
            beta,
            NotesModule.parentBlock,
            Some(Node.Ref(alpha)),
            Some(PartialDate.of(2026, 7, 20))
          )
        )
      )
      state <- base.state
    yield
      assertEquals(Outline.of(state, today).roots.map(_.text), List("heading", "was nested"))
      val past = Outline.asOf(state, today, LocalDate.of(2026, 7, 10))
      assertEquals(past.roots.map(_.text), List("heading"))
      assertEquals(past.roots.flatMap(_.children).map(_.text), List("was nested"))
