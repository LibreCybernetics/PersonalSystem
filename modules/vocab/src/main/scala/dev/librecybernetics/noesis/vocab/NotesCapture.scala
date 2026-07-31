package dev.librecybernetics.noesis.vocab

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

import cats.data.NonEmptyList

import dev.librecybernetics.noesis.core.capture.Intent
import dev.librecybernetics.noesis.logic.*

/** Which kind of page is being opened (SPEC §8.5.1). */
enum NoteKind(val cls: Iri):
  /** The dated page. Named for the page rather than for the append-only log of §3.2. */
  case Daily extends NoteKind(NotesModule.Daily)

  /** Zettelkasten's atomic note: what the owner now thinks. */
  case Permanent extends NoteKind(NotesModule.Permanent)

  /** What a source said, as distinct from what the owner concluded from it. */
  case Literature extends NoteKind(NotesModule.Literature)

/** Identifiers for notes and blocks.
  *
  * A dated page is derived from its date, so "today" resolves to the same page however it is
  * reached and opening it twice is idempotent. A **block is not content-addressed**: writing the
  * same sentence twice is two blocks, because each is a separate thing the owner wrote and each is
  * separately editable, citable and quotable.
  */
object NoteIds:
  private def digest(seed: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(seed.getBytes(StandardCharsets.UTF_8))
      .take(10)
      .map("%02x".format(_))
      .mkString

  def daily(date: LocalDate): Iri = Iri(s"noesis:e/note-daily-${digest(date.toString)}")

  def note(kind: NoteKind, title: String): Iri =
    Iri(s"noesis:e/note-${kind.toString.toLowerCase(java.util.Locale.ROOT)}-${digest(title)}")

  def block(id: UUID): Iri = Iri(s"noesis:e/blk-$id")

/** Why something could not be written down. */
enum NoteProblem:
  /** The anchor a caller asked to write after is not in the note it named. */
  case NoSuchBlock(note: Iri, block: Iri)

  /** Two siblings whose order keys admit nothing between them, which is a corrupt outline rather
    * than an ordinary editing outcome (see [[FractionalIndex]]).
    */
  case Unorderable(detail: FractionalIndex.Problem)

  /** Fewer identifiers were supplied than the passage has paragraphs. */
  case NotEnoughBlocks(needed: Int)

  def render: String = this match
    case NoSuchBlock(note, block) => s"${block.display} is not a block of ${note.display}"
    case Unorderable(detail)      => s"cannot position the block: $detail"
    case NotEnoughBlocks(needed)  => s"the passage needs $needed blocks"

/** Writing, as intents against the unmodified core (SPEC §8.5.1).
  *
  * Pure, and state-in/intents-out like [[PrmCapture]]: the same functions serve the CLI, the
  * editor round-trip and any later capture surface, so all of them necessarily agree on what
  * appending a line means. Nothing here commits.
  *
  * The rule the shapes encode is §8.5.1's: text, position and parent are fluents, so **rewording
  * supersedes rather than rewrites** and the previous wording keeps its interval. `note:blockOf`
  * is asserted once and never superseded, because a block that moved to another note is a
  * different block — everything pointing at it points at it *in* a note.
  */
object NotesCapture:

  /** Open a page. Committing this twice writes the same axioms, since the id derives from the
    * date, so a caller need not check whether today's page already exists.
    */
  def daily(date: LocalDate): NonEmptyList[Intent] =
    val id = NoteIds.daily(date)
    NonEmptyList.of(
      Intent.Assert(Axiom.ClassAssertion(id, NotesModule.Daily)),
      Intent.Assert(Axiom.DataAssertion(id, NotesModule.title, Literal.string(date.toString))),
      Intent.Assert(
        Axiom.DataAssertion(id, NotesModule.createdOn, Literal.date(PartialDate.from(date)))
      )
    )

  /** Open a note that is not a page in a calendar. */
  def note(id: Iri, kind: NoteKind, title: String, createdOn: LocalDate): NonEmptyList[Intent] =
    NonEmptyList.of(
      Intent.Assert(Axiom.ClassAssertion(id, kind.cls)),
      Intent.Assert(Axiom.DataAssertion(id, NotesModule.title, Literal.string(title))),
      Intent.Assert(
        Axiom.DataAssertion(id, NotesModule.createdOn, Literal.date(PartialDate.from(createdOn)))
      )
    )

  /** The blocks a passage of prose is worth splitting into: one per paragraph.
    *
    * A blank line ends a block; a single line break does not. The unit matters well beyond
    * rendering, because it is what extraction, quoting and §8.5.8's escalation all point at — a
    * fact drawn from the third paragraph should cite that paragraph, and a paragraph that yielded
    * a `sensitive` fact should become sensitive without dragging the two around it along with it.
    */
  def paragraphs(text: String): List[String] =
    text.split("\n\\s*\n").toList.map(_.strip).filter(_.nonEmpty)

  /** Add a block at the end of a note — quick capture, which is the common case (§8.5.3). */
  def append(
      outline: Outline.Note,
      block: Iri,
      text: String
  ): Either[NoteProblem, NonEmptyList[Intent]] =
    position(outline.roots.lastOption.map(_.order), None)
      .map(order => blockIntents(block, outline.id, text, order, parent = None))

  /** Add a passage at the end of a note, one block per paragraph.
    *
    * Needs one identifier per paragraph, and says so rather than silently writing fewer: dropping
    * the paragraphs it could not name would lose exactly the writing it was asked to keep.
    */
  def appendAll(
      outline: Outline.Note,
      blocks: List[Iri],
      text: String
  ): Either[NoteProblem, List[Intent]] =
    val written = paragraphs(text)
    if blocks.length < written.length then Left(NoteProblem.NotEnoughBlocks(written.length))
    else
      written
        .zip(blocks)
        .foldLeft[Either[NoteProblem, (List[Intent], Option[String])]](
          Right((Nil, outline.roots.lastOption.map(_.order)))
        ):
          case (Left(problem), _) => Left(problem)
          case (Right((acc, last)), (paragraph, block)) =>
            position(last, None).map: order =>
              (acc ++ blockIntents(block, outline.id, paragraph, order, None).toList, Some(order))
        .map(_._1)

  /** Add a block directly below `after`, as its sibling.
    *
    * Sibling rather than child on purpose: pressing return on a line continues the list it is in.
    * Indenting is a separate act, and a separate supersession of `note:parentBlock`.
    */
  def insertAfter(
      outline: Outline.Note,
      block: Iri,
      after: Iri,
      text: String
  ): Either[NoteProblem, NonEmptyList[Intent]] =
    siblingsOf(outline, after)
      .toRight(NoteProblem.NoSuchBlock(outline.id, after))
      .flatMap: (parent, siblings) =>
        val below = siblings.dropWhile(_.id != after)
        position(below.headOption.map(_.order), below.drop(1).headOption.map(_.order))
          .map(order => blockIntents(block, outline.id, text, order, parent))

  /** Reword a block. A supersession, so "what did I write then" stays answerable (§8.5.1). */
  def reword(block: Iri, text: String): NonEmptyList[Intent] =
    NonEmptyList.one(Intent.Supersede(block, NotesModule.text, Node.Lit(Literal.string(text))))

  /** Move a block: reposition it among its siblings, re-parent it, or both.
    *
    * Re-parenting is not one operation but three, and §3.6 already draws the distinction. Indenting
    * a top-level line *opens* a state, since it had no parent to replace; re-indenting under a
    * different block *supersedes* one; and outdenting back to the top *closes* one, because there
    * is no new value to supersede it with. Superseding in all three cases would be rejected at
    * capture for the two where no open state exists — which is the core refusing to invent a past
    * the journal never recorded.
    */
  def move(
      outline: Outline.Note,
      block: Iri,
      parent: Option[Iri],
      order: String
  ): Either[NoteProblem, NonEmptyList[Intent]] =
    siblingsOf(outline, block)
      .toRight(NoteProblem.NoSuchBlock(outline.id, block))
      .map: (current, _) =>
        val reparent = (current, parent) match
          case (Some(was), Some(now)) if was != now =>
            List(Intent.Supersede(block, NotesModule.parentBlock, Node.Ref(now)))
          case (None, Some(now)) =>
            List(Intent.OpenState(block, NotesModule.parentBlock, Node.Ref(now)))
          case (Some(_), None) =>
            List(Intent.CloseState(block, NotesModule.parentBlock))
          case _ => Nil

        NonEmptyList(
          Intent.Supersede(block, NotesModule.order, Node.Lit(Literal.string(order))),
          reparent
        )

  private def position(
      lower: Option[String],
      upper: Option[String]
  ): Either[NoteProblem, String] =
    FractionalIndex.between(lower, upper).left.map(NoteProblem.Unorderable(_))

  /** A block at a position already decided, which is what the editor round-trip needs: the buffer
    * fixes where every line goes before any of them is written.
    */
  def blockIntents(
      block: Iri,
      note: Iri,
      text: String,
      order: String,
      parent: Option[Iri]
  ): NonEmptyList[Intent] =
    NonEmptyList(
      Intent.Assert(Axiom.ClassAssertion(block, NotesModule.Block)),
      List(
        Intent.Assert(Axiom.ObjectAssertion(block, NotesModule.blockOf, note)),
        Intent.Assert(Axiom.DataAssertion(block, NotesModule.text, Literal.string(text))),
        Intent.Assert(Axiom.DataAssertion(block, NotesModule.order, Literal.string(order)))
      ) ++ parent.map(p => Intent.Assert(Axiom.ObjectAssertion(block, NotesModule.parentBlock, p)))
    )

  /** The level `block` sits on, as the parent it hangs from and the ordered siblings around it.
    *
    * Structural recursion over the tree [[Outline]] already built, whose children are fields
    * rather than a set recomputed per level — so this descends and cannot circle back.
    */
  private def siblingsOf(
      outline: Outline.Note,
      block: Iri
  ): Option[(Option[Iri], List[Outline.Block])] =
    def search(
        parent: Option[Iri],
        level: List[Outline.Block]
    ): Option[(Option[Iri], List[Outline.Block])] =
      if level.exists(_.id == block) then Some(parent -> level)
      else level.view.flatMap(sibling => search(Some(sibling.id), sibling.children)).headOption

    search(None, outline.roots)
