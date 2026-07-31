package dev.librecybernetics.noesis.journal

import java.nio.channels.{Channels, FileChannel}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

import cats.data.NonEmptyList
import cats.effect.std.Mutex
import cats.effect.{Async, Clock, Concurrent, Ref}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path
import io.circe.derivation.ConfiguredCodec
import io.circe.parser.parse
import io.circe.syntax.*
import dev.librecybernetics.noesis.logic.Canonical
import dev.librecybernetics.noesis.logic.given

/** The append-only log of operations — the system's only source of truth (SPEC §4, §3.2).
  *
  * Every other graph is a projection rebuildable from this log. Nothing in this interface can
  * rewrite history: there is no update and no delete, only `append`. Retraction is itself an
  * appended operation.
  */
trait Journal[F[_]]:
  /** Append a bundle atomically, assigning sequence numbers. Either all entries land or none. */
  def append(operations: NonEmptyList[Operation]): F[Commit]

  /** Atomically append only when the durable journal still ends at `expectedLastSeq`.
    *
    * Knowledge-base validation is computed from that exact prefix. A conditional append prevents
    * another process from changing the premise between validation and persistence.
    */
  def appendIfCurrent(
      expectedLastSeq: Long,
      operations: NonEmptyList[Operation]
  ): F[Option[Commit]]

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

/** A JSON Lines journal: one atomic commit frame per new line, appended to a file.
  *
  * A frame carries the entire bundle and a checksum, so a crash cannot turn the accepted prefix of
  * a multi-operation commit into a valid shorter commit. A non-LF-terminated final fragment is
  * discarded on open; corruption anywhere else is fatal (DESIGN journal atomicity and deterministic
  * replay).
  */
final class JsonLinesJournal[F[_]: Async] private (
    path: Path,
    expectedIdentity: Option[String]
) extends Journal[F]:

  def append(operations: NonEmptyList[Operation]): F[Commit] =
    appendAt(None, operations).map(_.getOrElse(Commit(Nil)))

  def appendIfCurrent(
      expectedLastSeq: Long,
      operations: NonEmptyList[Operation]
  ): F[Option[Commit]] =
    appendAt(Some(expectedLastSeq), operations)

  private def appendAt(
      expectedLastSeq: Option[Long],
      operations: NonEmptyList[Operation]
  ): F[Option[Commit]] =
    for
      now <- Clock[F].realTimeInstant
      commit <- JsonLines.locked(path, expectedIdentity): channel =>
        JournalFrames.readRecovering(channel).map: existing =>
          val start = existing.lastOption.fold(0L)(_.seq)
          if expectedLastSeq.exists(_ != start) then None
          else
            val entries = operations.toList.zipWithIndex.map: (op, i) =>
              JournalEntry(start + 1 + i, now, op)
            val frame = JournalFrame.create(entries)
            JsonLines.appendAndSync(channel, JsonLines.encode(List(frame)).toArray)
            Some(Commit(entries))
    yield commit

  def stream: Stream[F, JournalEntry] =
    Stream.eval(readEntries).flatMap(Stream.emits)

  def lastSeq: F[Long] = readEntries.map(_.lastOption.fold(0L)(_.seq))

  private def readEntries: F[List[JournalEntry]] =
    JsonLines.locked(path, expectedIdentity)(JournalFrames.readRecovering)

object JsonLinesJournal:
  /** Opens a private journal and validates or recovers its complete committed prefix. */
  def open[F[_]: Async](path: Path): F[JsonLinesJournal[F]] =
    for
      _ <- JsonLines.ensurePrivateFile(path)
      id <- JsonLines.identity(path)
      _ <- JsonLines.locked(path, id)(JournalFrames.readRecovering)
    yield new JsonLinesJournal[F](path, id)

private final case class JournalFrame(
    formatVersion: Int,
    entries: List[JournalEntry],
    checksum: String
) derives ConfiguredCodec

private object JournalFrame:
  val currentVersion = 1

  def create(entries: List[JournalEntry]): JournalFrame =
    JournalFrame(currentVersion, entries, digest(entries))

  def digest(entries: List[JournalEntry]): String =
    val bytes = Canonical.noesis(entries.asJson).getBytes(StandardCharsets.UTF_8)
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** Byte-level frame recovery and validation, kept synchronous inside the file lock. */
private[journal] object JournalFrames:
  private val lf = '\n'.toByte

  def readRecovering(channel: FileChannel): Either[Throwable, List[JournalEntry]] =
    readAll(channel).flatMap: original =>
      val completeLength = original.lastIndexOf(lf) + 1
      val complete = original.take(completeLength)
      channel.truncate(completeLength.toLong)
      channel.force(JsonLines.forceMetadata)
      decodeComplete(complete)

  /** Validates immutable archive bytes without applying live-journal tail recovery. */
  def validate(bytes: Array[Byte]): Either[Throwable, List[JournalEntry]] =
    if bytes.nonEmpty && bytes.last != lf then
      Left(CorruptJournal(bytes.count(_ == lf).toLong + 1L, "final record is not LF-terminated"))
    else decodeComplete(bytes)

  private def decodeComplete(bytes: Array[Byte]): Either[Throwable, List[JournalEntry]] =
    val lines =
      new String(bytes, StandardCharsets.UTF_8)
        .split("\n", -1)
        .toList
        .zipWithIndex
        .filter((line, _) => line.trim.nonEmpty)

    lines
      .traverse: (line, index) =>
        decodeLine(line, index + 1L)
      .flatMap(validateSequence)

  private def decodeLine(
      line: String,
      lineNumber: Long
  ): Either[CorruptJournal, (Long, List[JournalEntry])] =
    parse(line)
      .leftMap(error => CorruptJournal(lineNumber, error.getMessage))
      .flatMap: json =>
        json.hcursor.get[Int]("formatVersion").toOption match
          case None =>
            Left(CorruptJournal(lineNumber, "commit frame has no formatVersion"))

          case Some(version) =>
            json
              .as[JournalFrame]
              .leftMap(error => CorruptJournal(lineNumber, error.getMessage))
              .flatMap: frame =>
                if version != JournalFrame.currentVersion then
                  Left(CorruptJournal(lineNumber, s"unsupported journal frame version: $version"))
                else if frame.entries.isEmpty then
                  Left(CorruptJournal(lineNumber, "a commit frame must contain at least one operation"))
                else
                  val actual = JournalFrame.digest(frame.entries)
                  if actual != frame.checksum then
                    Left(CorruptJournal(lineNumber, "commit frame checksum mismatch"))
                  else Right(lineNumber -> frame.entries)

  private def validateSequence(
      frames: List[(Long, List[JournalEntry])]
  ): Either[CorruptJournal, List[JournalEntry]] =
    val entries = frames.flatMap: (line, frameEntries) =>
      frameEntries.map(line -> _)
    entries
      .foldLeft(
        Right((1L, List.empty[JournalEntry])): Either[
          CorruptJournal,
          (Long, List[JournalEntry])
        ]
      ): (result, located) =>
        result.flatMap: (expected, accepted) =>
          val (line, entry) = located
          if entry.seq != expected then
            Left(
              CorruptJournal(
                line,
                s"expected sequence $expected but found ${entry.seq}"
              )
            )
          else
            entry.operation match
              case Operation.Assert(id, axiom, _) if id != axiom.id =>
                Left(
                  CorruptJournal(
                    line,
                    s"assertion id ${id.value} does not match content id ${axiom.id.value}"
                  )
                )
              case _ => Right((expected + 1, entry :: accepted))
      .map: result =>
        val (_, accepted) = result
        accepted.reverse

  private[journal] def readAll(channel: FileChannel): Either[Throwable, Array[Byte]] =
    channel.position(0L)
    Right(Channels.newInputStream(channel).readAllBytes())

/** An in-memory journal, for tests and for dry-run capture sessions. */
final class InMemoryJournal[F[_]: {Clock, Concurrent}] private (
    state: Ref[F, Vector[JournalEntry]],
    writeLock: Mutex[F]
) extends Journal[F]:

  def append(operations: NonEmptyList[Operation]): F[Commit] =
    appendAt(None, operations).map(_.getOrElse(Commit(Nil)))

  def appendIfCurrent(
      expectedLastSeq: Long,
      operations: NonEmptyList[Operation]
  ): F[Option[Commit]] =
    appendAt(Some(expectedLastSeq), operations)

  private def appendAt(
      expectedLastSeq: Option[Long],
      operations: NonEmptyList[Operation]
  ): F[Option[Commit]] =
    writeLock.lock.surround:
      Clock[F].realTimeInstant.flatMap: now =>
        state.modify: entries =>
          val start = entries.lastOption.fold(0L)(_.seq)
          if expectedLastSeq.exists(_ != start) then (entries, None)
          else
            val fresh = operations.toList.zipWithIndex.map: (op, i) =>
              JournalEntry(start + 1 + i, now, op)
            (entries ++ fresh, Some(Commit(fresh)))

  def stream: Stream[F, JournalEntry] = Stream.evalSeq(state.get.map(_.toList))

  def lastSeq: F[Long] = state.get.map(_.lastOption.fold(0L)(_.seq))

object InMemoryJournal:
  def create[F[_]: {Clock, Concurrent}]: F[InMemoryJournal[F]] =
    (Ref.of[F, Vector[JournalEntry]](Vector.empty), Mutex[F]).mapN(new InMemoryJournal[F](_, _))
