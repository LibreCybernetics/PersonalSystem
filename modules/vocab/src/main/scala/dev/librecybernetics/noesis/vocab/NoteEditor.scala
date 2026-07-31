package dev.librecybernetics.noesis.vocab

import scala.util.matching.Regex

import cats.syntax.all.*

import dev.librecybernetics.noesis.core.capture.Intent
import dev.librecybernetics.noesis.logic.*

/** The editable buffer and what an edited one means (SPEC §8.5.3, UX.md §7).
  *
  * Blocks are journaled state, so `$EDITOR` edits a *rendering* of them and the saved text is
  * diffed back into block operations. The whole difficulty is identity: rewording, re-indenting or
  * moving a line must keep its block id, because every extracted fact, quote and link points at
  * those ids, and a diff that mints ids freely silently detaches the knowledge from the writing.
  *
  * Two mechanisms carry identity, and UX.md §7 chose the split between them. Blocks that **carry
  * knowledge** — something points at them, so losing their id would orphan it — are rendered with a
  * visible anchor, which pins them exactly and shows the owner which lines are load-bearing before
  * rewriting them. Everything else is ordinary prose whose id is recoverable by alignment, and
  * which stays uncluttered because clutter on every line is what would make the buffer unusable.
  */
object NoteEditor:

  /** One bullet in the buffer. */
  final case class Line(depth: Int, text: String, anchor: Option[Iri])

  /** Which block a buffer line turned out to be. */
  enum Match:
    /** An existing block, kept: reworded, moved, re-indented, or untouched. */
    case Kept(line: Line, block: Iri)

    /** A line that was not there before, and mints a block. */
    case Added(line: Line)

    def line: Line

  /** What the buffer and the note disagree about, when it cannot be resolved. */
  enum Problem:
    /** An anchor naming a block that is not in this note. Refused rather than dropped: the owner
      * has pasted or mistyped it, and guessing which block was meant is how provenance is lost.
      */
    case UnknownAnchor(anchor: Iri)

    /** The same anchor on two lines. One block cannot be in two places. */
    case DuplicateAnchor(anchor: Iri)

    /** A line indented past its parent — a bullet two levels deeper than the one above it names no
      * place in the tree.
      */
    case Unattached(number: Int, text: String)

    /** [[plan]] was handed fewer fresh identifiers than the buffer has new lines. A caller error
      * rather than an owner one, and reported rather than absorbed, because absorbing it would
      * drop the lines it could not name.
      */
    case NotEnoughBlocks(needed: Int)

    /** The buffer asks for an arrangement no order key can express, which means the note's keys
      * are corrupt rather than that the edit was unreasonable.
      */
    case Unorderable(detail: FractionalIndex.Problem)

    def render: String = this match
      case UnknownAnchor(anchor)    => s"${anchor.display} is not a block of this note"
      case DuplicateAnchor(anchor)  => s"${anchor.display} appears on two lines"
      case Unattached(number, text) => s"line $number is indented past its parent: $text"
      case NotEnoughBlocks(needed)  => s"the buffer needs $needed new blocks"
      case Unorderable(detail)      => s"cannot position the blocks: $detail"

  /** The short form an anchor is written in: the last segment of the block's IRI.
    *
    * Short because the owner reads it. Unambiguous because entity IRIs share one namespace, so the
    * segment determines the IRI — and [[parse]] refuses a name that is not a block of this note
    * rather than resolving it to something plausible.
    */
  private def name(block: Iri): String =
    block.value.substring(block.value.lastIndexOf('/') + 1)

  private val indent = "  "
  private val marker = "- "
  private val bullet: Regex = """^(\s*)-[ ]?(.*)$""".r
  private val anchored: Regex = """^(.*?)\s*\^([A-Za-z0-9\-]+)$""".r

  /** The note as a buffer to edit.
    *
    * Differs from [[NoteMarkdown.render]] in exactly one way — the anchors — because the mirror is
    * for searching and this is for editing, and only one of the two has to survive being written
    * back.
    */
  def render(note: Outline.Note, loadBearing: Set[Iri]): String =
    val heading = note.title.map(title => s"# $title").toList
    val body = note.roots.flatMap(block => lines(block, 0, loadBearing))
    (heading ++ Option.when(heading.nonEmpty && body.nonEmpty)("").toList ++ body).mkString("\n")

  private def lines(block: Outline.Block, depth: Int, loadBearing: Set[Iri]): List[String] =
    val margin = indent * depth
    val anchor = if loadBearing.contains(block.id) then s" ^${name(block.id)}" else ""

    val written = block.text.split("\n", -1).toList.zipWithIndex.map:
      // A block with nothing written in it yet gets a bare marker rather than a marker and a
      // space, since editors strip trailing whitespace on save and the buffer would then differ
      // from itself. An anchored one keeps the space, because something has to separate them.
      case (line, 0) if line.isEmpty => s"$margin-$anchor"
      case (line, 0)                 => s"$margin$marker$line$anchor"
      case (line, _)                 => s"$margin$indent$line"

    written ++ block.children.flatMap(child => lines(child, depth + 1, loadBearing))

  /** Reads a saved buffer back into bullets.
    *
    * A heading line is the title and is not a block; a line that is not a bullet continues the
    * bullet above it, which is how a block holding more than one line survives the round trip.
    * Depth is the indentation divided by the two spaces [[render]] writes, and a line deeper than
    * one level below its predecessor has no parent to attach to.
    */
  def parse(buffer: String, note: Outline.Note): Either[Problem, List[Line]] =
    val known = note.blocks.map(_.id).toSet

    val raw = buffer.linesIterator.toList

    val collected = raw.zipWithIndex.foldLeft(List.empty[(Line, Int)]):
      case (acc, (line, index)) =>
        line match
          case bullet(margin, rest) =>
            val depth = margin.length / indent.length
            val (text, anchor) = rest match
              case anchored(before, id) => (before, Some(Iri(s"noesis:e/$id")))
              case _                    => (rest, None)
            acc :+ (Line(depth, text, anchor), index + 1)

          case skipped if skipped.isBlank || skipped.startsWith("#") => acc

          case continuation =>
            acc.lastOption match
              // A blank line between bullets is spacing; inside one it is a paragraph break, and a
              // paragraph is a block. The question is therefore whether a blank line falls between
              // this line and the block it would otherwise continue — asked of the buffer directly,
              // rather than carried along as state each branch would have to remember to reset.
              case Some((previous, at)) if raw.slice(at, index).exists(_.isBlank) =>
                acc :+ (Line(previous.depth, continuation.trim, None), index + 1)
              case Some((previous, at)) =>
                acc.dropRight(1) :+ (previous.copy(text = s"${previous.text}\n${continuation.trim}"), at)
              case None => acc

    for
      _ <- collected.flatMap(_._1.anchor).find(!known.contains(_)).toLeft(()).left.map(Problem.UnknownAnchor(_))
      _ <- duplicated(collected.flatMap(_._1.anchor)).toLeft(()).left.map(Problem.DuplicateAnchor(_))
      _ <- unattached(collected).toLeft(())
    yield collected.map(_._1)

  private def duplicated(anchors: List[Iri]): Option[Iri] =
    anchors.groupBy(identity).collectFirst { case (anchor, found) if found.length > 1 => anchor }

  private def unattached(collected: List[(Line, Int)]): Option[Problem] =
    collected
      .foldLeft((0, Option.empty[Problem])):
        case ((previous, found), (line, number)) =>
          val problem = Option.when(line.depth > previous + 1)(Problem.Unattached(number, line.text))
          (line.depth.min(previous + 1), found.orElse(problem))
      ._2

  /** Which block each buffer line is, given the note it was rendered from.
    *
    * Anchored lines are fixed points and claim their blocks outright. Everything between two fixed
    * points is aligned by text: lines that read exactly as before keep their blocks, and what is
    * left over on each side is paired in order, so a reworded line keeps its id while a genuinely
    * new one does not. Pairing leftovers *within a gap* rather than across the whole note is what
    * stops one inserted line from re-identifying every line below it.
    */
  def align(note: Outline.Note, lines: List[Line]): List[Match] =
    val claimed = lines.flatMap(_.anchor).toSet
    val blocks = note.blocks.toVector.filterNot(block => claimed.contains(block.id))
    val free = lines.zipWithIndex.collect { case (line, at) if line.anchor.isEmpty => at }.toVector
    val texts = free.flatMap(at => lines.lift(at)).map(_.text)

    // Two passes, stronger evidence first.
    //
    // A line reading exactly as it did is that block, wherever it has moved to. Identical words
    // are the strongest evidence available, and taking the first unclaimed block with that text is
    // not a guess: within a note the texts are almost always distinct, so the match is unique, and
    // where two blocks genuinely read the same either answer says the same thing about the note.
    //
    // This is also what stops an inserted line from shifting every line below it onto the wrong
    // block — the failure a purely positional pairing walks straight into.
    val (unchanged, spare) =
      free.indices.toList.foldLeft((Map.empty[Int, Int], blocks.indices.toList)):
        case ((acc, pool), line) =>
          pool.find(block => blocks.lift(block).map(_.text) == texts.lift(line)) match
            case Some(found) => (acc + (line -> found), pool.filterNot(_ == found))
            case None        => (acc, pool)

    // Whatever remains is paired in document order: same place in the note, different words. That
    // is a rewording, and reading it as a deletion followed by an unrelated insertion is exactly
    // how an extracted fact would lose the sentence it came from.
    val reworded = free.indices.toList.filterNot(unchanged.contains).zip(spare).toMap

    val assigned = (unchanged ++ reworded).flatMap: (line, block) =>
      for
        at <- free.lift(line)
        target <- blocks.lift(block)
      yield at -> target.id

    lines.zipWithIndex.map: (line, at) =>
      line.anchor match
        case Some(anchor) => Match.Kept(line, anchor)
        case None         => assigned.get(at).fold(Match.Added(line))(Match.Kept(line, _))

  /** Blocks the note has and the buffer no longer does. */
  def removed(note: Outline.Note, matched: List[Match]): List[Iri] =
    val kept = matched.collect { case Match.Kept(_, block) => block }.toSet
    note.blocks.map(_.id).filterNot(kept.contains)

  /** How many fresh block identifiers [[plan]] will need for this buffer. */
  def additions(matched: List[Match]): Int = matched.count:
    case Match.Added(_) => true
    case Match.Kept(_, _) => false

  /** What a saved buffer means, as intents against the unmodified core.
    *
    * Only differences are emitted. A line nobody touched produces nothing at all — which matters
    * more than it sounds, because `note:text`, `note:order` and `note:parentBlock` are fluents, so
    * a plan that superseded everything on every save would record an edit history in which the
    * owner rewrote the whole note each time they opened it.
    *
    * Deleting a line **retracts its `note:blockOf` assertion and nothing else** (§3.4). Membership
    * is that one axiom, so removing membership is retracting it; the block's text fluents keep
    * their intervals, so "what did this used to say" stays answerable for a line that is gone, and
    * anything already citing the block still resolves to something that exists.
    */
  def plan(
      note: Outline.Note,
      matched: List[Match],
      fresh: List[Iri]
  ): Either[Problem, List[Intent]] =
    // Identifiers are handed out by buffer position, not by line, because two new lines reading
    // the same are two blocks and must not collect the same identifier.
    val minted = matched.zipWithIndex.collect { case (Match.Added(_), at) => at }.zip(fresh).toMap

    val rows = matched.zipWithIndex.traverse:
      case (Match.Kept(line, block), _) => Some(Row(block, line, fresh = false))
      case (Match.Added(line), at)      => minted.get(at).map(Row(_, line, fresh = true))

    for
      resolved <- rows.toRight(Problem.NotEnoughBlocks(additions(matched)))
      placed <- positions(note, withParents(resolved))
    yield placed.flatMap(change(note, _)) ++ removed(note, matched).map(retire(note, _))

  private final case class Row(block: Iri, line: Line, fresh: Boolean)

  /** A row together with the parent its indentation puts it under, and the key it ends up at —
    * which is the one it already had whenever that key still works.
    */
  private final case class Placed(row: Row, parent: Option[Iri], order: String)

  /** Parents read off the indentation: a line hangs from the nearest line above it that is one
    * level shallower. [[parse]] has already refused anything deeper than that, so the ancestors
    * held here always reach far enough.
    */
  private def withParents(rows: List[Row]): List[(Row, Option[Iri])] =
    rows
      .foldLeft((List.empty[(Row, Option[Iri])], List.empty[Iri])):
        case ((acc, ancestors), row) =>
          val above = ancestors.take(row.line.depth)
          (acc :+ (row, above.lastOption), above :+ row.block)
      ._1

  /** Position keys, minted only where the existing ones will not do.
    *
    * Sibling order is a fractional index precisely so that inserting a line does not renumber the
    * ones around it (PD-08), and this is where that pays: a block whose key already sorts after
    * its new predecessor keeps it, and only the lines that actually moved get a new one.
    */
  private def positions(
      note: Outline.Note,
      rows: List[(Row, Option[Iri])]
  ): Either[Problem, List[Placed]] =
    val existing = note.stateOf

    rows
      .groupBy((_, parent) => parent)
      .toList
      // Sorted so that one buffer always produces one sequence of intents. Grouping alone leaves
      // the order to the map, and a commit that varies run to run is not a commit anyone can
      // compare against another.
      .sortBy((parent, _) => parent.map(_.value))
      .traverse: (parent, siblings) =>
        val keys = siblings.map((row, _) => Option.unless(row.fresh)(existing.get(row.block)).flatten.map(_._2))

        siblings
          .zip(keys)
          .zipWithIndex
          .foldLeft[Either[Problem, (List[Placed], Option[String])]](Right((Nil, None))):
            case (Left(problem), _)                            => Left(problem)
            case (Right((acc, last)), (((row, _), key), at)) =>
              key.filter(existing => last.forall(_ < existing)) match
                case Some(kept) => Right((acc :+ Placed(row, parent, kept), Some(kept)))
                case None =>
                  // The next key still usable bounds the new one from above, so a line dropped
                  // between two settled ones does not push either of them aside.
                  val upper = keys.drop(at + 1).flatten.find(key => last.forall(_ < key))
                  FractionalIndex
                    .between(last, upper)
                    .left
                    .map(Problem.Unorderable(_))
                    .map(made => (acc :+ Placed(row, parent, made), Some(made)))
          .map(_._1)
      .map(_.flatten)

  /** The intents one row implies, which is nothing at all when nothing about it changed. */
  private def change(note: Outline.Note, placed: Placed): List[Intent] =
    val Placed(row, parent, order) = placed
    val was = note.stateOf.get(row.block)

    if row.fresh then
      NotesCapture.blockIntents(row.block, note.id, row.line.text, order, parent).toList
    else
      val reworded = Option
        .unless(was.map(_._1).contains(row.line.text))(NotesCapture.reword(row.block, row.line.text).toList)
        .toList
        .flatten

      val repositioned = Option
        .unless(was.map(_._2).contains(order))(
          Intent.Supersede(row.block, NotesModule.order, Node.Lit(Literal.string(order)))
        )
        .toList

      // The same three-way distinction capture makes: indenting opens a state, re-indenting
      // supersedes one, and outdenting closes one, because there is nothing to supersede it with.
      val reparented = (note.parentOf.get(row.block).flatten, parent) match
        case (was, now) if was == now => Nil
        case (Some(_), Some(now)) =>
          List(Intent.Supersede(row.block, NotesModule.parentBlock, Node.Ref(now)))
        case (None, Some(now)) =>
          List(Intent.OpenState(row.block, NotesModule.parentBlock, Node.Ref(now)))
        case (_, None) => List(Intent.CloseState(row.block, NotesModule.parentBlock))

      reworded ++ repositioned ++ reparented

  /** Membership is one axiom, and identifiers are derived from content, so the axiom to retract can
    * be reconstructed rather than looked up.
    */
  private def retire(note: Outline.Note, block: Iri): Intent =
    Intent.Retract(Axiom.ObjectAssertion(block, NotesModule.blockOf, note.id).id)
