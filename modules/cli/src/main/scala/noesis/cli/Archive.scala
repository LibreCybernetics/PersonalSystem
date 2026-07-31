package noesis.cli

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{
  Files as JFiles,
  LinkOption,
  Path as JPath,
  StandardOpenOption
}
import java.security.MessageDigest
import java.util.HexFormat

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.derivation.ConfiguredCodec
import io.circe.parser.decode
import io.circe.syntax.*
import noesis.core.projection.KbState
import noesis.journal.JournalArchive
import noesis.lms.Review
import noesis.logic.Canonical
import noesis.logic.given

/** Portable archive metadata. Checksums cover every payload; the manifest is canonical JSON. */
private final case class ArchiveFile(path: String, bytes: Long, sha256: String)
    derives ConfiguredCodec

private final case class ArchiveManifest(
    formatVersion: Int,
    journalFormat: String,
    reviewFormat: String,
    generatedItemIdFormat: String,
    lastJournalSequence: Long,
    files: List[ArchiveFile]
) derives ConfiguredCodec

final case class ArchiveReport(lastJournalSequence: Long, reviews: Int)

/** Creates, verifies and restores the transparent archive directory (DESIGN portability).
  *
  * Restore only targets a path that does not exist. This makes the operation recoverable and avoids
  * giving a backup command implicit authority to overwrite live personal data.
  */
object Archive:
  private val currentVersion = 1
  private val journalFormat = "noesis-jsonl-commit-frame-v1"
  private val reviewFormat = "noesis-jsonl-review-v1"
  private val itemIdFormat = "sha256-v2"
  private val manifestName = "manifest.json"
  private val journalName = "journal.jsonl"
  private val reviewsName = "reviews.jsonl"
  private val projectionName = "current.ttl"
  private val payloadNames = Set(journalName, reviewsName, projectionName)
  private val noFollow = Array(LinkOption.NOFOLLOW_LINKS)
  private val privateDirectory = PosixFilePermissions.fromString("rwx------")
  private val privateFile = PosixFilePermissions.fromString("rw-------")

  def create(root: Path, target: Path): IO[ArchiveReport] =
    for
      snapshot <- JournalArchive.capture[IO](
        Workspace.journalPath(root),
        Workspace.reviewsPath(root)
      )
      _ <- decodeReviews(snapshot.reviewBytes).liftTo[IO]
      state = KbState.replay(snapshot.entries)
      projection = Render.turtle(state).getBytes(StandardCharsets.UTF_8)
      payloads = List(
        journalName -> snapshot.journalBytes,
        reviewsName -> snapshot.reviewBytes,
        projectionName -> projection
      )
      manifest = ArchiveManifest(
        currentVersion,
        journalFormat,
        reviewFormat,
        itemIdFormat,
        state.seq,
        payloads.map: (name, bytes) =>
          ArchiveFile(name, bytes.length.toLong, sha256(bytes))
      )
      _ <- createPrivateDirectory(target)
      _ <- payloads.traverse_(entry =>
        val (name, bytes) = entry
        writePrivate(target / name, bytes)
      )
      manifestBytes =
        s"${Canonical.noesis(manifest.asJson)}\n".getBytes(StandardCharsets.UTF_8)
      _ <- writePrivate(target / manifestName, manifestBytes)
      verified <- checked(target)
    yield ArchiveReport(verified.manifest.lastJournalSequence, verified.reviews.length)

  def verify(source: Path): IO[ArchiveReport] =
    checked(source).map(archive =>
      ArchiveReport(archive.manifest.lastJournalSequence, archive.reviews.length)
    )

  def restore(source: Path, target: Path): IO[ArchiveReport] =
    for
      archive <- checked(source)
      _ <- createPrivateDirectory(target)
      _ <- writePrivate(target / journalName, archive.journalBytes)
      _ <- writePrivate(target / reviewsName, archive.reviewBytes)
      // Reopen through the production path: this validates journal identity/frames, review codecs,
      // replay, learning restoration and the permissions of the restored workspace.
      restored <- Workspace.open(target)
      restoredSeq <- restored.kb.journal.lastSeq
      _ <-
        Either
          .cond(
            restoredSeq == archive.manifest.lastJournalSequence,
            (),
            IllegalStateException(
              s"restored journal ends at $restoredSeq, expected ${archive.manifest.lastJournalSequence}"
            )
          )
          .liftTo[IO]
    yield ArchiveReport(restoredSeq, archive.reviews.length)

  private final case class CheckedArchive(
      manifest: ArchiveManifest,
      journalBytes: Array[Byte],
      reviewBytes: Array[Byte],
      reviews: List[Review]
  )

  private def checked(source: Path): IO[CheckedArchive] =
    for
      manifestBytes <- readRegular(source / manifestName)
      manifest <-
        decode[ArchiveManifest](new String(manifestBytes, StandardCharsets.UTF_8))
          .leftMap(error => IllegalStateException(s"invalid archive manifest: ${error.getMessage}"))
          .liftTo[IO]
      _ <- validateManifest(manifest).liftTo[IO]
      payloads <- manifest.files.traverse: file =>
        readRegular(source / file.path).flatMap: bytes =>
          Either
            .cond(
              bytes.length.toLong == file.bytes && sha256(bytes) == file.sha256,
              file.path -> bytes,
              IllegalStateException(s"archive checksum or length mismatch: ${file.path}")
            )
            .liftTo[IO]
      byName = payloads.toMap
      journalBytes = byName(journalName)
      reviewBytes = byName(reviewsName)
      entries <- JournalArchive.validateJournal(journalBytes).liftTo[IO]
      reviews <- decodeReviews(reviewBytes).liftTo[IO]
      state = KbState.replay(entries)
      _ <-
        Either
          .cond(
            state.seq == manifest.lastJournalSequence,
            (),
            IllegalStateException(
              s"manifest sequence ${manifest.lastJournalSequence} does not match journal ${state.seq}"
            )
          )
          .liftTo[IO]
      expectedProjection = Render.turtle(state).getBytes(StandardCharsets.UTF_8)
      _ <-
        Either
          .cond(
            java.util.Arrays.equals(expectedProjection, byName(projectionName)),
            (),
            IllegalStateException("current.ttl does not match the archived journal projection")
          )
          .liftTo[IO]
    yield CheckedArchive(manifest, journalBytes, reviewBytes, reviews)

  private def validateManifest(manifest: ArchiveManifest): Either[Throwable, Unit] =
    val names = manifest.files.map(_.path)
    val problems = List(
      Option.when(manifest.formatVersion != currentVersion)(
        s"unsupported archive format version: ${manifest.formatVersion}"
      ),
      Option.when(manifest.journalFormat != journalFormat)(
        s"unsupported journal format: ${manifest.journalFormat}"
      ),
      Option.when(manifest.reviewFormat != reviewFormat)(
        s"unsupported review format: ${manifest.reviewFormat}"
      ),
      Option.when(manifest.generatedItemIdFormat != itemIdFormat)(
        s"unsupported generated item identifier format: ${manifest.generatedItemIdFormat}"
      ),
      Option.when(names.toSet != payloadNames || names.distinct.length != names.length)(
        "manifest must contain journal.jsonl, reviews.jsonl and current.ttl exactly once"
      ),
      Option.when(manifest.files.exists(file => file.bytes < 0L || file.sha256.length != 64))(
        "manifest contains invalid file metadata"
      )
    ).flatten
    problems.headOption match
      case None => Right(())
      case Some(problem) =>
        Left(IllegalStateException(s"invalid archive manifest: $problem"))

  private def decodeReviews(bytes: Array[Byte]): Either[Throwable, List[Review]] =
    if bytes.nonEmpty && bytes.last != '\n'.toByte then
      Left(IllegalStateException("review log is not LF-terminated"))
    else
      new String(bytes, StandardCharsets.UTF_8)
        .split("\n", -1)
        .toList
        .filter(_.trim.nonEmpty)
        .zipWithIndex
        .traverse: (line, index) =>
          decode[Review](line).leftMap(error =>
            IllegalStateException(s"invalid review at line ${index + 1}: ${error.getMessage}")
          )

  private def createPrivateDirectory(path: Path): IO[Unit] =
    IO.blocking:
      val nio = path.toNioPath
      if JFiles.exists(nio, noFollow*) then
        Left(IllegalStateException(s"target already exists: $nio"))
      else if Option(nio.getParent).exists(JFiles.isSymbolicLink(_)) then
        Left(IllegalStateException(s"target parent must not be a symlink: ${nio.getParent}"))
      else
        val _ = JFiles.createDirectory(nio)
        tighten(nio, privateDirectory)
        Right(())
    .flatMap(_.liftTo[IO])

  private def writePrivate(path: Path, bytes: Array[Byte]): IO[Unit] =
    IO.blocking:
      val nio = path.toNioPath
      if JFiles.isSymbolicLink(nio) then
        Left(IllegalStateException(s"archive payload must not be a symlink: $nio"))
      else
        val _ = JFiles.write(nio, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        tighten(nio, privateFile)
        Right(())
    .flatMap(_.liftTo[IO])

  private def readRegular(path: Path): IO[Array[Byte]] =
    IO.blocking:
      val nio = path.toNioPath
      if JFiles.isSymbolicLink(nio) || !JFiles.isRegularFile(nio, noFollow*) then
        Left(IllegalStateException(s"archive payload is not a regular file: $nio"))
      else Right(JFiles.readAllBytes(nio))
    .flatMap(_.liftTo[IO])

  private def sha256(bytes: Array[Byte]): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def tighten(
      path: JPath,
      permissions: java.util.Set[java.nio.file.attribute.PosixFilePermission]
  ): Unit =
    if JFiles.getFileStore(path).supportsFileAttributeView("posix") then
      val _ = JFiles.setPosixFilePermissions(path, permissions)
