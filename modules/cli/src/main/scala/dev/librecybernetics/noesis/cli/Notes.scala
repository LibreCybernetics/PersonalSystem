package dev.librecybernetics.noesis.cli

import java.time.LocalDate
import java.util.Locale

import cats.data.NonEmptyList
import cats.effect.{ExitCode, IO}
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import fs2.io.file.{Files, Path}

import dev.librecybernetics.noesis.core.capture.Intent
import dev.librecybernetics.noesis.core.projection.KbState
import dev.librecybernetics.noesis.core.verbalize.Naming
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.vocab.*

/** Writing, reading back and searching notes (SPEC §8.5, PRODUCT.md J11, J13).
  *
  * The commands are thin: every decision about what an edit means lives in `vocab` so that the CLI,
  * the editor round-trip and any later surface necessarily agree. What is here is the part that
  * genuinely belongs to a terminal — resolving "today", running `$EDITOR`, and keeping the mirror
  * on disk in step with the journal.
  */
object Notes:

  /** Blocks something points at, which are the ones the editor buffer anchors (UX.md §7).
    *
    * "Points at" is deliberately not a list of known properties. A block carries knowledge when
    * some axiom mentions it that is not part of its own structure, so extracted facts and quotes
    * count the moment they exist without this having to be told about them.
    */
  private val structural: Set[Iri] = Set(
    NotesModule.blockOf,
    NotesModule.text,
    NotesModule.order,
    NotesModule.parentBlock,
    Vocab.rdfType
  )

  def loadBearing(state: KbState, note: Outline.Note): Set[Iri] =
    val blocks = note.blocks.map(_.id).toSet
    state.activeAxioms
      .map(_.axiom)
      .collect:
        case Axiom.ObjectAssertion(subject, property, _) if !structural.contains(property) => subject
        case Axiom.DataAssertion(subject, property, _) if !structural.contains(property)   => subject
        case Axiom.ObjectAssertion(_, property, obj) if !structural.contains(property)     => obj
      .toSet
      .intersect(blocks)

  /** Every note in the workspace, newest page first, then everything else by title. */
  def all(state: KbState): List[Outline.Note] =
    state.activeAxioms
      .map(_.axiom)
      .collect:
        case Axiom.ClassAssertion(note, cls)
            if cls == NotesModule.Daily || cls == NotesModule.Permanent ||
              cls == NotesModule.Literature =>
          note
      .toList
      .distinct
      .map(Outline.of(state, _))
      .sortBy(note => (note.title.map(title => -title.length), note.title, note.id.value))

  // ── The mirror (SPEC §8.5.3) ──────────────────────────────────────────────

  def mirrorDirectory(root: Path): Path = root / "notes"

  /** Rewrites the read-only Markdown mirror from the current state.
    *
    * Whole-directory rather than incremental, because §8.5.3 promises the mirror is a projection:
    * deleting it costs nothing and rebuilding it is deterministic. An incremental writer would have
    * to remember what it wrote last time, which is a second source of truth about the notes.
    */
  def mirror(root: Path, state: KbState): IO[Unit] =
    val directory = mirrorDirectory(root)
    val wanted = all(state).map(note => fileName(note) -> NoteMarkdown.render(note)).toMap

    for
      _ <- Files[IO].createDirectories(directory)
      existing <- Files[IO]
        .list(directory)
        .filter(_.extName == ".md")
        .compile
        .toList
      _ <- existing.filterNot(path => wanted.contains(path.fileName.toString)).traverse_(Files[IO].delete)
      _ <- wanted.toList.traverse_ : (name, content) =>
        Files[IO].writeUtf8Lines(directory / name)(fs2.Stream.emits(content.linesIterator.toList)).compile.drain
    yield ()

  /** A name that is greppable and still unique: the title, slugged, with the note's own suffix. */
  private def fileName(note: Outline.Note): String =
    val slug = note.title
      .map(_.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""))
      .filter(_.nonEmpty)
      .getOrElse("note")
    val suffix = note.id.value.substring(note.id.value.lastIndexOf('/') + 1).takeRight(8)
    s"$slug-$suffix.md"

  // ── Running $EDITOR ───────────────────────────────────────────────────────

  /** Materializes `buffer`, opens it in the owner's editor, and returns what was saved.
    *
    * The temporary file is removed however the editor exits. It holds the owner's own writing,
    * which the journal holds anyway — §8.5.4's no-retention rule is about *source* text handed to a
    * reading session, and does not reach here — but leaving a copy of a personal note in the
    * system temporary directory is still not something to do by accident.
    */
  def inEditor(buffer: String): IO[String] =
    val editor = sys.env.get("EDITOR").filter(_.nonEmpty).getOrElse("vi")

    Files[IO]
      .tempFile(None, "noesis-note-", ".md", None)
      .use: file =>
        for
          _ <- write(file, buffer)
          code <- IO.blocking(
            new ProcessBuilder(editor, file.toString).inheritIO().start().waitFor()
          )
          _ <- IO.raiseWhen(code != 0)(
            new RuntimeException(s"$editor exited with status $code; nothing was written")
          )
          saved <- Files[IO].readUtf8(file).compile.string
        yield saved

  private def write(file: Path, content: String): IO[Unit] =
    fs2.Stream
      .emit(content)
      .through(fs2.text.utf8.encode)
      .through(Files[IO].writeAll(file))
      .compile
      .drain

  // ── Rendering ─────────────────────────────────────────────────────────────

  def show(note: Outline.Note): List[String] =
    val body = NoteMarkdown.render(note)
    val warning = Option.when(note.detached.nonEmpty)(
      s"note: ${note.detached.length} block(s) could not be placed in the outline and are shown at " +
        "the top level"
    )
    (Option.when(body.nonEmpty)(body).toList ++ warning.toList)

  def listing(notes: List[Outline.Note]): List[String] =
    if notes.isEmpty then List("no notes yet")
    else
      notes.map: note =>
        val name = note.title.getOrElse("(untitled)")
        f"${note.id.display}%-46s $name (${note.blocks.length} block(s))"

  def backlinks(found: List[Backlinks.InNote], of: Iri): List[String] =
    if found.isEmpty then List(s"nothing written about ${of.display} yet")
    else
      val total = Backlinks.total(found)
      s"$total mention(s) in ${found.length} note(s)" ::
        found.flatMap: entry =>
          s"  ${entry.title.getOrElse(entry.note.display)}" ::
            entry.mentions.map(mention => s"    - ${mention.text}")

  /** Blocks whose text contains `term`, case-insensitively.
    *
    * Text search over the owner's own writing, which the ontology deliberately does not model:
    * §8.5.2 is explicit that tags and search are retrieval aids and never a substitute for it.
    */
  def search(state: KbState, term: String): List[String] =
    val needle = term.toLowerCase(Locale.ROOT)
    val hits = all(state).flatMap: note =>
      note.blocks
        .filter(_.text.toLowerCase(Locale.ROOT).contains(needle))
        .map(block => note -> block)

    if hits.isEmpty then List(s"no blocks contain \"$term\"")
    else
      s"${hits.length} block(s)" :: hits.map: (note, block) =>
        s"  ${note.title.getOrElse(note.id.display)}: ${block.text.linesIterator.next()}"

  // ── Capture helpers ───────────────────────────────────────────────────────

  /** Fresh block identifiers, one per paragraph the passage will become. */
  def freshBlocks(count: Int): IO[List[Iri]] =
    List.fill(count)(UUIDGen[IO].randomUUID.map(NoteIds.block)).sequence

  /** The links in `text` that resolved, as mentions, plus the questions the owner still owes.
    *
    * Unresolved and ambiguous links are reported rather than resolved (§3.5.3): the block is
    * written either way, because losing the sentence would be the worse failure, and the prompt
    * outlives the commit.
    */
  def mentions(state: KbState, block: Iri, text: String): (List[Intent], List[String]) =
    val naming = Naming.from(state, Workspace.config.namingProperties, Workspace.config.namingSchemes)
    val resolutions = NoteLinks.resolve(naming, NoteLinks.parse(text))

    val questions = NoteLinks.unanswered(resolutions).map:
      case NoteLinks.Resolution.Unresolved(link) =>
        s"[[${link.name}]] matches nothing; nothing was created — add the entity, then link it"
      case NoteLinks.Resolution.Ambiguous(link, candidates) =>
        s"[[${link.name}]] matches ${candidates.length}: ${candidates.map(_.display).mkString(", ")}"
      case NoteLinks.Resolution.Resolved(link, _) => s"[[${link.name}]]"

    (NoteLinks.mentions(block, resolutions), questions)

  def resolveDate(on: Option[LocalDate], today: LocalDate): LocalDate = on.getOrElse(today)

  def commitAll(workspace: Workspace, intents: List[Intent]): IO[ExitCode] =
    NonEmptyList.fromList(intents) match
      case None => IO.println("nothing to do").as(ExitCode.Success)
      case Some(batch) =>
        workspace.kb.commit(batch).flatMap:
          case Left(rejected) => IO.println(rejected.render).as(ExitCode.Error)
          case Right(_)       => IO.unit.as(ExitCode.Success)
