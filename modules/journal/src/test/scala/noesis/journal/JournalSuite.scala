package noesis.journal

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import noesis.logic.*
import noesis.journal.JournalFixtures.*

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

  test("a file-backed journal streams nothing if its file disappears after opening"):
    withTempJournal: path =>
      for
        journal <- JsonLinesJournal.open[IO](path)
        _ <- Files[IO].delete(path)
        entries <- journal.stream.compile.toList
      yield assertEquals(entries, Nil)

  test("the file holds exactly one JSON object per operation"):
    withTempJournal: path =>
      for
        journal <- JsonLinesJournal.open[IO](path)
        _ <- journal.append(
          NonEmptyList.of(Operation.Assert(ax(1).id, ax(1)), Operation.Assert(ax(2).id, ax(2)))
        )
        content <- Files[IO].readUtf8(path).compile.string
      yield
        val lines = content.linesIterator.filter(_.trim.nonEmpty).toList
        assertEquals(lines.length, 2)
        lines.foreach: line =>
          assert(decode[JournalEntry](line).isRight, s"line did not decode: $line")

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
        _ <- JsonLines.append[IO, String](Files[IO], path, Nil)
        absentAfterEmpty <- Files[IO].exists(path)
        missing <- JsonLines.read[IO, String](Files[IO], path)
        _ <- JsonLines.append[IO, String](Files[IO], path, List("first", "second"))
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
