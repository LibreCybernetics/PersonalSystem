package noesis.core.journal

import java.nio.charset.StandardCharsets

import cats.effect.Async
import cats.syntax.all.*
import fs2.io.file.{Files, Flags, Path}
import fs2.{Chunk, Stream, text}
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}

/** Append-only JSON Lines files, the one serialization the system uses (SPEC §10 auditability).
  *
  * The journal has its own implementation because it also assigns sequence numbers under a lock.
  * This is for the plainer logs alongside it — the review log, exports — where the same properties
  * matter (append-only, greppable, recoverable by hand) but ordering needs no coordination.
  */
object JsonLines:

  def append[F[_]: Async, A: Encoder](files: Files[F], path: Path, values: List[A]): F[Unit] =
    if values.isEmpty then Async[F].unit
    else
      val payload = values.map(_.asJson.deepDropNullValues.noSpaces).mkString("", "\n", "\n")
      for
        _ <- path.parent.traverse_(files.createDirectories)
        _ <- Stream
          .chunk(Chunk.array(payload.getBytes(StandardCharsets.UTF_8)))
          .through(files.writeAll(path, Flags.Append))
          .compile
          .drain
      yield ()

  /** Reads a whole file, or an empty list if it does not exist yet. */
  def read[F[_]: Async, A: Decoder](files: Files[F], path: Path): F[List[A]] =
    files.exists(path).flatMap:
      case false => List.empty[A].pure[F]
      case true =>
        files
          .readAll(path)
          .through(text.utf8.decode)
          .through(text.lines)
          .zipWithIndex
          .filter((line, _) => line.trim.nonEmpty)
          .evalMap: (line, idx) =>
            decode[A](line) match
              case Right(value) => value.pure[F]
              case Left(err)    => CorruptJournal(idx + 1, err.getMessage).raiseError[F, A]
          .compile
          .toList
