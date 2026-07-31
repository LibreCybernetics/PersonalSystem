package dev.librecybernetics.noesis.journal

import cats.effect.Async
import cats.syntax.all.*
import fs2.io.file.Path

/** A coordinated, byte-exact snapshot of the two durable workspace logs.
  *
  * The snapshot holds the journal and review-log locks together, so neither file can advance while
  * the pair is copied. Derived projections are intentionally absent: callers rebuild them from
  * `entries`, preserving the journal as the sole semantic source of truth (SPEC §3.2, §10).
  */
final case class JournalArchiveSnapshot(
    entries: List[JournalEntry],
    journalBytes: Array[Byte],
    reviewBytes: Array[Byte]
)

object JournalArchive:
  /** Captures a recoverable journal prefix and the matching review-log bytes. */
  def capture[F[_]: Async](journalPath: Path, reviewPath: Path): F[JournalArchiveSnapshot] =
    for
      _ <- JsonLines.ensurePrivateFile(journalPath)
      _ <- JsonLines.ensurePrivateFile(reviewPath)
      journalIdentity <- JsonLines.identity(journalPath)
      reviewIdentity <- JsonLines.identity(reviewPath)
      snapshot <- JsonLines.lockedPair(
        journalPath -> journalIdentity,
        reviewPath -> reviewIdentity
      ): (journal, reviews) =>
        for
          entries <- JournalFrames.readRecovering(journal)
          journalBytes <- JournalFrames.readAll(journal)
          reviewBytes <- JournalFrames.readAll(reviews)
        yield JournalArchiveSnapshot(entries, journalBytes, reviewBytes)
    yield snapshot

  /** Validates archived journal bytes without mutating them. */
  def validateJournal(bytes: Array[Byte]): Either[Throwable, List[JournalEntry]] =
    JournalFrames.validate(bytes)
