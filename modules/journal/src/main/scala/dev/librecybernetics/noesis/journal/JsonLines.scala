package dev.librecybernetics.noesis.journal

import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.attribute.{PosixFileAttributeView, PosixFilePermissions}
import java.nio.file.{
  Files as JFiles,
  LinkOption,
  Path as JPath,
  StandardOpenOption
}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

import cats.effect.{Async, Concurrent}
import cats.syntax.all.*
import fs2.io.file.{Files, Flags, Path}
import fs2.{Chunk, Stream, text}
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import dev.librecybernetics.noesis.logic.Canonical

/** The JSON Lines profile Noesis persists in — the journal itself and the plainer logs beside it.
  *
  * There is no standards-body specification for JSON Lines, so this is the definition the journal
  * specification points at: each record is one RFC 8259 JSON object restricted to I-JSON (RFC
  * 7493), serialized in the canonical form of [[dev.librecybernetics.noesis.logic.Canonical]], written on a single line,
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

  private val privateDirectory = PosixFilePermissions.fromString("rwx------")
  private val privateFile = PosixFilePermissions.fromString("rw-------")
  private val processLocks = ConcurrentHashMap[String, ReentrantLock]()
  private[journal] val forceMetadata = true

  /** Creates and tightens a single-user persistence file.
    *
    * The state directory and its files are assets in the application threat model, so their
    * confidentiality cannot depend on the caller's umask (DESIGN local-first and Zero Trust).
    * Symlinks are rejected because following one would let a replaced workspace redirect a write.
    */
  private[journal] def ensurePrivateFile[F[_]: Async](path: Path): F[Unit] =
    Async[F].blocking:
      val nio = path.toNioPath
      val parentResult =
        Option(nio.getParent).toRight(
          IllegalStateException(s"persistence path must have a parent directory: $nio")
        ).flatMap: parent =>
          if JFiles.isSymbolicLink(parent) then
            Left(IllegalStateException(s"workspace directory must not be a symlink: $parent"))
          else
            JFiles.createDirectories(parent)
            tighten(parent, privateDirectory)
            Right(())

      parentResult.flatMap: _ =>
        if JFiles.isSymbolicLink(nio) then
          Left(IllegalStateException(s"persistence file must not be a symlink: $nio"))
        else
          if !JFiles.exists(nio, LinkOption.NOFOLLOW_LINKS) then
            val _ = JFiles.createFile(nio)
          requireRegularFile(nio).map: _ =>
            tighten(nio, privateFile)
    .flatMap(_.liftTo[F])

  private[journal] def validatePrivateFile[F[_]: Async](path: Path): F[Unit] =
    Async[F].blocking:
      val nio = path.toNioPath
      if !JFiles.exists(nio, LinkOption.NOFOLLOW_LINKS) then
        Left(IllegalStateException(s"persistence file disappeared: $nio"))
      else if JFiles.isSymbolicLink(nio) then
        Left(IllegalStateException(s"persistence file must not be a symlink: $nio"))
      else
        requireRegularFile(nio).map: _ =>
          tighten(nio, privateFile)
    .flatMap(_.liftTo[F])

  private[journal] def identity[F[_]: Async](path: Path): F[Option[String]] =
    Async[F].blocking:
      val attributes =
        JFiles.readAttributes(
          path.toNioPath,
          classOf[java.nio.file.attribute.BasicFileAttributes],
          LinkOption.NOFOLLOW_LINKS
        )
      Option(attributes.fileKey()).map(_.toString)

  private[journal] def locked[F[_]: Async, A](
      path: Path,
      expectedIdentity: Option[String]
  )(use: FileChannel => Either[Throwable, A]): F[A] =
    validatePrivateFile(path) *> Async[F].blocking:
      val nio = path.toNioPath
      val processLock =
        processLocks.computeIfAbsent(
          nio.toAbsolutePath.normalize.toString,
          _ => ReentrantLock()
        )
      processLock.lock()
      try
        val actualIdentity =
          Option(
            JFiles
              .readAttributes(
                nio,
                classOf[java.nio.file.attribute.BasicFileAttributes],
                LinkOption.NOFOLLOW_LINKS
              )
              .fileKey()
          ).map(_.toString)
        if actualIdentity != expectedIdentity then
          Left(IllegalStateException(s"persistence file was replaced after opening: $nio"))
        else
          val channel = FileChannel.open(nio, StandardOpenOption.READ, StandardOpenOption.WRITE)
          val lock = channel.lock()
          try use(channel)
          finally
            lock.release()
            channel.close()
      finally processLock.unlock()
    .flatMap(_.liftTo[F])

  /** Locks two persistence files in canonical path order.
    *
    * Journal commits lock the journal and review writes lock the review log independently. Archive
    * capture needs one prefix of both, so it holds both of those same locks while copying. Sorting
    * is load-bearing: every multi-file caller acquires in one order and cannot deadlock another.
    */
  private[journal] def lockedPair[F[_]: Async, A](
      first: (Path, Option[String]),
      second: (Path, Option[String])
  )(use: (FileChannel, FileChannel) => Either[Throwable, A]): F[A] =
    val requested = List(first, second)
    val ordered = requested.sortBy((path, _) => path.toNioPath.toAbsolutePath.normalize.toString)

    (validatePrivateFile(first._1), validatePrivateFile(second._1)).tupled *>
      Async[F]
        .blocking:
          val processLockList = ordered.map: (path, _) =>
            processLocks.computeIfAbsent(
              path.toNioPath.toAbsolutePath.normalize.toString,
              _ => ReentrantLock()
            )
          processLockList.foreach(_.lock())

          var resources =
            List.empty[(FileChannel, Option[java.nio.channels.FileLock])]
          try
            val identityProblem = ordered.collectFirst:
              case (path, expected) if fileIdentity(path.toNioPath) != expected =>
                IllegalStateException(
                  s"persistence file was replaced after opening: ${path.toNioPath}"
                )

            identityProblem match
              case Some(problem) => Left(problem)
              case None =>
                val channels = ordered.map: (path, _) =>
                  val channel = FileChannel.open(
                    path.toNioPath,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
                  )
                  resources = (channel, None) :: resources
                  val fileLock = channel.lock()
                  resources = (channel, Some(fileLock)) :: resources.drop(1)
                  path -> channel
                val byPath = channels.toMap
                use(byPath(first._1), byPath(second._1))
          finally
            resources.foreach: (channel, fileLock) =>
              fileLock.foreach(_.release())
              channel.close()
            processLockList.reverse.foreach(_.unlock())
        .flatMap(_.liftTo[F])

  private[journal] def appendAndSync(channel: FileChannel, bytes: Array[Byte]): Unit =
    channel.position(channel.size())
    val buffer = ByteBuffer.wrap(bytes)
    while buffer.hasRemaining do
      val _ = channel.write(buffer)
    channel.force(forceMetadata)

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

  def append[F[_]: Async, A: Encoder](path: Path, values: List[A]): F[Unit] =
    if values.isEmpty then Async[F].unit
    else
      for
        _ <- ensurePrivateFile(path)
        id <- identity(path)
        _ <- locked(path, id): channel =>
          appendAndSync(channel, encode(values).toArray)
          Right(())
      yield ()

  /** Reads a whole file, or an empty list if it does not exist yet. */
  def read[F[_]: Async, A: Decoder](files: Files[F], path: Path): F[List[A]] =
    files.exists(path).flatMap:
      case false => List.empty[A].pure[F]
      case true  => validatePrivateFile(path) *> decodeLines[F, A](files, path).compile.toList

  private def requireRegularFile(path: JPath): Either[Throwable, Unit] =
    if !JFiles.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      Left(IllegalStateException(s"persistence path is not a regular file: $path"))
    else Right(())

  private def fileIdentity(path: JPath): Option[String] =
    Option(
      JFiles
        .readAttributes(
          path,
          classOf[java.nio.file.attribute.BasicFileAttributes],
          LinkOption.NOFOLLOW_LINKS
        )
        .fileKey()
    ).map(_.toString)

  private def tighten(
      path: JPath,
      permissions: java.util.Set[java.nio.file.attribute.PosixFilePermission]
  ): Unit =
    Option(
      JFiles.getFileAttributeView(
        path,
        classOf[PosixFileAttributeView],
        LinkOption.NOFOLLOW_LINKS
      )
    ).foreach(_.setPermissions(permissions))
