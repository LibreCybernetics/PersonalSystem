package noesis.cli

import java.nio.file.Files as JFiles
import java.nio.file.attribute.PosixFilePermissions

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{Files, Flags}
import munit.CatsEffectSuite
import noesis.logic.{Axiom, Iri}

/** The archive is the owner's exit path, so its test crosses the real workspace and replay seams. */
class ArchiveSuite extends CatsEffectSuite:

  test("an archive verifies, restores into a fresh workspace, and detects tampering"):
    Files[IO].tempDirectory.use: parent =>
      val source = parent / "source"
      val archive = parent / "archive"
      val restored = parent / "restored"
      val fact = Axiom.ClassAssertion(Iri("noesis:e/alice"), Iri("noesis:core/Person"))
      for
        workspace <- Workspace.open(source)
        committed <- workspace.kb.assert(fact)
        _ = assert(committed.isRight, committed)
        created <- Archive.create(source, archive)
        verified <- Archive.verify(archive)
        permissions <- IO.blocking:
          val paths = List(
            archive,
            archive / "manifest.json",
            archive / "journal.jsonl",
            archive / "reviews.jsonl",
            archive / "current.ttl"
          )
          paths.map(path =>
            PosixFilePermissions.toString(JFiles.getPosixFilePermissions(path.toNioPath))
          )
        restoredReport <- Archive.restore(archive, restored)
        reopened <- Workspace.open(restored)
        state <- reopened.kb.state
        overwrite <- Archive.restore(archive, restored).attempt
        _ <- Files[IO]
          .writeUtf8(archive / "current.ttl", Flags.Append)(Stream.emit("# tampered\n"))
          .compile
          .drain
        tampered <- Archive.verify(archive).attempt
      yield
        assertEquals(created.lastJournalSequence, 1L)
        assertEquals(verified, created)
        assertEquals(permissions, "rwx------" :: List.fill(4)("rw-------"))
        assertEquals(restoredReport, created)
        assert(state.axiom(fact.id).nonEmpty)
        assert(overwrite.isLeft)
        assert(tampered.isLeft)
