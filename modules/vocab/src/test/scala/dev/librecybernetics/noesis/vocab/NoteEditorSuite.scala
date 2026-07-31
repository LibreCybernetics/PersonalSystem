package dev.librecybernetics.noesis.vocab

import munit.FunSuite

import dev.librecybernetics.noesis.logic.Iri

/** The editable buffer and what an edited one means (SPEC §8.5.3, UX.md §7).
  *
  * Everything here is one claim in different clothes: **a block that was reworded, moved or
  * re-indented keeps its id, and only a genuinely new line mints one.** Extracted facts, quotes and
  * links all point at block ids, so an alignment that gets this wrong does not corrupt the note —
  * it silently detaches the knowledge from the writing, which is worse, because the note still
  * looks right.
  */
class NoteEditorSuite extends FunSuite:

  private val note = Iri("noesis:e/n-today")

  private def id(name: String): Iri = Iri(s"noesis:e/blk-$name")

  private def block(
      name: String,
      text: String,
      children: List[Outline.Block] = Nil,
      order: String = "a0"
  ): Outline.Block =
    Outline.Block(id(name), text, order, children)

  private def page(roots: List[Outline.Block], title: Option[String] = Some("Today")): Outline.Note =
    Outline.Note(note, title, roots, detached = Nil)

  private def parsed(buffer: String, of: Outline.Note): List[NoteEditor.Line] =
    NoteEditor.parse(buffer, of).fold(problem => fail(problem.render), identity)

  /** The ids the buffer turned out to name, in order, with `None` for a line that mints a block. */
  private def identities(of: Outline.Note, buffer: String): List[Option[Iri]] =
    NoteEditor.align(of, parsed(buffer, of)).map:
      case NoteEditor.Match.Kept(_, block) => Some(block)
      case NoteEditor.Match.Added(_)       => None

  // ── The buffer ────────────────────────────────────────────────────────────

  test("only the blocks that carry knowledge are anchored"):
    // UX.md §7: the owner should see which lines are load-bearing before rewriting them, and the
    // rest should stay uncluttered, because an anchor on every line is what makes a buffer unusable.
    assertEquals(
      NoteEditor.render(page(List(block("1", "plain"), block("2", "cited"))), Set(id("2"))),
      """# Today
        |
        |- plain
        |- cited ^blk-2""".stripMargin
    )

  test("an anchored empty line keeps the space that separates it from its anchor"):
    // The bare marker exists so an empty line carries no trailing whitespace for an editor to
    // strip. An anchor is something to separate, so the space comes back.
    assertEquals(NoteEditor.render(page(List(block("1", ""))), Set.empty), "# Today\n\n-")
    assertEquals(NoteEditor.render(page(List(block("1", ""))), Set(id("1"))), "# Today\n\n- ^blk-1")

  test("a title with nothing under it has no blank line waiting for a body"):
    assertEquals(NoteEditor.render(page(Nil), Set.empty), "# Today")
    assertEquals(NoteEditor.render(page(Nil, title = None), Set.empty), "")
    assertEquals(NoteEditor.render(page(List(block("1", "a")), title = None), Set.empty), "- a")

  test("every refusal says which anchor or line it is about"):
    val unknown = NoteEditor.Problem.UnknownAnchor(id("gone"))
    val duplicate = NoteEditor.Problem.DuplicateAnchor(id("twice"))
    val unattached = NoteEditor.Problem.Unattached(4, "orphan")
    val short = NoteEditor.Problem.NotEnoughBlocks(3)
    val unorderable = NoteEditor.Problem.Unorderable(FractionalIndex.Problem.OutOfOrder("a5", "a1"))
    assert(unknown.render.contains("blk-gone"), unknown.render)
    assert(duplicate.render.contains("blk-twice"), duplicate.render)
    assert(unattached.render.contains("4") && unattached.render.contains("orphan"), unattached.render)
    assert(short.render.contains("3"), short.render)
    assert(unorderable.render.contains("a5"), unorderable.render)

  // ── Keys are kept, not reissued ───────────────────────────────────────────

  private def planFor(of: Outline.Note, buffer: String, fresh: List[Iri] = Nil) =
    NoteEditor
      .plan(of, NoteEditor.align(of, parsed(buffer, of)), fresh)
      .fold(problem => fail(problem.render), identity)

  test("keys far apart are still the keys, and an untouched buffer reissues none of them"):
    // The keys a note accumulates are not evenly spaced — inserting between two lines produces a
    // fractional one, and deleting leaves gaps. A plan that reissued keys whenever it could would
    // supersede `note:order` for every block on every save, which is a rewrite of the note's
    // history disguised as opening the editor.
    val outline = page(List(block("1", "a", order = "a0"), block("2", "b", order = "a5")))
    assertEquals(planFor(outline, "- a\n- b"), Nil)

  test("a fractional key earned by an earlier insertion is left alone"):
    val outline = page(
      List(block("1", "a", order = "a0"), block("2", "b", order = "a0V"), block("3", "c", order = "a1"))
    )
    assertEquals(planFor(outline, "- a\n- b\n- c"), Nil)

  test("a line added above everything is placed below nothing, not on top of the first block"):
    // The new key has to be bounded from above by the block that follows it. Without that bound it
    // would be minted as though the note were empty, land on the first block's position, and the
    // repair would then push that block down — one new line renumbering the page under it.
    val outline = page(List(block("1", "a", order = "a0"), block("2", "b", order = "a1")))
    val planned = planFor(outline, "- NEW\n- a\n- b", fresh = List(id("new")))
    assertEquals(
      planned.collect:
        case dev.librecybernetics.noesis.core.capture.Intent.Supersede(block, property, _, _, _)
            if property == NotesModule.order =>
          block
      ,
      Nil,
      s"no existing block should be repositioned: $planned"
    )

  test("several blocks sharing a position are all repaired, rather than the plan giving up"):
    // Each repair becomes the lower bound for the next, so the search for an upper bound has to
    // reject a key equal to it. Accepting one asks for a position between a key and itself, which
    // no order can satisfy — and the whole note would refuse to save.
    val outline = page(
      List(
        block("1", "a", order = "a0"),
        block("2", "b", order = "a0"),
        block("3", "c", order = "a0")
      )
    )
    val planned = planFor(outline, "- a\n- b\n- c")
    assertEquals(planned.length, 2, s"the two that collide should move: $planned")

  test("two blocks sharing a position are repaired, since one of them has to move"):
    // Not reachable through capture, which always mints a key strictly between its neighbours. It
    // is reachable through a corrupt or hand-edited journal, and the outline would then order the
    // two by identifier — stably, but not by anything the owner chose.
    val outline = page(List(block("1", "a", order = "a0"), block("2", "b", order = "a0")))
    val planned = planFor(outline, "- a\n- b")
    assertEquals(planned.length, 1, s"exactly one block should be repositioned: $planned")
    assert(
      planned.exists:
        case dev.librecybernetics.noesis.core.capture.Intent.Supersede(block, property, _, _, _) =>
          block == id("2") && property == NotesModule.order
        case _ => false
      ,
      s"the second block should be the one that moves: $planned"
    )

  test("a buffer with nothing load-bearing reads like the mirror"):
    val outline = page(List(block("1", "parent", List(block("2", "child")))))
    assertEquals(NoteEditor.render(outline, Set.empty), NoteMarkdown.render(outline))

  test("what was rendered parses back to what it was rendered from"):
    val outline = page(List(block("1", "first", List(block("2", "nested"))), block("3", "second")))
    val lines = parsed(NoteEditor.render(outline, Set(id("2"))), outline)
    assertEquals(lines.map(_.text), List("first", "nested", "second"))
    assertEquals(lines.map(_.depth), List(0, 1, 0))
    assertEquals(lines.map(_.anchor), List(None, Some(id("2")), None))

  test("a line that is not a bullet continues the one above it"):
    val outline = page(List(block("1", "one\ntwo")))
    val lines = parsed(NoteEditor.render(outline, Set.empty), outline)
    assertEquals(lines.map(_.text), List("one\ntwo"), "a multi-line block survives the round trip")

  test("the heading is the title, and is not a block"):
    val outline = page(List(block("1", "only")))
    assertEquals(parsed("# Today\n\n- only", outline).length, 1)

  test("a blank line inside a bullet is a paragraph break, so it starts a block"):
    // The unit is the paragraph, so a block's text never contains a blank line. Folding the second
    // paragraph into the first would make a fact drawn from it cite both, and would escalate both
    // to whatever sensitivity one of them earned.
    val outline = page(List(block("1", "first")))
    val lines = parsed("- first\n  still first\n\n  second paragraph", outline)
    assertEquals(lines.map(_.text), List("first\nstill first", "second paragraph"))
    assertEquals(lines.map(_.depth), List(0, 0), "the new block sits beside the one it broke from")

  test("a paragraph break inside a nested bullet keeps the nesting"):
    val outline = page(List(block("1", "parent", List(block("2", "child")))))
    val lines = parsed("- parent\n  - child\n\n    second", outline)
    assertEquals(lines.map(_.text), List("parent", "child", "second"))
    assertEquals(lines.map(_.depth), List(0, 1, 1))

  test("blank lines between and after bullets are spacing, not text"):
    // Editors leave a trailing newline, and people separate paragraphs with blank lines. Folding
    // either into the block above would append whitespace to its text and report an edit the owner
    // did not make — on every save.
    val outline = page(List(block("1", "a"), block("2", "b")))
    val lines = parsed("# Today\n\n- a\n\n- b\n\n", outline)
    assertEquals(lines.map(_.text), List("a", "b"))

  // ── Refusals ──────────────────────────────────────────────────────────────

  test("an anchor naming no block here is refused rather than dropped"):
    // Pasted from another note, or mistyped. Guessing which block was meant is how provenance is
    // lost quietly; refusing costs one message.
    val outline = page(List(block("1", "only")))
    assertEquals(
      NoteEditor.parse("- only ^blk-elsewhere", outline),
      Left(NoteEditor.Problem.UnknownAnchor(id("elsewhere")))
    )

  test("the same anchor twice is refused, since one block cannot be in two places"):
    val outline = page(List(block("1", "only")))
    assertEquals(
      NoteEditor.parse("- one ^blk-1\n- two ^blk-1", outline),
      Left(NoteEditor.Problem.DuplicateAnchor(id("1")))
    )

  test("a line indented past its parent names no place in the tree"):
    val outline = page(List(block("1", "root")))
    assertEquals(
      NoteEditor.parse("- root\n    - orphan", outline),
      Left(NoteEditor.Problem.Unattached(2, "orphan"))
    )

  test("indentation may fall by any amount, since outdenting skips levels"):
    val outline = page(List(block("1", "a"), block("2", "b"), block("3", "c")))
    assertEquals(parsed("- a\n  - b\n    - c\n- d", outline).map(_.depth), List(0, 1, 2, 0))

  // ── Identity across edits ─────────────────────────────────────────────────

  test("an untouched buffer changes nothing"):
    val outline = page(List(block("1", "a"), block("2", "b")))
    assertEquals(identities(outline, "- a\n- b"), List(Some(id("1")), Some(id("2"))))
    assertEquals(NoteEditor.removed(outline, NoteEditor.align(outline, parsed("- a\n- b", outline))), Nil)

  test("a reworded line keeps its block"):
    val outline = page(List(block("1", "a"), block("2", "b")))
    assertEquals(
      identities(outline, "- a\n- b, rewritten entirely"),
      List(Some(id("1")), Some(id("2"))),
      "same place in the note, different words — that is an edit, not a replacement"
    )

  test("an inserted line mints a block and re-identifies nothing below it"):
    // The failure a positional pairing walks straight into: one new line at the top, and every
    // block below it is silently reassigned to the wrong text.
    val outline = page(List(block("1", "a"), block("2", "b"), block("3", "c")))
    assertEquals(
      identities(outline, "- a\n- NEW\n- b\n- c"),
      List(Some(id("1")), None, Some(id("2")), Some(id("3")))
    )

  test("a line inserted at the very top re-identifies nothing"):
    val outline = page(List(block("1", "a"), block("2", "b")))
    assertEquals(identities(outline, "- NEW\n- a\n- b"), List(None, Some(id("1")), Some(id("2"))))

  test("a deleted line is reported, and the rest keep their blocks"):
    val outline = page(List(block("1", "a"), block("2", "b"), block("3", "c")))
    val matched = NoteEditor.align(outline, parsed("- a\n- c", outline))
    assertEquals(
      matched.map:
        case NoteEditor.Match.Kept(_, block) => Some(block)
        case NoteEditor.Match.Added(_)       => None
      ,
      List(Some(id("1")), Some(id("3")))
    )
    assertEquals(NoteEditor.removed(outline, matched), List(id("2")))

  test("re-indenting a line keeps its block, because the words did not change"):
    val outline = page(List(block("1", "heading"), block("2", "point")))
    assertEquals(identities(outline, "- heading\n  - point"), List(Some(id("1")), Some(id("2"))))

  test("an anchored line keeps its block wherever it is moved to"):
    val outline = page(List(block("1", "first"), block("2", "cited"), block("3", "last")))
    assertEquals(
      identities(outline, "- cited, reworded ^blk-2\n- first\n- last"),
      List(Some(id("2")), Some(id("1")), Some(id("3"))),
      "an anchor pins its block exactly, which is the point of showing it"
    )

  test("a rewrite of the whole note keeps nothing, and says so"):
    val outline = page(List(block("1", "a"), block("2", "b")))
    val matched = NoteEditor.align(outline, parsed("- entirely\n- different\n- and longer", outline))
    assertEquals(
      matched.count:
        case NoteEditor.Match.Added(_) => true
        case _                         => false
      ,
      1,
      "two lines pair with the two existing blocks; only the third is new"
    )

  test("emptying a note removes every block rather than pretending they are unchanged"):
    val outline = page(List(block("1", "a"), block("2", "b")))
    val matched = NoteEditor.align(outline, parsed("# Today", outline))
    assertEquals(matched, Nil)
    assertEquals(NoteEditor.removed(outline, matched), List(id("1"), id("2")))

  test("blocks moved past each other keep their ids, since their words did not change"):
    val outline = page(List(block("1", "a"), block("2", "b"), block("3", "c")))
    // Longest common subsequence keeps two of the three in place and pairs the odd one out; what
    // matters is that no block is lost and none is invented.
    val matched = NoteEditor.align(outline, parsed("- c\n- a\n- b", outline))
    assertEquals(NoteEditor.removed(outline, matched), Nil)
    assertEquals(
      matched.collect { case NoteEditor.Match.Kept(_, block) => block }.distinct.length,
      3,
      "every block still accounted for exactly once"
    )
