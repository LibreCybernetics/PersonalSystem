package dev.librecybernetics.noesis.journal

import java.nio.file.Files as JFiles
import java.nio.file.attribute.PosixFilePermissions

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.journal.JournalFixtures.*

/** The journal is the truth (SPEC §4), so these tests hold it to that: append-only, exactly
  * ordered, byte-level round-trippable, and readable back into an identical state.
  */
class JournalSuite extends CatsEffectSuite:

  test("appended operations get contiguous sequence numbers starting at 1"):
    for
      journal <- InMemoryJournal.create[IO]
      first <- journal.append(NonEmptyList.of(Operation.Assert(ax(1).id, ax(1))))
      second <- journal.append(
        NonEmptyList.of(Operation.Assert(ax(2).id, ax(2)), Operation.Assert(ax(3).id, ax(3)))
      )
      entries <- journal.stream.compile.toList
    yield
      assertEquals(first.entries.map(_.seq), List(1L))
      assertEquals(second.entries.map(_.seq), List(2L, 3L))
      assertEquals(entries.map(_.seq), List(1L, 2L, 3L))

  test("a commit bundle is journaled in the order given"):
    val ops = NonEmptyList.of(
      Operation.Assert(ax(1).id, ax(1)),
      Operation.OpenFluent(Fluent(FluentId.unsafe("fl_x"), alice, worksAt, Node.Ref(acme))),
      Operation.Retract(ax(1).id, Some("mistake"))
    )
    for
      journal <- InMemoryJournal.create[IO]
      _ <- journal.append(ops)
      entries <- journal.stream.compile.toList
    yield assertEquals(entries.map(_.operation), ops.toList)

  test("lastSeq survives reopening a file-backed journal"):
    withTempJournal: path =>
      for
        first <- JsonLinesJournal.open[IO](path)
        _ <- first.append(NonEmptyList.of(Operation.Assert(ax(1).id, ax(1))))
        _ <- first.append(NonEmptyList.of(Operation.Assert(ax(2).id, ax(2))))
        reopened <- JsonLinesJournal.open[IO](path)
        last <- reopened.lastSeq
        third <- reopened.append(NonEmptyList.of(Operation.Assert(ax(3).id, ax(3))))
        all <- reopened.stream.compile.toList
      yield
        assertEquals(last, 2L)
        assertEquals(third.entries.map(_.seq), List(3L))
        assertEquals(all.map(_.seq), List(1L, 2L, 3L))

  test("a file-backed journal fails closed if its file disappears after opening"):
    withTempJournal: path =>
      for
        journal <- JsonLinesJournal.open[IO](path)
        _ <- Files[IO].delete(path)
        result <- journal.stream.compile.toList.attempt
      yield
        assert(
          result.left.exists(_.getMessage.contains("disappeared")),
          s"expected a disappeared-file failure, got $result"
        )

  test("the file holds one checksummed JSON object per atomic commit"):
    withTempJournal: path =>
      for
        journal <- JsonLinesJournal.open[IO](path)
        _ <- journal.append(
          NonEmptyList.of(Operation.Assert(ax(1).id, ax(1)), Operation.Assert(ax(2).id, ax(2)))
        )
        content <- Files[IO].readUtf8(path).compile.string
      yield
        val lines = content.linesIterator.filter(_.trim.nonEmpty).toList
        assertEquals(lines.length, 1)
        val line = lines.headOption.getOrElse(fail("commit frame missing"))
        val json = io.circe.parser.parse(line).fold(error => fail(error.getMessage), identity)
        assertEquals(json.hcursor.get[Int]("formatVersion"), Right(1))
        assertEquals(json.hcursor.downField("entries").values.map(_.size), Some(2))
        assert(json.hcursor.get[String]("checksum").exists(_.length == 64))

  test("a line that is not a commit frame is corruption, not a shorter commit"):
    withTempJournal: path =>
      val entry =
        JournalEntry(1L, java.time.Instant.parse("2026-07-29T12:00:00Z"), Operation.Assert(ax(1).id, ax(1)))
      for
        _ <- Files[IO].createFile(path)
        _ <- JsonLines.write(Files[IO], path, List(entry))
        failure <- JsonLinesJournal.open[IO](path).attempt
      yield assert(
        failure.swap.exists(_.getMessage.contains("no formatVersion")),
        s"a bare entry opened as a journal: $failure"
      )

  test("an incomplete final frame is discarded while the complete prefix survives"):
    withTempJournal: path =>
      for
        journal <- JsonLinesJournal.open[IO](path)
        first <- journal.append(NonEmptyList.one(Operation.Assert(ax(1).id, ax(1))))
        second <- journal.append(NonEmptyList.one(Operation.Assert(ax(2).id, ax(2))))
        _ <- Files[IO]
          .writeUtf8(path, fs2.io.file.Flags.Append)(fs2.Stream.emit("""{"formatVersion":1"""))
          .compile
          .drain
        reopened <- JsonLinesJournal.open[IO](path)
        entries <- reopened.stream.compile.toList
        content <- Files[IO].readUtf8(path).compile.string
      yield
        assertEquals(entries, first.entries ++ second.entries)
        assert(content.endsWith("\n"))
        assert(!content.contains("""{"formatVersion":1{"formatVersion":1"""))

  test("conditional append rejects a stale validated prefix"):
    withTempJournal: path =>
      for
        first <- JsonLinesJournal.open[IO](path)
        second <- JsonLinesJournal.open[IO](path)
        accepted <- first.appendIfCurrent(0L, NonEmptyList.one(Operation.Assert(ax(1).id, ax(1))))
        stale <- second.appendIfCurrent(0L, NonEmptyList.one(Operation.Assert(ax(2).id, ax(2))))
        entries <- second.stream.compile.toList
      yield
        assert(accepted.nonEmpty)
        assertEquals(stale, None)
        assertEquals(entries.map(_.operation), List(Operation.Assert(ax(1).id, ax(1))))

  test("the in-memory journal enforces the same conditional-append prefix"):
    for
      journal <- InMemoryJournal.create[IO]
      accepted <- journal.appendIfCurrent(
        0L,
        NonEmptyList.one(Operation.Assert(ax(1).id, ax(1)))
      )
      stale <- journal.appendIfCurrent(
        0L,
        NonEmptyList.one(Operation.Assert(ax(2).id, ax(2)))
      )
      entries <- journal.stream.compile.toList
    yield
      assert(accepted.nonEmpty)
      assertEquals(stale, None)
      assertEquals(entries.map(_.operation), List(Operation.Assert(ax(1).id, ax(1))))

  test("separate file-backed handles serialize concurrent commit frames"):
    withTempJournal: path =>
      for
        first <- JsonLinesJournal.open[IO](path)
        second <- JsonLinesJournal.open[IO](path)
        _ <- (1 to 30).toList.parTraverse_ { i =>
          val selected = if i % 2 == 0 then first else second
          selected.append(
            NonEmptyList.of(Operation.Assert(ax(i).id, ax(i)), Operation.Retract(ax(i).id))
          )
        }
        entries <- first.stream.compile.toList
      yield
        assertEquals(entries.map(_.seq), (1L to 60L).toList)
        entries.grouped(2).foreach:
          case List(
                JournalEntry(_, _, Operation.Assert(asserted, _, _)),
                JournalEntry(_, _, Operation.Retract(retracted, _))
              ) =>
            assertEquals(asserted, retracted)
          case other => fail(s"commit frame was split: $other")

  test("archive capture returns one locked, replayable pair of durable logs"):
    withTempJournal: path =>
      val reviews = path.parent.getOrElse(fail("temporary journal has no parent")) / "reviews.jsonl"
      for
        journal <- JsonLinesJournal.open[IO](path)
        commit <- journal.append(NonEmptyList.one(Operation.Assert(ax(1).id, ax(1))))
        _ <- JsonLines.append[IO, String](reviews, List("remembered"))
        snapshot <- JournalArchive.capture[IO](path, reviews)
        _ <- journal.append(NonEmptyList.one(Operation.Assert(ax(2).id, ax(2))))
      yield
        assertEquals(snapshot.entries, commit.entries)
        assertEquals(JournalArchive.validateJournal(snapshot.journalBytes), Right(commit.entries))
        assertEquals(new String(snapshot.reviewBytes, java.nio.charset.StandardCharsets.UTF_8), "\"remembered\"\n")

  test("opening a workspace tightens directory, journal, and review permissions"):
    withTempJournal: path =>
      val root = path.parent.getOrElse(fail("temporary journal has no parent"))
      val reviews = root / "reviews.jsonl"
      for
        _ <- IO.blocking:
          JFiles.setPosixFilePermissions(
            root.toNioPath,
            PosixFilePermissions.fromString("rwxrwxrwx")
          )
        _ <- JsonLinesJournal.open[IO](path)
        _ <- JsonLines.append[IO, String](reviews, List("review"))
        permissions <- IO.blocking:
          (
            PosixFilePermissions.toString(JFiles.getPosixFilePermissions(root.toNioPath)),
            PosixFilePermissions.toString(JFiles.getPosixFilePermissions(path.toNioPath)),
            PosixFilePermissions.toString(JFiles.getPosixFilePermissions(reviews.toNioPath))
          )
      yield assertEquals(permissions, ("rwx------", "rw-------", "rw-------"))

  test("a checksum mismatch in a complete frame is fatal"):
    withTempJournal: path =>
      for
        journal <- JsonLinesJournal.open[IO](path)
        _ <- journal.append(NonEmptyList.one(Operation.Assert(ax(1).id, ax(1))))
        original <- Files[IO].readUtf8(path).compile.string
        json = io.circe.parser.parse(original).fold(error => fail(error.getMessage), identity)
        checksum = json.hcursor.get[String]("checksum").fold(error => fail(error.getMessage), identity)
        replacement = (if checksum.startsWith("0") then "1" else "0") + checksum.drop(1)
        tampered = original.replace(checksum, replacement)
        _ <- Files[IO].writeUtf8(path)(fs2.Stream.emit(tampered)).compile.drain
        result <- JsonLinesJournal.open[IO](path).attempt
      yield assert(
        result.left.exists(_.getMessage == "corrupt journal at line 1: commit frame checksum mismatch")
        ,
        s"expected checksum corruption, got $result"
      )

  test("unsupported and empty commit frames fail with their exact format errors"):
    withTempJournal: path =>
      val unsupported = """{"checksum":"","entries":[],"formatVersion":2}""" + "\n"
      val empty =
        """{"checksum":"0000000000000000000000000000000000000000000000000000000000000000","entries":[],"formatVersion":1}""" + "\n"
      for
        _ <- Files[IO].writeUtf8(path)(fs2.Stream.emit(unsupported)).compile.drain
        unsupportedResult <- JsonLinesJournal.open[IO](path).attempt
        _ <- Files[IO].writeUtf8(path)(fs2.Stream.emit(empty)).compile.drain
        emptyResult <- JsonLinesJournal.open[IO](path).attempt
      yield
        assertEquals(
          unsupportedResult.left.map(_.getMessage),
          Left("corrupt journal at line 1: unsupported journal frame version: 2")
        )
        assertEquals(
          emptyResult.left.map(_.getMessage),
          Left("corrupt journal at line 1: a commit frame must contain at least one operation")
        )

  test("sequence gaps and forged assertion identifiers fail before replay"):
    withTempJournal: path =>
      val at = java.time.Instant.parse("2026-07-29T12:00:00Z")
      val gap = JournalEntry(2L, at, Operation.Assert(ax(1).id, ax(1)))
      val forged = JournalEntry(
        1L,
        at,
        Operation.Assert(AxiomId.unsafe("ax_forged"), ax(1))
      )
      // Well-formed frames carrying bad entries: the checksum and version are correct, so these
      // reach the replay checks rather than being turned away as malformed input.
      for
        _ <- Files[IO].createFile(path)
        _ <- JsonLines.write(Files[IO], path, List(JournalFrame.create(List(gap))))
        gapResult <- JsonLinesJournal.open[IO](path).attempt
        _ <- fs2.Stream
          .chunk(JsonLines.encode(List(JournalFrame.create(List(forged)))))
          .through(Files[IO].writeAll(path))
          .compile
          .drain
        forgedResult <- JsonLinesJournal.open[IO](path).attempt
      yield
        assertEquals(
          gapResult.left.map(_.getMessage),
          Left("corrupt journal at line 1: expected sequence 1 but found 2")
        )
        assertEquals(
          forgedResult.left.map(_.getMessage),
          Left(
            s"corrupt journal at line 1: assertion id ax_forged does not match content id ${ax(1).id.value}"
          )
        )

  test("immutable archived journal bytes require an LF-terminated final record"):
    val bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(
      JournalArchive.validateJournal(bytes).left.map(_.getMessage),
      Left("corrupt journal at line 1: final record is not LF-terminated")
    )

  test("persistence paths reject missing parents, directories, and symlinks"):
    Files[IO].tempDirectory.use: dir =>
      val directoryPath = dir / "as-directory"
      val realDirectory = dir / "real"
      val linkedDirectory = dir / "linked"
      val targetFile = dir / "target.jsonl"
      val linkedFile = dir / "linked.jsonl"
      for
        noParent <- JsonLines.ensurePrivateFile[IO](Path("")).attempt
        _ <- Files[IO].createDirectory(directoryPath)
        directoryResult <- JsonLinesJournal.open[IO](directoryPath).attempt
        _ <- Files[IO].createDirectory(realDirectory)
        _ <- IO.blocking:
          val _ = JFiles.createSymbolicLink(linkedDirectory.toNioPath, realDirectory.toNioPath)
        linkedParentResult <- JsonLines.append[IO, String](
          linkedDirectory / "reviews.jsonl",
          List("review")
        ).attempt
        _ <- Files[IO].createFile(targetFile)
        _ <- IO.blocking:
          val _ = JFiles.createSymbolicLink(linkedFile.toNioPath, targetFile.toNioPath)
        linkedFileResult <- JsonLinesJournal.open[IO](linkedFile).attempt
      yield
        assert(noParent.left.exists(_.getMessage.contains("must have a parent directory")))
        assert(directoryResult.left.exists(_.getMessage.contains("is not a regular file")))
        assert(linkedParentResult.left.exists(_.getMessage.contains("directory must not be a symlink")))
        assert(linkedFileResult.left.exists(_.getMessage.contains("file must not be a symlink")))

  test("an opened handle rejects replacement by a file or symlink"):
    withTempJournal: path =>
      val parent = path.parent.getOrElse(fail("journal has no parent"))
      val replacementFile = parent / "fresh-journal"
      val symlinkTarget = parent / "symlink-target"
      for
        journal <- JsonLinesJournal.open[IO](path)
        _ <- Files[IO].createFile(replacementFile)
        _ <- IO.blocking:
          val _ = JFiles.move(
            replacementFile.toNioPath,
            path.toNioPath,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
          )
        replaced <- journal.stream.compile.toList.attempt
        reopened <- JsonLinesJournal.open[IO](path)
        _ <- Files[IO].createFile(symlinkTarget)
        _ <- Files[IO].delete(path)
        _ <- IO.blocking:
          val _ = JFiles.createSymbolicLink(path.toNioPath, symlinkTarget.toNioPath)
        symlinked <- reopened.stream.compile.toList.attempt
      yield
        assert(replaced.left.exists(_.getMessage.contains("was replaced after opening")))
        assert(symlinked.left.exists(_.getMessage.contains("file must not be a symlink")))

  test("paired locking validates both expected file identities"):
    Files[IO].tempDirectory.use: dir =>
      val first = dir / "first"
      val second = dir / "second"
      for
        _ <- JsonLines.ensurePrivateFile[IO](first)
        _ <- JsonLines.ensurePrivateFile[IO](second)
        result <- JsonLines
          .lockedPair[IO, Unit](first -> Some("wrong"), second -> Some("wrong"))((_, _) => Right(()))
          .attempt
      yield assert(result.left.exists(_.getMessage.contains("was replaced after opening")))

  test("a corrupt line fails loudly rather than being skipped"):
    withTempJournal: path =>
      for
        journal <- JsonLinesJournal.open[IO](path)
        _ <- journal.append(NonEmptyList.of(Operation.Assert(ax(1).id, ax(1))))
        _ <- Files[IO]
          .writeUtf8(path, fs2.io.file.Flags.Append)(fs2.Stream.emit("{not json}\n"))
          .compile
          .drain
        result <- journal.stream.compile.toList.attempt
      yield
        val isCorrupt = result.left.exists:
          case _: CorruptJournal => true
          case _                 => false
        assert(isCorrupt, s"expected CorruptJournal, got $result")

  test("every operation round-trips through JSON"):
    val operations = List(
      Operation.Assert(ax(1).id, ax(1), AxiomAnnotations.ownerConfirmed),
      Operation.Assert(
        ax(2).id,
        ax(2),
        AxiomAnnotations(
          truthConfidence = Some(0.6),
          sensitivity = Some(Sensitivity.Internal),
          knowledgeScope = Set(orgAcme),
          recallUtility = Some(0.9),
          provenance = Provenance(captureSession = Some("s1"), proposedBy = Some("agent:tutor"))
        )
      ),
      Operation.Retract(ax(1).id, Some("superseded")),
      Operation.Annotate(ax(1).id, AnnotationPatch(recallUtility = Patch.of(0.3))),
      Operation.Annotate(ax(1).id, AnnotationPatch(sensitivity = Patch.Clear)),
      Operation.Reclassify(ax(1).id, Sensitivity.Sensitive, Set(orgAcme)),
      Operation.Dispute(ax(1).id, Some("conflicts with payslip")),
      Operation.Undispute(ax(1).id),
      Operation.OpenFluent(
        Fluent(
          FluentId.unsafe("fl_1"),
          alice,
          worksAt,
          Node.Ref(acme),
          validFrom = Some(PartialDate.of(2026, 1, 1))
        )
      ),
      Operation.CloseFluent(
        FluentId.unsafe("fl_1"),
        Some(PartialDate.of(2026, 7, 1)),
        EndReason.Superseded
      ),
      Operation.SupersedeFluent(
        FluentId.unsafe("fl_1"),
        Fluent(FluentId.unsafe("fl_2"), alice, worksAt, Node.Ref(molina)),
        Some(PartialDate.of(2026, 7, 1))
      )
    )

    operations.foreach: op =>
      val entry = JournalEntry(1L, java.time.Instant.parse("2026-07-29T12:00:00Z"), op)
      val json = entry.asJson.deepDropNullValues.noSpaces
      assertEquals(decode[JournalEntry](json), Right(entry), s"round-trip failed for: $json")

  test("clearing an annotation override is distinguishable from leaving it alone"):
    val clear = AnnotationPatch(sensitivity = Patch.Clear)
    val leave = AnnotationPatch(sensitivity = Patch.Leave)
    val start = AxiomAnnotations(sensitivity = Some(Sensitivity.Sensitive))

    assertEquals(clear.applyTo(start).sensitivity, None)
    assertEquals(leave.applyTo(start).sensitivity, Some(Sensitivity.Sensitive))
    // and both survive serialization, which is what makes the distinction replayable
    assertEquals(decode[AnnotationPatch](clear.asJson.noSpaces), Right(clear))

  test("concurrent appends neither interleave nor lose entries"):
    for
      journal <- InMemoryJournal.create[IO]
      _ <- (1 to 50).toList.parTraverse_ { i =>
        journal.append(NonEmptyList.of(Operation.Assert(ax(i).id, ax(i)), Operation.Retract(ax(i).id)))
      }
      entries <- journal.stream.compile.toList
    yield
      assertEquals(entries.length, 100)
      assertEquals(entries.map(_.seq), (1L to 100L).toList)
      // each pair landed together: an Assert is always immediately followed by its own Retract
      val pairs = entries.grouped(2).toList
      pairs.foreach:
        case List(JournalEntry(_, _, Operation.Assert(a, _, _)), JournalEntry(_, _, Operation.Retract(b, _))) =>
          assertEquals(a, b, "an atomic bundle was split by a concurrent append")
        case other => fail(s"unexpected pairing: $other")

  test("the shared JSON Lines helper distinguishes empty, missing, and populated files"):
    Files[IO].tempDirectory.use: dir =>
      val path = dir / "reviews.jsonl"
      for
        _ <- JsonLines.append[IO, String](path, Nil)
        absentAfterEmpty <- Files[IO].exists(path)
        missing <- JsonLines.read[IO, String](Files[IO], path)
        _ <- JsonLines.append[IO, String](path, List("first", "second"))
        content <- Files[IO].readUtf8(path).compile.string
        values <- JsonLines.read[IO, String](Files[IO], path)
      yield
        assert(!absentAfterEmpty, "an empty append must not create a file")
        assertEquals(missing, Nil)
        assertEquals(content, "\"first\"\n\"second\"\n")
        assertEquals(values, List("first", "second"))

  test("the shared JSON Lines helper ignores blanks but reports the physical corrupt line"):
    Files[IO].tempDirectory.use: dir =>
      val path = dir / "reviews.jsonl"
      val content = "\n\"valid\"\n\n{broken}\n"
      for
        _ <- Files[IO].writeUtf8(path)(fs2.Stream.emit(content)).compile.drain
        result <- JsonLines.read[IO, String](Files[IO], path).attempt
      yield result match
        case Left(CorruptJournal(line, detail)) =>
          assertEquals(line, 4L)
          assert(detail.nonEmpty)
        case other => fail(s"expected corruption on physical line four, got $other")

  private def ax(i: Int): Axiom = Axiom.ClassAssertion(Iri(s"noesis:e/p$i"), Person)

  private def withTempJournal[A](use: Path => IO[A]): IO[A] =
    Files[IO].tempDirectory.use(dir => use(dir / "journal.jsonl"))
