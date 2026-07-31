package dev.librecybernetics.noesis.vocab

/** A note as Markdown (SPEC §8.5.3).
  *
  * Two things read this. `noesis note show` renders a note for the terminal, and the **mirror** is
  * a read-only file written on change so that `grep` and `ripgrep` work over notes as they would
  * over any other second brain. The mirror is a projection: deleting it costs nothing and
  * rebuilding it is deterministic, which is why nothing here records anything.
  *
  * The outline is a bullet list because a block is a node with children, and a heading hierarchy
  * cannot express one — headings nest by level, so a block indented under a sibling would have to
  * be a deeper heading than its own parent, and the file would say something false about the shape
  * of the note.
  */
object NoteMarkdown:

  private val indent = "  "
  private val bullet = "- "

  /** The whole note: its title, then its outline.
    *
    * Blocks the outline could not place — a cycle, or a parent in another note — appear at the top
    * level, because [[Outline]] puts them there rather than dropping them. The mirror says nothing
    * about that: it exists so text can be searched, and a note with a malformed outline still has
    * the words in it. `noesis note show` is where the owner is told.
    */
  def render(note: Outline.Note): String =
    val heading = note.title.map(title => s"# $title").toList
    val body = note.roots.flatMap(block => lines(block, depth = 0))
    (heading ++ Option.when(heading.nonEmpty && body.nonEmpty)("").toList ++ body).mkString("\n")

  /** One block and everything under it, parents before children. */
  private def lines(block: Outline.Block, depth: Int): List[String] =
    val margin = indent * depth
    // A block's text may hold more than one line. The continuation lines are indented to sit under
    // the first one's text rather than under its marker, which is what keeps them part of the same
    // list item instead of starting a sibling.
    val written = block.text.split("\n", -1).toList.zipWithIndex.map:
      // A block with nothing written in it yet gets a bare marker rather than a marker and a
      // space. Editors strip trailing whitespace on save, so emitting one would make the mirror
      // differ from itself after a round trip and report an edit nobody made.
      case (line, 0) if line.isEmpty => s"$margin-"
      case (line, 0)                 => s"$margin$bullet$line"
      case (line, _)                 => s"$margin$indent$line"

    written ++ block.children.flatMap(child => lines(child, depth + 1))
