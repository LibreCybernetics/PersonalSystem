package noesis.core.journal

import java.nio.charset.StandardCharsets

import cats.data.NonEmptyList
import cats.effect.std.Mutex
import cats.effect.{Async, Clock, Concurrent, Ref}
import cats.syntax.all.*
import fs2.io.file.{Files, Flags, Path}
import fs2.{Chunk, Stream, text}
import io.circe.parser.decode
import io.circe.syntax.*

/** The append-only log of operations — the system's only source of truth (SPEC §4, §3.2).
  *
  * Every other graph is a projection rebuildable from this log. Nothing in this interface can
  * rewrite history: there is no update and no delete, only `append`. Retraction is itself an
  * appended operation.
  */
trait Journal[F[_]]:
  /** Append a bundle atomically, assigning sequence numbers. Either all entries land or none. */
  def append(operations: NonEmptyList[Operation]): F[Commit]

  /** Replay the whole journal in commit order. */
  def stream: Stream[F, JournalEntry]

  /** The highest assigned sequence number, or 0 for an empty journal. */
  def lastSeq: F[Long]

object Journal:
  def appendOne[F[_]](journal: Journal[F])(operation: Operation): F[Commit] =
    journal.append(NonEmptyList.one(operation))

/** Malformed journal content. Fatal by design: a projection built from a partially-read journal
  * would silently disagree with the truth, which is worse than refusing to start.
  */
final case class CorruptJournal(line: Long, detail: String)
    extends RuntimeException(s"corrupt journal at line $line: $detail")

/** A JSON Lines journal: one operation per line, appended to a file.
  *
  * The format is chosen for auditability over compactness — a line is greppable, diffable in git,
  * and recoverable by hand. Snapshot-plus-journal backup (SPEC §3.2) is `cp`.
  *
  * `Files` arrives as an explicit parameter rather than a context bound so it is never resolved
  * implicitly alongside `Async`, which fs2 3.12 deprecates.
  */
final class JsonLinesJournal[F[_]: {Clock, Concurrent}] private (
    path: Path,
    files: Files[F],
    seqRef: Ref[F, Long],
    writeLock: Mutex[F]
) extends Journal[F]:

  def append(operations: NonEmptyList[Operation]): F[Commit] =
    writeLock.lock.surround:
      for
        now <- Clock[F].realTimeInstant
        start <- seqRef.get
        entries = operations.toList.zipWithIndex.map: (op, i) =>
          JournalEntry(start + 1 + i, now, op)
        payload = entries.map(_.asJson.deepDropNullValues.noSpaces).mkString("", "\n", "\n")
        _ <- Stream
          .chunk(Chunk.array(payload.getBytes(StandardCharsets.UTF_8)))
          .through(files.writeAll(path, Flags.Append))
          .compile
          .drain
        _ <- seqRef.set(start + entries.length)
      yield Commit(entries)

  def stream: Stream[F, JournalEntry] =
    Stream.eval(files.exists(path)).flatMap: present =>
      if present then JsonLinesJournal.decodeLines(files, path) else Stream.empty

  def lastSeq: F[Long] = seqRef.get

object JsonLinesJournal:
  /** Opens (creating if absent) a journal at `path`, recovering the sequence counter by replay. */
  def open[F[_]: {Files as files, Async}](path: Path): F[JsonLinesJournal[F]] =
    for
      _ <- path.parent.traverse_(files.createDirectories)
      exists <- files.exists(path)
      _ <- Async[F].whenA(!exists)(files.createFile(path))
      last <- highestSeq(files, path)
      ref <- Ref.of[F, Long](last)
      lock <- Mutex[F]
    yield new JsonLinesJournal[F](path, files, ref, lock)

  private def decodeLines[F[_]: Concurrent](
      files: Files[F],
      path: Path
  ): Stream[F, JournalEntry] =
    files
      .readAll(path)
      .through(text.utf8.decode)
      .through(text.lines)
      .zipWithIndex
      .filter((line, _) => line.trim.nonEmpty)
      .evalMap: (line, idx) =>
        decode[JournalEntry](line) match
          case Right(entry) => entry.pure[F]
          case Left(err)    => CorruptJournal(idx + 1, err.getMessage).raiseError[F, JournalEntry]

  private def highestSeq[F[_]: Async](files: Files[F], path: Path): F[Long] =
    decodeLines(files, path).map(_.seq).fold(0L)(_.max(_)).compile.lastOrError

/** An in-memory journal, for tests and for dry-run capture sessions. */
final class InMemoryJournal[F[_]: {Clock, Concurrent}] private (
    state: Ref[F, Vector[JournalEntry]],
    writeLock: Mutex[F]
) extends Journal[F]:

  def append(operations: NonEmptyList[Operation]): F[Commit] =
    writeLock.lock.surround:
      Clock[F].realTimeInstant.flatMap: now =>
        state.modify: entries =>
          val start = entries.lastOption.fold(0L)(_.seq)
          val fresh = operations.toList.zipWithIndex.map: (op, i) =>
            JournalEntry(start + 1 + i, now, op)
          (entries ++ fresh, Commit(fresh))

  def stream: Stream[F, JournalEntry] = Stream.evalSeq(state.get.map(_.toList))

  def lastSeq: F[Long] = state.get.map(_.lastOption.fold(0L)(_.seq))

object InMemoryJournal:
  def create[F[_]: {Clock, Concurrent}]: F[InMemoryJournal[F]] =
    (Ref.of[F, Vector[JournalEntry]](Vector.empty), Mutex[F]).mapN(new InMemoryJournal[F](_, _))
