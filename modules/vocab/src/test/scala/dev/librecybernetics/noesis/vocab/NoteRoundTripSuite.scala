package dev.librecybernetics.noesis.vocab

import java.time.LocalDate
import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.std.{SecureRandom, UUIDGen}
import cats.syntax.all.*
import munit.CatsEffectSuite

import dev.librecybernetics.noesis.core.capture.Intent
import dev.librecybernetics.noesis.core.kb.{KbConfig, KnowledgeBase}
import dev.librecybernetics.noesis.journal.InMemoryJournal
import dev.librecybernetics.noesis.logic.*

/** The editing round-trip, end to end (SPEC §8.5.3, PRODUCT.md J11.4, US-26).
  *
  * `NoteEditorSuite` tests the alignment against outline values. This runs the whole loop against a
  * real knowledge base — render, edit, parse, align, plan, commit, render again — because that is
  * the claim the journey makes and the one that can be wrong in ways no unit test would show: a
  * plan that type-checks and produces intents the core then rejects, or that commits and leaves a
  * different note than the buffer described.
  */
class NoteRoundTripSuite extends CatsEffectSuite:

  given SecureRandom[IO] =
    SecureRandom.javaSecuritySecureRandom[IO].unsafeRunSync()(using cats.effect.unsafe.implicits.global)
  given UUIDGen[IO] = UUIDGen.fromSecureRandom[IO]

  private val day = LocalDate.of(2026, 7, 31)
  private val today = NoteIds.daily(day)

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

    private def outline: IO[Outline.Note] = base.state.map(Outline.of(_, today))

  private def blockId(seed: String): Iri =
    NoteIds.block(UUID.nameUUIDFromBytes(seed.getBytes("UTF-8")))

  private def appendAll(base: KnowledgeBase[IO], lines: List[String]): IO[Unit] =
    lines.traverse_ : line =>
      for
        current <- base.outline
        intents <- IO.fromEither(
          NotesCapture.append(current, blockId(line), line).leftMap(p => new AssertionError(p.render))
        )
        _ <- base.expect(intents)
      yield ()

  /** Saves `buffer` over the note, exactly as `noesis note edit` would. */
  private def save(base: KnowledgeBase[IO], buffer: String): IO[Unit] =
    for
      before <- base.outline
      lines <- IO.fromEither(
        NoteEditor.parse(buffer, before).leftMap(p => new AssertionError(p.render))
      )
      matched = NoteEditor.align(before, lines)
      fresh <- List
        .fill(NoteEditor.additions(matched))(UUIDGen[IO].randomUUID.map(NoteIds.block))
        .sequence
      intents <- IO.fromEither(
        NoteEditor.plan(before, matched, fresh).leftMap(p => new AssertionError(p.render))
      )
      _ <- NonEmptyList.fromList(intents).traverse_(base.expect)
    yield ()

  /** The note as it now reads, with no anchors, so a test can state a buffer and compare. */
  private def shown(base: KnowledgeBase[IO]): IO[String] = base.outline.map(NoteMarkdown.render)

  private def started(lines: List[String]): IO[KnowledgeBase[IO]] =
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, lines)
    yield base

  // ── The loop ──────────────────────────────────────────────────────────────

  test("saving an unchanged buffer writes nothing at all"):
    // The one that matters most for a fluent-backed model: a plan that superseded everything on
    // every save would record a history in which the owner rewrote the note each time they opened
    // it, and `state.changed` would fire for every block.
    for
      base <- started(List("first", "second"))
      before <- base.outline
      buffer = NoteEditor.render(before, Set.empty)
      lines <- IO.fromEither(NoteEditor.parse(buffer, before).leftMap(p => new AssertionError(p.render)))
      intents <- IO.fromEither(
        NoteEditor
          .plan(before, NoteEditor.align(before, lines), Nil)
          .leftMap(p => new AssertionError(p.render))
      )
    yield assertEquals(intents, Nil, "an untouched buffer is not an edit")

  test("rewording a line supersedes only that line"):
    for
      base <- started(List("first", "second"))
      before <- base.outline
      _ <- save(base, "# 2026-07-31\n\n- first\n- second, revised")
      after <- base.outline
      state <- base.state
    yield
      assertEquals(after.roots.map(_.text), List("first", "second, revised"))
      assertEquals(after.roots.map(_.id), before.roots.map(_.id), "both blocks keep their identity")
      assertEquals(
        state.fluents.values.count(_.statedProperty == NotesModule.text),
        3,
        "two blocks, one of which now has a former wording"
      )

  test("a line added in the middle keeps the ones around it exactly as they were"):
    for
      base <- started(List("first", "third"))
      before <- base.outline
      _ <- save(base, "- first\n- second\n- third")
      after <- base.outline
      state <- base.state
    yield
      assertEquals(after.roots.map(_.text), List("first", "second", "third"))
      assertEquals(
        after.roots.map(_.id).filter(before.roots.map(_.id).contains),
        before.roots.map(_.id),
        "the existing blocks are still there, in order"
      )
      assertEquals(
        state.fluents.values.count(_.statedProperty == NotesModule.order),
        3,
        "the neighbours were not renumbered to make room"
      )

  test("indenting a line in the buffer nests it"):
    for
      base <- started(List("heading", "point"))
      before <- base.outline
      _ <- save(base, "- heading\n  - point")
      after <- base.outline
    yield
      assertEquals(after.roots.map(_.text), List("heading"))
      assertEquals(after.roots.flatMap(_.children).map(_.text), List("point"))
      assertEquals(after.blocks.map(_.id).sorted, before.blocks.map(_.id).sorted, "no block was replaced")

  test("outdenting a line in the buffer lifts it back out"):
    for
      base <- started(List("heading", "point"))
      _ <- save(base, "- heading\n  - point")
      _ <- save(base, "- heading\n- point")
      after <- base.outline
      state <- base.state
    yield
      assertEquals(after.roots.map(_.text), List("heading", "point"))
      assertEquals(state.ongoingFluents.count(_.statedProperty == NotesModule.parentBlock), 0)

  test("deleting a line removes it from the note and keeps what it used to say"):
    // Membership is one axiom, so deleting retracts that and nothing else. The text fluents keep
    // their intervals, which is what lets a deleted line still answer "what did this say?" and
    // lets anything already citing the block resolve to something that exists.
    for
      base <- started(List("first", "second"))
      before <- base.outline
      gone = before.roots.lastOption.getOrElse(fail("expected a second block")).id
      _ <- save(base, "- first")
      after <- base.outline
      state <- base.state
    yield
      assertEquals(after.roots.map(_.text), List("first"))
      assertEquals(
        state.fluents.values.count(fluent => fluent.statedSubject == gone),
        2,
        "its text and its position are still in the journal"
      )

  test("a buffer rewritten wholesale still reads back as itself"):
    for
      base <- started(List("a", "b", "c"))
      _ <- save(base, "- one\n  - two\n- three\n- four")
      shown <- shown(base)
    yield assertEquals(shown, "# 2026-07-31\n\n- one\n  - two\n- three\n- four")

  test("what is saved is what is shown, over several rounds"):
    // The round-trip property itself: whatever the buffer says, the note afterwards renders as
    // that buffer. Run more than once, because the second save is the one that meets the state the
    // first left behind.
    val buffers = List(
      "- alpha\n- beta",
      "- alpha\n  - nested\n- beta",
      "- beta\n- alpha\n  - nested",
      "- beta",
      "- beta\n- gamma\n  - delta\n    - epsilon"
    )
    for
      base <- started(List("alpha", "beta"))
      _ <- buffers.traverse_ : buffer =>
        save(base, buffer) *> shown(base).map: rendered =>
          assertEquals(rendered, s"# 2026-07-31\n\n$buffer", s"after saving:\n$buffer")
    yield ()

  test("an anchored line keeps its block even when its words change completely"):
    for
      base <- started(List("first", "load-bearing", "last"))
      before <- base.outline
      cited = before.roots.drop(1).headOption.getOrElse(fail("expected a middle block")).id
      buffer = NoteEditor.render(before, Set(cited))
      edited = buffer.replace("- load-bearing ^", "- nothing like the original ^")
      _ <- save(base, edited)
      after <- base.outline
    yield
      assertEquals(after.roots.map(_.text), List("first", "nothing like the original", "last"))
      assertEquals(after.roots.map(_.id), before.roots.map(_.id), "the anchor pinned it exactly")

  test("a plan cannot be made without enough identifiers for the new lines"):
    for
      base <- started(List("only"))
      before <- base.outline
      lines <- IO.fromEither(
        NoteEditor.parse("- only\n- new one\n- new two", before).leftMap(p => new AssertionError(p.render))
      )
      matched = NoteEditor.align(before, lines)
    yield
      assertEquals(NoteEditor.additions(matched), 2)
      assertEquals(
        NoteEditor.plan(before, matched, List(blockId("one"))),
        Left(NoteEditor.Problem.NotEnoughBlocks(2))
      )

  test("the same buffer always plans to the same intents"):
    // Grouping siblings by parent leaves the order to a map unless it is sorted, and a commit that
    // varies between runs is not one anybody can compare against another.
    for
      base <- started(List("a", "b"))
      before <- base.outline
      lines <- IO.fromEither(
        NoteEditor.parse("- a\n  - b\n- c", before).leftMap(p => new AssertionError(p.render))
      )
      matched = NoteEditor.align(before, lines)
      fresh = List(blockId("c"))
      plans = List.fill(8)(NoteEditor.plan(before, matched, fresh))
    yield assertEquals(plans.distinct.length, 1, "the plan is a function of the buffer")
