package dev.librecybernetics.noesis.vocab

import munit.FunSuite

import dev.librecybernetics.noesis.logic.Iri

/** The Markdown rendering of a note (SPEC §8.5.3).
  *
  * Built from outline values directly rather than through a knowledge base: the projection is
  * already covered by `OutlineSuite`, and what is under test here is only the text that comes out
  * the other side.
  */
class NoteMarkdownSuite extends FunSuite:

  private val note = Iri("noesis:e/n-today")

  private def block(id: String, text: String, children: List[Outline.Block] = Nil): Outline.Block =
    Outline.Block(Iri(s"noesis:e/blk-$id"), text, "a0", children)

  private def page(title: Option[String], roots: List[Outline.Block]): Outline.Note =
    Outline.Note(note, title, roots, detached = Nil)

  test("a note renders as its title and a bullet list"):
    assertEquals(
      NoteMarkdown.render(page(Some("2026-07-31"), List(block("1", "first"), block("2", "second")))),
      """# 2026-07-31
        |
        |- first
        |- second""".stripMargin
    )

  test("nesting is indentation, because a block is a node and a heading is a level"):
    // A heading hierarchy could not express this: a block indented under a sibling would need to
    // be a deeper heading than its own parent, and the file would misstate the note's shape.
    assertEquals(
      NoteMarkdown.render(
        page(
          Some("Outline"),
          List(
            block("1", "parent", List(block("2", "child", List(block("3", "grandchild"))))),
            block("4", "sibling")
          )
        )
      ),
      """# Outline
        |
        |- parent
        |  - child
        |    - grandchild
        |- sibling""".stripMargin
    )

  test("an untitled note is its outline, with no blank line where a heading was not"):
    assertEquals(NoteMarkdown.render(page(None, List(block("1", "a thought")))), "- a thought")

  test("an empty note renders as nothing to search"):
    assertEquals(NoteMarkdown.render(page(None, Nil)), "")
    assertEquals(NoteMarkdown.render(page(Some("Empty"), Nil)), "# Empty")

  test("a block holding more than one line stays one list item"):
    // Continuation lines sit under the first line's text rather than under its marker. Indenting
    // them by less would end the item and turn the rest of the block into loose paragraphs.
    assertEquals(
      NoteMarkdown.render(page(None, List(block("1", "first line\nsecond line")))),
      """- first line
        |  second line""".stripMargin
    )

  test("a multi-line block nested under another keeps both indentations"):
    assertEquals(
      NoteMarkdown.render(page(None, List(block("1", "parent", List(block("2", "one\ntwo")))))),
      """- parent
        |  - one
        |    two""".stripMargin
    )

  test("an empty block is a bare marker, so the mirror does not differ from itself"):
    // A line just typed has no text yet, and dropping it would lose the block being written. It
    // gets no trailing space either: editors strip those on save, which would make a round trip
    // report an edit nobody made.
    val rendered = NoteMarkdown.render(page(None, List(block("1", ""), block("2", "written"))))
    assertEquals(rendered, "-\n- written")
    assertEquals(rendered.linesIterator.toList.filter(_.endsWith(" ")), Nil, "no trailing space")

  test("text that looks like a bullet is written as it stands"):
    // No escaping: the marker is the first "- " on the line, so what follows is the text whatever
    // it contains, and a line about Markdown syntax survives the round trip unchanged.
    assertEquals(NoteMarkdown.render(page(None, List(block("1", "- not a sub-bullet")))), "- - not a sub-bullet")
