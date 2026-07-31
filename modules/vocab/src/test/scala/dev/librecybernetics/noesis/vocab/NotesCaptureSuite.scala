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
import dev.librecybernetics.noesis.core.verbalize.Naming
import dev.librecybernetics.noesis.journal.InMemoryJournal
import dev.librecybernetics.noesis.logic.*

/** Writing things down (SPEC §8.5.1–§8.5.2, PRODUCT.md J11, J13).
  *
  * Capture is pure, so most of this could be asserted about intent lists alone. It is run through a
  * real knowledge base anyway, because the claim worth testing is not "these intents have this
  * shape" but "writing a note works against the unmodified core" — the same reason `ModuleSuite`
  * is an integration test.
  */
class NotesCaptureSuite extends CatsEffectSuite:

  given SecureRandom[IO] =
    SecureRandom.javaSecuritySecureRandom[IO].unsafeRunSync()(using cats.effect.unsafe.implicits.global)
  given UUIDGen[IO] = UUIDGen.fromSecureRandom[IO]

  private val day = LocalDate.of(2026, 7, 31)
  private val today = NoteIds.daily(day)
  private val lia = Iri("noesis:e/lia")
  private val marco = Iri("noesis:e/marco")

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

    private def outline(note: Iri): IO[Outline.Note] = base.state.map(Outline.of(_, note))

  private def blockId(seed: String): Iri =
    NoteIds.block(UUID.nameUUIDFromBytes(seed.getBytes("UTF-8")))

  private def onlyLink(links: List[NoteLinks.Link]): NoteLinks.Link =
    links match
      case single :: Nil => single
      case other         => fail(s"expected exactly one link, got $other")

  /** Appends `lines` to the page one at a time, as the CLI would, re-reading between each. */
  private def appendAll(base: KnowledgeBase[IO], note: Iri, lines: List[String]): IO[Unit] =
    lines.traverse_ : line =>
      for
        current <- base.outline(note)
        intents <- IO.fromEither(
          NotesCapture
            .append(current, blockId(line), line)
            .leftMap(problem => new AssertionError(problem.render))
        )
        _ <- base.expect(intents)
      yield ()

  // ── Identifiers ───────────────────────────────────────────────────────────

  test("the identifier of a dated page is fixed, and is not to drift"):
    // A golden value, in the same spirit as the PRM record ids. `note today` resolves to this page
    // by deriving the id rather than by searching, so a change to the derivation silently orphans
    // every page already written — the blocks would still exist, in a note nothing points at.
    assertEquals(NoteIds.daily(day), Iri("noesis:e/note-daily-9315f54be7286ced2ec0"))
    assertNotEquals(NoteIds.daily(day), NoteIds.daily(day.plusDays(1)), "each day is its own page")

  test("two notes of the same kind and different titles are two notes"):
    assertNotEquals(
      NoteIds.note(NoteKind.Permanent, "one"),
      NoteIds.note(NoteKind.Permanent, "another")
    )
    assertNotEquals(
      NoteIds.note(NoteKind.Permanent, "same"),
      NoteIds.note(NoteKind.Literature, "same"),
      "a literature note and a permanent note are different things about the same subject"
    )

  test("writing the same sentence twice is two blocks, not one"):
    // Blocks are deliberately not content-addressed: each is separately editable, citable and
    // quotable, so collapsing them would make an edit to one silently edit the other.
    assertNotEquals(blockId("a"), blockId("b"))

  test("a problem says which block and which note it is about"):
    val problem = NoteProblem.NoSuchBlock(today, Iri("noesis:e/blk-nowhere"))
    assert(problem.render.contains("blk-nowhere"), problem.render)
    assert(problem.render.contains(today.display), problem.render)

    val unorderable = NoteProblem.Unorderable(FractionalIndex.Problem.OutOfOrder("a5", "a1"))
    assert(unorderable.render.contains("a5"), unorderable.render)

    val short = NoteProblem.NotEnoughBlocks(4)
    assert(short.render.contains("4"), short.render)

  // ── Opening a page ────────────────────────────────────────────────────────

  test("opening today's page twice is the same page, not two"):
    // J11.1. The id derives from the date, so the CLI need not check whether the page exists —
    // which is what keeps `note today` a single idempotent commit rather than a read-then-write.
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- base.expect(NotesCapture.daily(day))
      state <- base.state
    yield
      val note = Outline.of(state, today)
      assertEquals(note.title, Some("2026-07-31"))
      assertEquals(
        state.activeAxioms.count(_.axiom.signature.contains(today)),
        3,
        "a second opening should add no axioms"
      )

  test("a permanent note is a note, and is not the dated page"):
    val id = NoteIds.note(NoteKind.Permanent, "Local-first software")
    for
      base <- installed
      _ <- base.expect(NotesCapture.note(id, NoteKind.Permanent, "Local-first software", day))
      isNote <- base.entails(Axiom.ClassAssertion(id, NotesModule.Note))
      state <- base.state
    yield
      assert(isNote, "note:Permanent should classify as note:Note")
      assertEquals(Outline.of(state, id).title, Some("Local-first software"))
      assertNotEquals(id, today)

  // ── Adding blocks ─────────────────────────────────────────────────────────

  test("appended lines read back in the order they were written"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("first", "second", "third"))
      note <- base.outline(today)
    yield assertEquals(note.roots.map(_.text), List("first", "second", "third"))

  test("a passage becomes one block per paragraph"):
    // The unit extraction, quoting and §8.5.8's escalation all point at. A single line break stays
    // inside a block; a blank line ends one.
    assertEquals(
      NotesCapture.paragraphs("Local-first is about\nownership.\n\nCRDTs are the mechanism,\nnot the point."),
      List("Local-first is about\nownership.", "CRDTs are the mechanism,\nnot the point.")
    )
    assertEquals(NotesCapture.paragraphs("one line"), List("one line"))
    assertEquals(NotesCapture.paragraphs("  \n\n  "), Nil, "whitespace is not a paragraph")
    assertEquals(
      NotesCapture.paragraphs("a\n   \nb"),
      List("a", "b"),
      "a line of spaces separates as surely as an empty one"
    )

  test("pasting several paragraphs writes them in order, each its own block"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      before <- base.outline(today)
      ids = List(blockId("p1"), blockId("p2"), blockId("p3"))
      intents <- IO.fromEither(
        NotesCapture
          .appendAll(before, ids, "first para\n\nsecond para\n\nthird para")
          .leftMap(problem => new AssertionError(problem.render))
      )
      _ <- base.expect(NonEmptyList.fromListUnsafe(intents))
      after <- base.outline(today)
    yield
      assertEquals(after.roots.map(_.text), List("first para", "second para", "third para"))
      assertEquals(after.roots.map(_.id), ids, "each paragraph is separately addressable")
      val keys = after.roots.map(_.order)
      assertEquals(keys.sorted, keys)
      assertEquals(keys.distinct, keys)

  test("a passage with more paragraphs than identifiers is refused, not truncated"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      before <- base.outline(today)
    yield assertEquals(
      NotesCapture.appendAll(before, List(blockId("only")), "one\n\ntwo"),
      Left(NoteProblem.NotEnoughBlocks(2))
    )

  test("a passage appended to a note that already has blocks continues after them"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("existing"))
      before <- base.outline(today)
      intents <- IO.fromEither(
        NotesCapture
          .appendAll(before, List(blockId("p1"), blockId("p2")), "added one\n\nadded two")
          .leftMap(problem => new AssertionError(problem.render))
      )
      _ <- base.expect(NonEmptyList.fromListUnsafe(intents))
      after <- base.outline(today)
    yield assertEquals(after.roots.map(_.text), List("existing", "added one", "added two"))

  test("a line inserted after another lands between it and the next"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("first", "third"))
      before <- base.outline(today)
      anchor = before.roots.headOption.getOrElse(fail("expected a first block")).id
      intents <- IO.fromEither(
        NotesCapture
          .insertAfter(before, blockId("second"), anchor, "second")
          .leftMap(problem => new AssertionError(problem.render))
      )
      _ <- base.expect(intents)
      after <- base.outline(today)
    yield
      assertEquals(after.roots.map(_.text), List("first", "second", "third"))
      // Not only the rendered order: the key itself has to fall between its neighbours, or the
      // next insertion has no gap to aim at and the outline is one edit from rearranging itself.
      val keys = after.roots.map(_.order)
      assertEquals(keys.sorted, keys)
      assertEquals(keys.distinct, keys)

  test("inserting after the last line appends, with nothing above to squeeze against"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("first", "second"))
      before <- base.outline(today)
      anchor = before.roots.lastOption.getOrElse(fail("expected a last block")).id
      intents <- IO.fromEither(
        NotesCapture
          .insertAfter(before, blockId("third"), anchor, "third")
          .leftMap(problem => new AssertionError(problem.render))
      )
      _ <- base.expect(intents)
      after <- base.outline(today)
    yield assertEquals(after.roots.map(_.text), List("first", "second", "third"))

  test("inserting after a line that is not in the note is refused, not guessed at"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("first"))
      note <- base.outline(today)
    yield
      val stranger = Iri("noesis:e/blk-nowhere")
      assertEquals(
        NotesCapture.insertAfter(note, blockId("x"), stranger, "x"),
        Left(NoteProblem.NoSuchBlock(today, stranger))
      )

  test("a line inserted after a nested line becomes its sibling, not its child"):
    // Pressing return on an indented line continues that list. Indenting is a separate act.
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("heading"))
      withParent <- base.outline(today)
      parent = withParent.roots.headOption.getOrElse(fail("expected a heading")).id
      nested = blockId("nested")
      _ <- base.expect(
        NonEmptyList.fromListUnsafe(
          List(
            Intent.Assert(Axiom.ClassAssertion(nested, NotesModule.Block)),
            Intent.Assert(Axiom.ObjectAssertion(nested, NotesModule.blockOf, today)),
            Intent.Assert(Axiom.DataAssertion(nested, NotesModule.text, Literal.string("a point"))),
            Intent.Assert(Axiom.DataAssertion(nested, NotesModule.order, Literal.string("a0"))),
            Intent.Assert(Axiom.ObjectAssertion(nested, NotesModule.parentBlock, parent))
          )
        )
      )
      before <- base.outline(today)
      intents <- IO.fromEither(
        NotesCapture
          .insertAfter(before, blockId("another"), nested, "another point")
          .leftMap(problem => new AssertionError(problem.render))
      )
      _ <- base.expect(intents)
      after <- base.outline(today)
    yield
      assertEquals(after.roots.map(_.text), List("heading"))
      assertEquals(
        after.roots.flatMap(_.children).map(_.text),
        List("a point", "another point"),
        "the new line should sit beside the one it followed"
      )

  // ── Changing what is there ────────────────────────────────────────────────

  test("rewording keeps the previous wording as history, and the block as itself"):
    // J11.5. The identity claim matters more than the history one: extracted facts, quotes and
    // links all point at the block id, so an edit that minted a new block would orphan them.
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("PR 8072 is open"))
      before <- base.outline(today)
      block = before.roots.headOption.getOrElse(fail("expected a block")).id
      _ <- base.expect(NotesCapture.reword(block, "PR 8072 is still open"))
      after <- base.outline(today)
      state <- base.state
    yield
      assertEquals(after.roots.map(_.text), List("PR 8072 is still open"))
      assertEquals(after.roots.map(_.id), List(block), "the block keeps its identity across an edit")
      assertEquals(state.fluents.values.count(_.statedProperty == NotesModule.text), 2)

  private def moving(
      base: KnowledgeBase[IO],
      note: Iri,
      block: Iri,
      parent: Option[Iri]
  ): IO[Unit] =
    for
      current <- base.outline(note)
      intents <- IO.fromEither(
        NotesCapture
          .move(current, block, parent, "a0")
          .leftMap(problem => new AssertionError(problem.render))
      )
      _ <- base.expect(intents)
    yield ()

  test("indenting a top-level line opens a parent state, since it had none to replace"):
    // Superseding here would be rejected: there is no open state, and the core will not invent a
    // past the journal never recorded. §3.6 draws the distinction; capture has to honour it.
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("heading", "stray"))
      before <- base.outline(today)
      heading = before.roots.headOption.getOrElse(fail("expected a heading")).id
      stray = before.roots.lastOption.getOrElse(fail("expected a stray")).id
      _ <- moving(base, today, stray, Some(heading))
      after <- base.outline(today)
    yield
      assertEquals(after.roots.map(_.text), List("heading"))
      assertEquals(after.roots.flatMap(_.children).map(_.text), List("stray"))

  test("outdenting closes the parent state, because there is no new value for it"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("heading", "stray"))
      before <- base.outline(today)
      heading = before.roots.headOption.getOrElse(fail("expected a heading")).id
      stray = before.roots.lastOption.getOrElse(fail("expected a stray")).id
      _ <- moving(base, today, stray, Some(heading))
      _ <- moving(base, today, stray, None)
      after <- base.outline(today)
      state <- base.state
    yield
      assertEquals(after.roots.map(_.text).sorted, List("heading", "stray"))
      assertEquals(after.roots.flatMap(_.children), Nil)
      assertEquals(
        state.ongoingFluents.count(_.statedProperty == NotesModule.parentBlock),
        0,
        "the state ended rather than being overwritten"
      )

  test("re-indenting under a different block supersedes the parent state"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("first heading", "second heading", "stray"))
      before <- base.outline(today)
      headings = before.roots.map(_.id)
      stray = before.roots.lastOption.getOrElse(fail("expected a stray")).id
      first = headings.headOption.getOrElse(fail("expected a first heading"))
      second = headings.drop(1).headOption.getOrElse(fail("expected a second heading"))
      _ <- moving(base, today, stray, Some(first))
      _ <- moving(base, today, stray, Some(second))
      after <- base.outline(today)
      state <- base.state
    yield
      assertEquals(
        after.roots.map(root => root.text -> root.children.map(_.text)),
        List("first heading" -> Nil, "second heading" -> List("stray"))
      )
      assertEquals(
        state.fluents.values.count(_.statedProperty == NotesModule.parentBlock),
        2,
        "the earlier nesting keeps its interval"
      )

  test("moving a block that is not in the note is refused"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("first"))
      note <- base.outline(today)
    yield
      val stranger = Iri("noesis:e/blk-nowhere")
      assertEquals(
        NotesCapture.move(note, stranger, None, "a0"),
        Left(NoteProblem.NoSuchBlock(today, stranger))
      )

  // ── Links (SPEC §8.5.2) ───────────────────────────────────────────────────

  test("a link resolves to the entity that currently goes by that name"):
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(lia, RelationshipsModule.Person)),
          Intent.Assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Lía García")))
        )
      )
      state <- base.state
    yield
      val naming = Naming.from(state, config.namingProperties, config.namingSchemes)
      val links = NoteLinks.parse("met [[Lía García]] about local-first")
      assertEquals(links.map(_.name), List("Lía García"))
      assertEquals(
        NoteLinks.resolve(naming, links),
        List(NoteLinks.Resolution.Resolved(onlyLink(links), lia))
      )

  test("a link matches regardless of case and spacing, because a person typed it"):
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.one(
          Intent.Assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Lía García")))
        )
      )
      state <- base.state
    yield
      val naming = Naming.from(state, config.namingProperties, config.namingSchemes)
      val links = NoteLinks.parse("[[lía   garcía]]")
      assertEquals(
        NoteLinks.resolve(naming, links).map:
          case NoteLinks.Resolution.Resolved(_, entity) => entity
          case other                                    => fail(s"expected a match, got $other")
        ,
        List(lia)
      )

  test("an unknown name asks rather than minting an entity"):
    // §3.5.3, and the rule F4 records as broken elsewhere. Prose is where a silent new entity
    // would be cheapest to create and hardest to notice.
    for
      base <- installed
      state <- base.state
    yield
      val naming = Naming.from(state, config.namingProperties, config.namingSchemes)
      val links = NoteLinks.parse("spoke to [[Nobody At All]]")
      val resolved = NoteLinks.resolve(naming, links)
      assertEquals(resolved, List(NoteLinks.Resolution.Unresolved(onlyLink(links))))
      assertEquals(NoteLinks.mentions(blockId("b"), resolved), Nil, "nothing may be committed")
      assertEquals(NoteLinks.unanswered(resolved).length, 1)

  test("only the links still owed an answer are asked about"):
    // A block usually mixes both. Prompting about links that already resolved would train the
    // owner to dismiss the prompt, which is how a confirmation step stops being one.
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.one(
          Intent.Assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Lía García")))
        )
      )
      state <- base.state
    yield
      val naming = Naming.from(state, config.namingProperties, config.namingSchemes)
      val resolved = NoteLinks.resolve(naming, NoteLinks.parse("[[Lía García]] and [[Nobody]]"))
      assertEquals(resolved.length, 2)
      assertEquals(NoteLinks.unanswered(resolved).map(_.link.name), List("Nobody"))
      assertEquals(NoteLinks.mentions(blockId("b"), resolved).length, 1)

  test("names that differ only by spacing stay different names"):
    // Normalizing spacing must fold runs of whitespace, not remove it: "Alex Ruiz" and "AlexRuiz"
    // are two people, and collapsing them would turn a resolvable link into a false choice.
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.of(
          Intent.Assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Alex Ruiz"))),
          Intent.Assert(Axiom.DataAssertion(marco, Vocab.label, Literal.string("AlexRuiz")))
        )
      )
      state <- base.state
    yield
      val naming = Naming.from(state, config.namingProperties, config.namingSchemes)
      val links = NoteLinks.parse("[[Alex Ruiz]]")
      assertEquals(
        NoteLinks.resolve(naming, links),
        List(NoteLinks.Resolution.Resolved(onlyLink(links), lia))
      )

  test("two people with the same name are a choice, never a guess"):
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.of(
          Intent.Assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Alex Ruiz"))),
          Intent.Assert(Axiom.DataAssertion(marco, Vocab.label, Literal.string("Alex Ruiz")))
        )
      )
      state <- base.state
    yield
      val naming = Naming.from(state, config.namingProperties, config.namingSchemes)
      val links = NoteLinks.parse("[[Alex Ruiz]] called")
      assertEquals(
        NoteLinks.resolve(naming, links),
        List(NoteLinks.Resolution.Ambiguous(onlyLink(links), List(lia, marco).sortBy(_.value)))
      )

  test("what is not a link is not treated as one"):
    val cases = List(
      "an unclosed [[link swallows nothing" -> Nil,
      "empty [[]] names nothing" -> Nil,
      "plain text" -> Nil,
      "[[one]] and [[two]]" -> List("one", "two"),
      "[[  padded  ]]" -> List("padded")
    )
    cases.foreach: (text, expected) =>
      assertEquals(NoteLinks.parse(text).map(_.name), expected, text)

  test("a link's span is kept, so a prompt can quote the sentence it sits in"):
    val text = "met [[Lía García]] today"
    val link = NoteLinks.parse(text).headOption.getOrElse(fail("expected a link"))
    assertEquals(text.substring(link.start, link.end), "[[Lía García]]")

  test("the same entity linked twice in one block is mentioned once"):
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.one(
          Intent.Assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Lía García")))
        )
      )
      state <- base.state
    yield
      val naming = Naming.from(state, config.namingProperties, config.namingSchemes)
      val resolved = NoteLinks.resolve(naming, NoteLinks.parse("[[Lía García]] and [[lía garcía]]"))
      assertEquals(NoteLinks.mentions(blockId("b"), resolved).length, 1)

  // ── Backlinks (SPEC §8.5.2, J13) ──────────────────────────────────────────

  test("everything written about someone is a query, not a search"):
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(lia, RelationshipsModule.Person)),
          Intent.Assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Lía García")))
        )
      )
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("met [[Lía García]]", "unrelated", "[[Lía García]] again"))
      state <- base.state
      naming = Naming.from(state, config.namingProperties, config.namingSchemes)
      note <- base.outline(today)
      _ <- note.blocks.traverse_ : block =>
        NoteLinks.mentions(block.id, NoteLinks.resolve(naming, NoteLinks.parse(block.text))) match
          case Nil     => IO.unit
          case intents => base.expect(NonEmptyList.fromListUnsafe(intents))
      closure <- base.closure
      after <- base.state
    yield
      val found = Backlinks.of(after, closure, lia)
      assertEquals(found.length, 1, "one note mentions her")
      assertEquals(Backlinks.total(found), 2, "in two of its blocks")
      assertEquals(
        found.flatMap(_.mentions).map(_.text),
        List("met [[Lía García]]", "[[Lía García]] again"),
        "in the order they appear on the page"
      )
      assertEquals(found.map(_.title), List(Some("2026-07-31")))

  test("backlinks are about one person, not about everyone mentioned nearby"):
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.of(
          Intent.Assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Lía García"))),
          Intent.Assert(Axiom.DataAssertion(marco, Vocab.label, Literal.string("Marco Díaz")))
        )
      )
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("about [[Lía García]]", "about [[Marco Díaz]]"))
      state <- base.state
      naming = Naming.from(state, config.namingProperties, config.namingSchemes)
      note <- base.outline(today)
      _ <- note.blocks.traverse_ : block =>
        NoteLinks.mentions(block.id, NoteLinks.resolve(naming, NoteLinks.parse(block.text))) match
          case Nil     => IO.unit
          case intents => base.expect(NonEmptyList.fromListUnsafe(intents))
      closure <- base.closure
      after <- base.state
    yield
      assertEquals(
        Backlinks.of(after, closure, lia).flatMap(_.mentions).map(_.text),
        List("about [[Lía García]]")
      )
      assertEquals(
        Backlinks.of(after, closure, marco).flatMap(_.mentions).map(_.text),
        List("about [[Marco Díaz]]")
      )

  test("notes are listed by title, and an untitled one is not sorted as though it had one"):
    val untitled = Iri("noesis:e/note-untitled")
    for
      base <- installed
      _ <- base.expect(
        NonEmptyList.one(
          Intent.Assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Lía García")))
        )
      )
      _ <- base.expect(NotesCapture.daily(day))
      _ <- base.expect(
        NonEmptyList.one(Intent.Assert(Axiom.ClassAssertion(untitled, NotesModule.Permanent)))
      )
      _ <- appendAll(base, today, List("dated [[Lía García]]"))
      _ <- appendAll(base, untitled, List("untitled [[Lía García]]"))
      state <- base.state
      naming = Naming.from(state, config.namingProperties, config.namingSchemes)
      dated <- base.outline(today)
      loose <- base.outline(untitled)
      _ <- (dated.blocks ++ loose.blocks).traverse_ : block =>
        NoteLinks.mentions(block.id, NoteLinks.resolve(naming, NoteLinks.parse(block.text))) match
          case Nil     => IO.unit
          case intents => base.expect(NonEmptyList.fromListUnsafe(intents))
      closure <- base.closure
      after <- base.state
    yield assertEquals(
      Backlinks.of(after, closure, lia).map(_.title),
      List(None, Some("2026-07-31")),
      "an untitled note sorts before every titled one, rather than under an invented name"
    )

  test("someone never written about has no backlinks, which is not an error"):
    for
      base <- installed
      _ <- base.expect(NotesCapture.daily(day))
      _ <- appendAll(base, today, List("a thought"))
      state <- base.state
      closure <- base.closure
    yield
      assertEquals(Backlinks.of(state, closure, marco), Nil)
      assertEquals(Backlinks.total(Nil), 0)
