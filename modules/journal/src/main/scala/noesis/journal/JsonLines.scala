package noesis.journal

import java.nio.charset.StandardCharsets

import cats.effect.{Async, Concurrent}
import cats.syntax.all.*
import fs2.io.file.{Files, Flags, Path}
import fs2.{Chunk, Stream, text}
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import noesis.logic.Canonical

/** The JSON Lines profile Noesis persists in — the journal itself and the plainer logs beside it.
  *
  * There is no standards-body specification for JSON Lines, so this is the definition the journal
  * specification points at: each record is one RFC 8259 JSON object restricted to I-JSON (RFC
  * 7493), serialized in the canonical form of [[noesis.logic.Canonical]], written on a single line,
  * terminated by LF, encoded as UTF-8, with no byte-order mark and no whitespace outside string
  * values. The IETF alternative, RFC 7464, frames records with a leading RS (0x1E) byte instead;
  * that was rejected because a control byte per record defeats the properties the format was chosen
  * for — a line that is greppable, diffable in git and recoverable by hand.
  *
  * Encoding and decoding live here rather than in each caller so that the framing has one
  * implementation and one conformance surface. The journal adds sequencing and locking on top; it
  * does not have its own idea of what a line is.
  */
object JsonLines:

  /** One canonical JSON record per line, each LF-terminated. Empty input encodes to no bytes. */
  def encode[A: Encoder](values: List[A]): Chunk[Byte] =
    val payload = values.map(value => s"${Canonical.noesis(value.asJson)}\n").mkString
    Chunk.array(payload.getBytes(StandardCharsets.UTF_8))

  /** Appends records to an existing file, without creating parent directories. */
  def write[F[_]: Concurrent, A: Encoder](files: Files[F], path: Path, values: List[A]): F[Unit] =
    Stream.chunk(encode(values)).through(files.writeAll(path, Flags.Append)).compile.drain

  /** Streams records from a file that is known to exist.
    *
    * A malformed non-empty line is fatal rather than skipped: a projection built from part of the
    * source of truth would silently disagree with it, which is worse than refusing to start.
    */
  def decodeLines[F[_]: Concurrent, A: Decoder](files: Files[F], path: Path): Stream[F, A] =
    files
      .readAll(path)
      .through(text.utf8.decode)
      .through(text.lines)
      .zipWithIndex
      .filter((line, _) => line.trim.nonEmpty)
      .evalMap: (line, index) =>
        decode[A](line) match
          case Right(value) => value.pure[F]
          case Left(err)    => CorruptJournal(index + 1, err.getMessage).raiseError[F, A]

  def append[F[_]: Async, A: Encoder](files: Files[F], path: Path, values: List[A]): F[Unit] =
    if values.isEmpty then Async[F].unit
    else path.parent.traverse_(files.createDirectories) *> write(files, path, values)

  /** Reads a whole file, or an empty list if it does not exist yet. */
  def read[F[_]: Async, A: Decoder](files: Files[F], path: Path): F[List[A]] =
    files.exists(path).flatMap:
      case false => List.empty[A].pure[F]
      case true  => decodeLines[F, A](files, path).compile.toList
