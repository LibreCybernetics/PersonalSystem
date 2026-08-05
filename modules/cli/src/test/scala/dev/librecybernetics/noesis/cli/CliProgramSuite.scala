package dev.librecybernetics.noesis.cli

import java.time.{LocalDate, ZoneId}

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Files
import munit.CatsEffectSuite

import dev.librecybernetics.noesis.logic.{Literal, PartialDate, Sensitivity}
import dev.librecybernetics.noesis.lms.QueueMode
import dev.librecybernetics.noesis.vocab.*

/** Typed-command integration at the imperative shell, backed by disposable real workspaces. */
class CliProgramSuite extends CatsEffectSuite:
  private val utc = ZoneId.of("UTC")
  private val day = LocalDate.of(2026, 8, 5)

  test("read, query, learning, disclosure, and note commands share one durable workspace"):
    Files[IO].tempDirectory.use: parent =>
      val root = parent / "workspace"
      val commands = List(
        Command.Init,
        Command.Assert("marco", "rdf:type", "crm:Person", None, Nil, None, None, true),
        Command.Assert("marco", "rdfs:label", "Marco", Some(Sensitivity.Public), Nil, Some(0.8), Some(0.9), true),
        Command.Show("marco"),
        Command.QueryCmd("?s rdf:type crm:Person"),
        Command.QueryCmd("not a pattern"),
        Command.Entails("marco", "rdf:type", "crm:Person"),
        Command.Entails("marco", "rdf:type", "crm:Organization"),
        Command.Explain("marco", "rdf:type", "crm:Person"),
        Command.Explain("nobody", "rdf:type", "crm:Person"),
        Command.Check,
        Command.Journal(Some(3)),
        Command.Queue(QueueMode.Mixed, 2),
        Command.Answer("missing", -1.0, 0L),
        Command.Answer("missing", 0.5, -1L),
        Command.Answer("missing", 0.5, 10L),
        Command.Items,
        Command.Disclose("assistant", Sensitivity.Public, Nil),
        Command.Loans,
        Command.Export,
        Command.AsOf(day),
        Command.Agenda(day),
        Command.VocabSearch("birthday"),
        Command.VocabSearch("no-such-term"),
        Command.VocabShow("crm:birthday"),
        Command.VocabShow("no-such-term"),
        Command.Quiz(QueueMode.Mixed, 1),
        Command.Note(NoteCommand.Today),
        Command.Note(NoteCommand.New("Design notes", false)),
        Command.Note(NoteCommand.Append("Met [[Marco]].", Some(day), None)),
        Command.Note(NoteCommand.Show(None, None)),
        Command.Note(NoteCommand.ListNotes),
        Command.Note(NoteCommand.History("missing-block")),
        Command.Backlinks("marco"),
        Command.Search("Marco")
      )
      commands.traverse(Main.run(root, utc, _)).map: codes =>
        assertEquals(codes.headOption, Some(ExitCode.Success))
        assertEquals(codes.length, commands.length)
        assert(codes.contains(ExitCode.Error))
        assertEquals(codes.lastOption, Some(ExitCode.Success))

  test("structured contact commands and archive dispatch run through the typed shell"):
    Files[IO].tempDirectory.use: parent =>
      val root = parent / "workspace"
      val archive = parent / "archive"
      val restored = parent / "restored"
      val importFile = parent / "contact.vcf"
      val vcard = "BEGIN:VCARD\nVERSION:4.0\nFN:Lía\nEMAIL:lia@example.test\nEND:VCARD\n"
      val preference = NonBlank.parse("preference", "coffee").fold(message => fail(message), identity)
      val gift = NonBlank.parse("gift", "book").fold(message => fail(message), identity)
      val days = PositiveDays.parse("30").fold(message => fail(message), identity)
      val commands = List(
        Command.Init,
        Command.Contact(ContactCommand.Add("Marco", Some("marco"), false)),
        Command.Contact(ContactCommand.Add("Acme", Some("acme"), true)),
        Command.Contact(ContactCommand.MethodAdd("marco", "m@example.test", ContactKind.Email, None, Some("work"), None, Some(1))),
        Command.Contact(ContactCommand.AddressAdd("marco", "1 Main St", None, None, None, Some("CDMX"), None, None, Some("MX"), None, None)),
        Command.Contact(ContactCommand.EmploymentAdd("marco", "acme", None, Some("Engineer"), None, None)),
        Command.Contact(ContactCommand.InteractionAdd("marco", List("acme"), PartialDate.of(2026, 8, 1), "email", None, Some("Hello"))),
        Command.Contact(ContactCommand.RelationshipAdd("marco", "acme", Nil, "professional", None, None, None)),
        Command.Contact(ContactCommand.NoteAdd("marco", "Prefers email", "general", None, Sensitivity.Personal)),
        Command.Contact(ContactCommand.PreferenceAdd("marco", PreferencePolarity.Likes, preference, None, None)),
        Command.Contact(ContactCommand.FollowUpSet("marco", days, None, Some("email"))),
        Command.Contact(ContactCommand.ReminderAdd("marco", Literal.date(PartialDate.from(day)), "birthday", None, None)),
        Command.Contact(ContactCommand.CompanionAdd("marco", "Pico", None, Nil)),
        Command.Contact(ContactCommand.CircleAdd("Friends", "marco", Nil, None)),
        Command.Contact(ContactCommand.GiftAdd("marco", gift, GiftStatus.Idea, None, Some("birthday"))),
        Command.Contact(ContactCommand.Show("marco")),
        Command.Contact(ContactCommand.Due(day)),
        Command.Contact(ContactCommand.Export("marco", "vcard", true, false)),
        Command.Contact(ContactCommand.Import(importFile.toString, "vcard", true)),
        Command.Archive(ArchiveCommand.Create(archive)),
        Command.Archive(ArchiveCommand.Verify(archive)),
        Command.Archive(ArchiveCommand.Restore(archive, restored))
      )
      for
        _ <- Stream.emit(vcard).through(Files[IO].writeUtf8(importFile)).compile.drain
        codes <- commands.traverse(Main.run(root, utc, _))
        restoredJournal <- Files[IO].exists(dev.librecybernetics.noesis.app.Workspace.journalPath(restored))
      yield
        assertEquals(codes.length, commands.length)
        assert(codes.take(2).forall(_ == ExitCode.Success))
        assert(codes.takeRight(3).forall(_ == ExitCode.Success))
        assert(restoredJournal)
