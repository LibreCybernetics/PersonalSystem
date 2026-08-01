package dev.librecybernetics.noesis.cli

import java.time.ZoneId
import java.util.Locale

import cats.data.{NonEmptyList, Validated}
import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.{Argument, Opts}
import fs2.io.file.{Files, Path}
import dev.librecybernetics.noesis.core.capture.Intent
import dev.librecybernetics.noesis.core.kb.{CommitResult, ReasoningResult}
import dev.librecybernetics.noesis.core.module.{ExportContext, ExportOptions, ImportBatch}
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.core.policy.{DisclosurePolicy, PolicyCascade}
import dev.librecybernetics.noesis.core.projection.AxiomRecord
import dev.librecybernetics.noesis.reasoner.query.PatternSyntax
import dev.librecybernetics.noesis.reasoner.{Consistency, Support}
import dev.librecybernetics.noesis.lms.{ItemId, QueueEntry, QueueMode}
import dev.librecybernetics.noesis.vocab.*

enum ContactCommand:
  case Add(name: String, id: Option[String], organization: Boolean)
  case MethodAdd(
      contact: String,
      value: String,
      kind: ContactKind,
      id: Option[String],
      label: Option[String],
      purpose: Option[String],
      rank: Option[Int]
  )
  case AddressAdd(
      contact: String,
      formatted: String,
      id: Option[String],
      street: Option[String],
      extended: Option[String],
      locality: Option[String],
      region: Option[String],
      postalCode: Option[String],
      countryCode: Option[String],
      label: Option[String],
      purpose: Option[String]
  )
  case MethodRetire(id: String)
  case EmploymentAdd(
      person: String,
      organization: String,
      id: Option[String],
      title: Option[String],
      department: Option[String],
      location: Option[String]
  )
  case InteractionAdd(
      participant: String,
      others: List[String],
      on: PartialDate,
      channel: String,
      id: Option[String],
      note: Option[String]
  )
  case RelationshipAdd(
      first: String,
      second: String,
      others: List[String],
      kind: String,
      id: Option[String],
      description: Option[String],
      anniversary: Option[Literal]
  )
  case NoteAdd(
      contact: String,
      body: String,
      kind: String,
      id: Option[String],
      sensitivity: Sensitivity
  )
  case PreferenceAdd(
      contact: String,
      polarity: String,
      text: String,
      id: Option[String],
      context: Option[String]
  )
  case FollowUpSet(
      contact: String,
      days: Int,
      id: Option[String],
      channel: Option[String]
  )
  case ReminderAdd(
      contact: String,
      due: Literal,
      occasion: String,
      id: Option[String],
      recurrence: Option[String]
  )
  case CompanionAdd(owner: String, name: String, id: Option[String], others: List[String])
  case CircleAdd(name: String, member: String, others: List[String], id: Option[String])
  case GiftAdd(
      contact: String,
      description: String,
      status: String,
      id: Option[String],
      occasion: Option[String]
  )
  case Show(contact: String)
  case Due(on: java.time.LocalDate)
  case Import(path: String, format: String, dryRun: Boolean)
  case Export(
      contact: String,
      format: String,
      includeContactData: Boolean,
      includeSocialGraph: Boolean
  )

enum ArchiveCommand:
  case Create(target: Path)
  case Verify(source: Path)
  case Restore(source: Path, target: Path)

/** Writing and reading back notes (SPEC §8.5). */
enum NoteCommand:
  case Today
  case New(title: String, literature: Boolean)
  case Append(text: String, on: Option[java.time.LocalDate], note: Option[String])
  case Edit(note: Option[String], from: Option[Path])
  case Show(note: Option[String], asOf: Option[java.time.LocalDate])
  case ListNotes
  case History(block: String)

/** What the CLI was asked to do. */
enum Command:
  case Init
  case Assert(
      subject: String,
      property: String,
      value: String,
      sensitivity: Option[Sensitivity],
      scope: List[String],
      utility: Option[Double],
      confidence: Option[Double],
      yes: Boolean
  )
  case Retract(axiomId: String)
  case CloseState(subject: String, property: String, on: Option[PartialDate])
  case Supersede(subject: String, property: String, value: String, on: Option[PartialDate])
  case Show(target: String)
  case QueryCmd(pattern: String)
  case Entails(subject: String, property: String, value: String)
  case Explain(subject: String, property: String, value: String)
  case Check
  case Journal(limit: Option[Int])
  case Queue(mode: QueueMode, limit: Int)
  case Answer(item: String, grade: Double, latencyMs: Long)
  case Items
  case Disclose(policyName: String, level: Sensitivity, scopes: List[String])
  case Loans
  case Export
  case AsOf(date: java.time.LocalDate)
  case Contact(command: ContactCommand)
  case Archive(command: ArchiveCommand)
  case Agenda(on: java.time.LocalDate)
  case VocabSearch(query: String)
  case VocabShow(term: String)
  case Quiz(mode: QueueMode, limit: Int)
  case Note(command: NoteCommand)
  case Backlinks(target: String)
  case Search(term: String)

object Main
    extends CommandIOApp(
      name = "noesis",
      header = "Noesis — a single-user knowledge & learning system on a formal knowledge base",
      version = "0.1.0"
    ):

  // ── Argument parsers ──────────────────────────────────────────────────────

  private given Argument[Sensitivity] = Argument.from("public|internal|personal|sensitive"): raw =>
    Sensitivity.parse(raw).fold(err => Validated.invalidNel(err), Validated.valid)

  // `yyyy[-mm[-dd]]` rather than `date`: a boundary is a located date, and the metavar is where the
  // owner finds that out. An undated boundary is the flag's absence, not a word for "unknown".
  private given Argument[PartialDate] = Argument.from("yyyy[-mm[-dd]]"): raw =>
    PartialDate.parse(raw).fold(err => Validated.invalidNel(err), Validated.valid)

  private given Argument[QueueMode] = Argument.from("retention|elucidation|mixed"): raw =>
    QueueMode.values
      .find(_.toString.equalsIgnoreCase(raw))
      .fold(Validated.invalidNel(s"unknown queue mode: $raw"))(Validated.valid)

  /** An IANA zone name, resolved against the JVM's bundled tzdata.
    *
    * The same string can be valid on one machine and not another, because tzdata ships with the
    * runtime and changes independently of any release — the shape of deviation D5, where language
    * subtag *validity* was left to a registry for the same reason. Nothing is stored, so an
    * unresolvable name costs a message rather than a bad journal line.
    */
  private given Argument[ZoneId] = Argument.from("area/location"): raw =>
    Either
      .catchNonFatal(ZoneId.of(raw))
      .fold(_ => Validated.invalidNel(s"unknown time zone: $raw"), Validated.valid)

  private given Argument[java.time.LocalDate] = Argument.from("yyyy-mm-dd"): raw =>
    Either
      .catchNonFatal(java.time.LocalDate.parse(raw))
      .fold(e => Validated.invalidNel(s"not a date: ${e.getMessage}"), Validated.valid)

  private given Argument[ContactKind] = Argument.from(
    "email|phone|sms|whatsapp|signal|telegram|matrix|website|social|other"
  ): raw =>
    ContactKind.parse(raw).fold(Validated.invalidNel, Validated.valid)

  private val rootOpt: Opts[Path] = Opts
    .option[String]("root", "workspace directory (default ~/.noesis)")
    .map(Path(_))
    .withDefault(Workspace.defaultRoot)

  /** When a reminder falls due: a located date, or a recurring day for a yearly one.
    *
    * Both are ordinary answers — "call the bank on 2026-09-01" and "their anniversary is 05-12" —
    * and since the two are different types, the option accepts either and stores whichever was
    * meant rather than making the owner pick a spelling.
    */
  private val dueOpt: Opts[Literal] = calendarOpt("due", "due date (yyyy-mm-dd) or recurring day (mm-dd)")

  /** A relationship anniversary, which is a recurring day far more often than a dated one. */
  private val anniversaryOpt: Opts[Literal] =
    calendarOpt("anniversary", "relationship anniversary (yyyy-mm-dd or mm-dd)")

  private def calendarOpt(name: String, help: String): Opts[Literal] =
    Opts
      .option[String](name, help)
      .mapValidated: raw =>
        Literal
          .dateOrAnniversary(raw)
          .fold(Validated.invalidNel(s"not a date or a recurring day: $raw"))(Validated.valid)

  /** The zone timestamps are *shown* in (SPEC §3.2).
    *
    * Storage stays UTC: `seq` orders the journal and belief decay measures elapsed time, so neither
    * has a zone to be wrong about. A displayed instant does — an entry captured last night reads as
    * today under UTC for anyone west of it — and that is the whole of the problem here, so the zone
    * is a presentation setting rather than journal data. Per-entry zones wait for §7.4's agenda,
    * where *which local day* starts to decide behaviour.
    */
  private val zoneOpt: Opts[ZoneId] = Opts
    .option[ZoneId]("zone", "time zone for displayed timestamps (default: the system zone)")
    .withDefault(ZoneId.systemDefault())

  // ── Subcommands ───────────────────────────────────────────────────────────

  private val init = Opts.subcommand("init", "create the workspace and install module ontologies"):
    Opts(Command.Init)

  private val assertCmd = Opts.subcommand("assert", "assert a fact, with confirmation of its annotations"):
    (
      Opts.argument[String]("subject"),
      Opts.argument[String]("property"),
      Opts.argument[String]("value"),
      Opts.option[Sensitivity]("sensitivity", "override the cascade's sensitivity").orNone,
      Opts.options[String]("scope", "knowledge scope, required for internal").orEmpty,
      Opts.option[Double]("utility", "override recall utility [0,1]").orNone,
      Opts.option[Double]("confidence", "truth confidence [0,1]; defaults to 1.0").orNone,
      // The only way past the prompt (US-03). A confirmation that anything else can suppress is
      // not a confirmation, and the prompt is where §1.3 says the owner learns the representation.
      Opts.flag("yes", "skip the confirmation prompt, for scripted use").orFalse
    ).mapN(Command.Assert.apply)

  private val retract = Opts.subcommand("retract", "retract an axiom by id"):
    Opts.argument[String]("axiomId").map(Command.Retract.apply)

  private val closeState = Opts.subcommand("close", "close an open time-varying state"):
    (
      Opts.argument[String]("subject"),
      Opts.argument[String]("property"),
      Opts.option[PartialDate]("on", "boundary date").orNone
    ).mapN(Command.CloseState.apply)

  private val supersede = Opts.subcommand("supersede", "replace an open state's value in one step"):
    (
      Opts.argument[String]("subject"),
      Opts.argument[String]("property"),
      Opts.argument[String]("newValue"),
      Opts.option[PartialDate]("on", "boundary date").orNone
    ).mapN(Command.Supersede.apply)

  private val show = Opts.subcommand("show", "show an entity's facts, states and belief"):
    Opts.argument[String]("entity").map(Command.Show.apply)

  private val query = Opts.subcommand("query", "run a basic graph pattern over the closure"):
    Opts
      .argument[String]("pattern")
      .map(Command.QueryCmd.apply)

  private val entails = Opts.subcommand("entails", "ask whether a fact is entailed"):
    (
      Opts.argument[String]("subject"),
      Opts.argument[String]("property"),
      Opts.argument[String]("value")
    ).mapN(Command.Entails.apply)

  private val explain = Opts.subcommand("explain", "show the justifications for a fact"):
    (
      Opts.argument[String]("subject"),
      Opts.argument[String]("property"),
      Opts.argument[String]("value")
    ).mapN(Command.Explain.apply)

  private val check =
    Opts.subcommand("check", "check consistency and annotation policy violations")(Opts(Command.Check))

  private val journal = Opts.subcommand("journal", "dump the journal"):
    Opts.option[Int]("limit", "show only the last N entries").orNone.map(Command.Journal.apply)

  private val queue = Opts.subcommand("queue", "show the review queue"):
    (
      Opts.option[QueueMode]("mode", "selection policy").withDefault(QueueMode.Mixed),
      Opts.option[Int]("limit", "queue length").withDefault(10)
    ).mapN(Command.Queue.apply)

  /** Every module's dated obligations, not the PRM module's (SPEC §5.2).
    *
    * `contact due` runs the same producers and stays as an alias, because it is what is in fingers
    * and in the journeys. What changes is that finding the agenda no longer requires knowing which
    * module happens to own the obligation you are looking for.
    */
  private val agenda = Opts.subcommand("agenda", "show everything due, from every module"):
    Opts
      .option[java.time.LocalDate]("on", "agenda date")
      .withDefault(java.time.LocalDate.now())
      .map(Command.Agenda.apply)

  private val vocabSearch = Opts.subcommand("search", "find a term by name or by how it reads"):
    Opts.argument[String]("query").map(Command.VocabSearch.apply)

  private val vocabShow = Opts.subcommand("show", "show a term's domain, range and defaults"):
    Opts.argument[String]("term").map(Command.VocabShow.apply)

  private val vocab = Opts.subcommand("vocab", "browse the vocabulary the modules declare"):
    vocabSearch orElse vocabShow

  private val quiz = Opts.subcommand("quiz", "be asked the queued questions, and graded"):
    (
      Opts.option[QueueMode]("mode", "selection policy").withDefault(QueueMode.Mixed),
      Opts.option[Int]("limit", "how many to ask").withDefault(10)
    ).mapN(Command.Quiz.apply)

  private val answer = Opts.subcommand("review", "record a review outcome for an item"):
    (
      Opts.argument[String]("itemId"),
      Opts.argument[Double]("grade"),
      Opts.option[Long]("latency", "response latency in ms").withDefault(3000L)
    ).mapN(Command.Answer.apply)

  private val items = Opts.subcommand("items", "list learning items")(Opts(Command.Items))

  private val disclose =
    Opts.subcommand("disclose", "show what an external agent would be allowed to see"):
      (
        Opts.argument[String]("agentName"),
        Opts.option[Sensitivity]("level", "maximum level granted").withDefault(Sensitivity.Public),
        Opts.options[String]("scope", "granted internal knowledge scopes").orEmpty
      ).mapN(Command.Disclose.apply)

  private val loans = Opts.subcommand("loans", "show what is out on loan and borrowed")(Opts(Command.Loans))

  private val exportCmd =
    Opts.subcommand("export", "export the current graph as Turtle")(Opts(Command.Export))

  private val asOf = Opts.subcommand("as-of", "show the graph as it stood on a past date"):
    Opts.argument[java.time.LocalDate]("date").map(Command.AsOf.apply)

  private val archiveCreate = Opts.subcommand(
    "create",
    "capture the journal, reviews, manifest and current Turtle projection"
  ):
    Opts.argument[String]("archive-directory").map(path => ArchiveCommand.Create(Path(path)))

  private val archiveVerify = Opts.subcommand(
    "verify",
    "verify checksums, formats, replay and the derived projection"
  ):
    Opts.argument[String]("archive-directory").map(path => ArchiveCommand.Verify(Path(path)))

  private val archiveRestore = Opts.subcommand(
    "restore",
    "restore a verified archive into a new workspace directory"
  ):
    (
      Opts.argument[String]("archive-directory"),
      Opts.argument[String]("new-workspace-directory")
    ).mapN((source, target) => ArchiveCommand.Restore(Path(source), Path(target)))

  private val archive = Opts.subcommand("archive", "create, verify or restore a portable archive"):
    (archiveCreate orElse archiveVerify orElse archiveRestore).map(Command.Archive.apply)

  private val contactAdd = Opts.subcommand("add", "add a person or organization contact"):
    (
      Opts.argument[String]("name"),
      Opts.option[String]("id", "entity handle; defaults to a name-derived handle").orNone,
      Opts.flag("organization", "create an organization instead of a person").orFalse
    ).mapN(ContactCommand.Add.apply)

  private val contactMethodAdd = Opts.subcommand("method-add", "add a typed contact method"):
    (
      Opts.argument[String]("contact"),
      Opts.argument[String]("value"),
      Opts.option[ContactKind]("kind", "contact method kind"),
      Opts.option[String]("id", "method record handle").orNone,
      Opts.option[String]("label", "display label such as mobile").orNone,
      Opts.option[String]("purpose", "purpose such as home or work").orNone,
      Opts.option[Int]("rank", "lower values are preferred").orNone
    ).mapN(ContactCommand.MethodAdd.apply)

  private val contactAddressAdd = Opts.subcommand("address-add", "add a structured postal address"):
    (
      Opts.argument[String]("contact"),
      Opts.argument[String]("formatted"),
      Opts.option[String]("id", "address record handle").orNone,
      Opts.option[String]("street", "street address").orNone,
      Opts.option[String]("extended", "apartment, suite, or other extension").orNone,
      Opts.option[String]("locality", "city or locality").orNone,
      Opts.option[String]("region", "state or region").orNone,
      Opts.option[String]("postal-code", "postal code").orNone,
      Opts.option[String]("country", "ISO alpha-2 country code").orNone,
      Opts.option[String]("label", "display label").orNone,
      Opts.option[String]("purpose", "purpose such as home or work").orNone
    ).mapN(ContactCommand.AddressAdd.apply)

  private val contactMethodRetire =
    Opts.subcommand("method-retire", "retire a contact method without deleting its history"):
      Opts.argument[String]("method").map(ContactCommand.MethodRetire.apply)

  private val contactEmploymentAdd = Opts.subcommand("employment-add", "add a current employment"):
    (
      Opts.argument[String]("person"),
      Opts.option[String]("at", "organization handle"),
      Opts.option[String]("id", "employment record handle").orNone,
      Opts.option[String]("title", "job title").orNone,
      Opts.option[String]("department", "department").orNone,
      Opts.option[String]("location", "work location").orNone
    ).mapN(ContactCommand.EmploymentAdd.apply)

  private val contactInteractionAdd = Opts.subcommand("interaction-add", "record an interaction"):
    (
      Opts.argument[String]("participant"),
      Opts.options[String]("with", "additional participant").orEmpty,
      Opts.option[PartialDate]("on", "interaction date"),
      Opts.option[String]("channel", "in-person, phone, video, message, email, or other"),
      Opts.option[String]("id", "interaction record handle").orNone,
      Opts.option[String]("note", "brief interaction summary").orNone
    ).mapN(ContactCommand.InteractionAdd.apply)

  private val contactRelationshipAdd =
    Opts.subcommand("relationship-add", "add a reified relationship"):
      (
        Opts.argument[String]("first"),
        Opts.argument[String]("second"),
        Opts.options[String]("with", "additional participant").orEmpty,
        Opts.option[String]("kind", "relationship kind"),
        Opts.option[String]("id", "relationship record handle").orNone,
        Opts.option[String]("description", "self-described relationship text").orNone,
        anniversaryOpt.orNone
      ).mapN(ContactCommand.RelationshipAdd.apply)

  private val contactNoteAdd = Opts.subcommand("note-add", "add a contact note"):
    (
      Opts.argument[String]("contact"),
      Opts.argument[String]("body"),
      Opts.option[String]("kind", "note kind").withDefault("general"),
      Opts.option[String]("id", "note record handle").orNone,
      Opts.option[Sensitivity]("sensitivity", "note sensitivity").withDefault(Sensitivity.Personal)
    ).mapN(ContactCommand.NoteAdd.apply)

  private val contactPreferenceAdd = Opts.subcommand("preference-add", "add a preference"):
    (
      Opts.argument[String]("contact"),
      Opts.argument[String]("polarity"),
      Opts.argument[String]("text"),
      Opts.option[String]("id", "preference record handle").orNone,
      Opts.option[String]("context", "optional context").orNone
    ).mapN(ContactCommand.PreferenceAdd.apply)

  private val contactFollowUp = Opts.subcommand("follow-up-set", "set a keep-in-touch cadence"):
    (
      Opts.argument[String]("contact"),
      Opts.option[Int]("every", "cadence in days"),
      Opts.option[String]("id", "follow-up plan handle").orNone,
      Opts.option[String]("channel", "only count this interaction channel").orNone
    ).mapN(ContactCommand.FollowUpSet.apply)

  private val contactReminderAdd = Opts.subcommand("reminder-add", "add a one-time reminder"):
    (
      Opts.argument[String]("contact"),
      dueOpt,
      Opts.option[String]("occasion", "occasion or prompt"),
      Opts.option[String]("id", "reminder record handle").orNone,
      Opts.option[String]("recurrence", "recurrence description").orNone
    ).mapN(ContactCommand.ReminderAdd.apply)

  private val contactCompanionAdd = Opts.subcommand("companion-add", "add a companion animal"):
    (
      Opts.argument[String]("owner"),
      Opts.argument[String]("name"),
      Opts.option[String]("id", "companion animal handle").orNone,
      Opts.options[String]("with", "additional associated contact").orEmpty
    ).mapN(ContactCommand.CompanionAdd.apply)

  private val contactCircleAdd = Opts.subcommand("circle-add", "create a contact circle"):
    (
      Opts.argument[String]("name"),
      Opts.argument[String]("member"),
      Opts.options[String]("with", "additional member").orEmpty,
      Opts.option[String]("id", "circle handle").orNone
    ).mapN(ContactCommand.CircleAdd.apply)

  private val contactGiftAdd = Opts.subcommand("gift-add", "record a gift idea or gift"):
    (
      Opts.argument[String]("contact"),
      Opts.argument[String]("description"),
      Opts.option[String]("status", "idea, planned, given, or received").withDefault("idea"),
      Opts.option[String]("id", "gift record handle").orNone,
      Opts.option[String]("occasion", "occasion").orNone
    ).mapN(ContactCommand.GiftAdd.apply)

  private val contactShow = Opts.subcommand("show", "show a structured contact card"):
    Opts.argument[String]("contact").map(ContactCommand.Show.apply)

  private val contactDue = Opts.subcommand("due", "show due follow-ups and reminders"):
    Opts
      .option[java.time.LocalDate]("on", "agenda date")
      .withDefault(java.time.LocalDate.now())
      .map(ContactCommand.Due.apply)

  private val contactImport = Opts.subcommand("import", "import vCard or FOAF/RDF contacts"):
    (
      Opts.argument[String]("path"),
      Opts.option[String]("format", "vcard or foaf"),
      Opts.flag("dry-run", "parse and validate without committing").orFalse
    ).mapN(ContactCommand.Import.apply)

  private val contactExport = Opts.subcommand("export", "export one contact as vCard or FOAF"):
    (
      Opts.argument[String]("contact"),
      Opts.option[String]("format", "vcard or foaf"),
      Opts.flag("include-contact-data", "include disclosed mailbox, phone and account data").orFalse,
      Opts.flag("include-social", "include the disclosed person-to-person social graph").orFalse
    ).mapN(ContactCommand.Export.apply)

  private val contact = Opts.subcommand("contact", "personal relationship management"):
    (
      contactAdd orElse contactMethodAdd orElse contactAddressAdd orElse contactMethodRetire orElse
        contactEmploymentAdd orElse contactInteractionAdd orElse contactRelationshipAdd orElse
        contactNoteAdd orElse contactPreferenceAdd orElse contactFollowUp orElse
        contactReminderAdd orElse contactCompanionAdd orElse contactCircleAdd orElse
        contactGiftAdd orElse contactShow orElse contactDue orElse contactImport orElse contactExport
    ).map(Command.Contact.apply)

  private val noteToday = Opts.subcommand("today", "open today's page and show it"):
    Opts(NoteCommand.Today)

  private val noteNew = Opts.subcommand("new", "start a titled note"):
    (
      Opts.argument[String]("title"),
      Opts.flag("literature", "what a source said, rather than what you now think").orFalse
    ).mapN(NoteCommand.New.apply)

  private val noteAppend = Opts.subcommand("append", "add a passage, one block per paragraph"):
    (
      Opts.argument[String]("text"),
      Opts.option[java.time.LocalDate]("on", "the dated page to write to, defaulting to today").orNone,
      Opts.option[String]("note", "a note other than a dated page").orNone
    ).mapN(NoteCommand.Append.apply)

  private val noteEdit = Opts.subcommand("edit", "edit a note in $EDITOR and diff the result back"):
    (
      Opts.argument[String]("note").orNone,
      Opts
        .option[String]("from", "read the saved buffer from a file instead of opening an editor")
        .map(Path(_))
        .orNone
    ).mapN(NoteCommand.Edit.apply)

  private val noteShow = Opts.subcommand("show", "show a note as Markdown"):
    (
      Opts.argument[String]("note").orNone,
      Opts.option[java.time.LocalDate]("as-of", "the note as it stood on a past date").orNone
    ).mapN(NoteCommand.Show.apply)

  private val noteList = Opts.subcommand("list", "list every note")(Opts(NoteCommand.ListNotes))

  private val noteHistory = Opts.subcommand("history", "show every wording a block has had"):
    Opts.argument[String]("block").map(NoteCommand.History.apply)

  private val note = Opts.subcommand("note", "write and read back notes"):
    (
      noteToday orElse noteNew orElse noteAppend orElse noteEdit orElse noteShow orElse
        noteList orElse noteHistory
    ).map(Command.Note.apply)

  private val backlinks = Opts.subcommand("backlinks", "show everything written about an entity"):
    Opts.argument[String]("entity").map(Command.Backlinks.apply)

  private val search = Opts.subcommand("search", "find blocks whose text contains a term"):
    Opts.argument[String]("term").map(Command.Search.apply)

  def main: Opts[IO[ExitCode]] =
    (
      rootOpt,
      zoneOpt,
      init orElse assertCmd orElse retract orElse closeState orElse supersede orElse
        show orElse query orElse entails orElse explain orElse check orElse journal orElse
        vocab orElse agenda orElse queue orElse quiz orElse answer orElse items orElse disclose orElse loans orElse
        exportCmd orElse asOf
          orElse contact orElse archive orElse note orElse backlinks orElse search
    ).mapN(run)

  // ── Execution ─────────────────────────────────────────────────────────────

  private def run(root: Path, zone: ZoneId, command: Command): IO[ExitCode] =
    command match
      case Command.Archive(archiveCommand) => runArchive(root, archiveCommand)
      case _ => Workspace.open(root).flatMap(execute(_, zone, command))

  private def runArchive(root: Path, command: ArchiveCommand): IO[ExitCode] =
    val (action, result) = command match
      case ArchiveCommand.Create(target) => "created and verified" -> Archive.create(root, target)
      case ArchiveCommand.Verify(source) => "verified" -> Archive.verify(source)
      case ArchiveCommand.Restore(source, target) =>
        "restored and verified" -> Archive.restore(source, target)

    result
      .flatMap(report =>
        IO.println(
          s"archive $action: journal sequence ${report.lastJournalSequence}, " +
            s"${report.reviews} review(s)"
        )
      )
      .as(ExitCode.Success)

  private def execute(workspace: Workspace, zone: ZoneId, command: Command): IO[ExitCode] =
    val kb = workspace.kb
    val engine = workspace.engine

    command match
      case Command.Init =>
        Workspace.install(workspace).flatMap(lines => print(lines).as(ExitCode.Success))

      case Command.Assert(subject, property, value, sensitivity, scope, utility, confidence, yes) =>
        val annotations = AxiomAnnotations(
          truthConfidence = confidence.orElse(Some(1.0)),
          sensitivity = sensitivity,
          knowledgeScope = scope.map(Workspace.iri).toSet,
          recallUtility = utility
        )
        for
          axiom <- buildAssertion(workspace, subject, property, value)
          accepted <- confirmAssertion(workspace, axiom, annotations, skip = yes)
          code <-
            if !accepted then IO.println("not committed").as(ExitCode.Success)
            else
              kb.commit(NonEmptyList.one(Intent.Assert(axiom, annotations)))
                .flatMap(reportCommit(workspace, _))
        yield code

      case Command.Retract(id) =>
        kb.commit(NonEmptyList.one(Intent.Retract(AxiomId.unsafe(id))))
          .flatMap(reportCommit(workspace, _))

      case Command.CloseState(subject, property, on) =>
        kb.commit(
          NonEmptyList.one(
            Intent.CloseState(Workspace.iri(subject), Workspace.iri(property), validTo = on)
          )
        ).flatMap(reportCommit(workspace, _))

      case Command.Supersede(subject, property, value, on) =>
        kb.commit(
          NonEmptyList.one(
            Intent.Supersede(Workspace.iri(subject), Workspace.iri(property), node(value), on)
          )
        ).flatMap(reportCommit(workspace, _))

      case Command.Show(target) =>
        val entity = Workspace.iri(target)
        for
          state <- kb.state
          verbalizer <- kb.verbalizer
          records = state.about(entity)._1
          beliefs <- engine.beliefsFor(records.map(_.id).toSet)
          _ <- IO.println(Render.entity(verbalizer, state, entity, beliefs))
        yield ExitCode.Success

      case Command.QueryCmd(pattern) =>
        PatternSyntax.parse(pattern) match
          case Left(err) => IO.println(s"bad pattern: $err").as(ExitCode.Error)
          case Right(bgp) =>
            for
              view <- kb.disclosureView(DisclosurePolicy.localOwner("owner CLI"))
              result = view.query(bgp)
              order = bgp.variables.toList.sorted
              solutions = result match
                case ReasoningResult.Complete(found)      => found
                case ReasoningResult.Incomplete(found, _) => found
              _ <- result match
                case ReasoningResult.Complete(_) => IO.unit
                case ReasoningResult.Incomplete(_, reasons) =>
                  IO.println(
                    s"warning: reasoning incomplete (${reasons.toList.sorted.mkString(", ")}); " +
                      "showing sound partial results"
                  )
              _ <-
                if solutions.isEmpty then IO.println("no solutions")
                else
                  solutions.traverse_ : solution =>
                    val cells = order.map: variable =>
                      val rendered = solution
                        .get(variable)
                        .map:
                          case Node.Ref(iri) => view.verbalizer.label(iri)
                          case Node.Lit(lit) => lit.text
                        .getOrElse("-")
                      s"?$variable=$rendered"
                    IO.println("  " + cells.mkString("  "))
            yield result match
              case ReasoningResult.Complete(_)      => ExitCode.Success
              case ReasoningResult.Incomplete(_, _) => ExitCode.Error

      case Command.Entails(subject, property, value) =>
        for
          axiom <- buildAssertion(workspace, subject, property, value)
          view <- kb.disclosureView(DisclosurePolicy.localOwner("owner CLI"))
          result = view.entails(axiom)
          _ <- result match
            case ReasoningResult.Complete(true) =>
              IO.println(s"yes — ${view.verbalizer.verbalize(axiom)}")
            case ReasoningResult.Complete(false) =>
              IO.println(s"no — ${view.verbalizer.verbalize(axiom)} is not entailed")
            case ReasoningResult.Incomplete(partial, reasons) =>
              IO.println(
                s"unknown — reasoning incomplete (${reasons.toList.sorted.mkString(", ")}); " +
                  s"partial closure ${if partial then "contains" else "does not contain"} the fact"
              )
        yield result match
          case ReasoningResult.Complete(true)  => ExitCode.Success
          case ReasoningResult.Complete(false) => ExitCode(1)
          case ReasoningResult.Incomplete(_, _) => ExitCode.Error

      case Command.Explain(subject, property, value) =>
        for
          axiom <- buildAssertion(workspace, subject, property, value)
          view <- kb.disclosureView(DisclosurePolicy.localOwner("owner CLI"))
          result = view.explain(axiom)
          explanation = result match
            case ReasoningResult.Complete(found)      => found
            case ReasoningResult.Incomplete(found, _) => found
          _ <- result match
            case ReasoningResult.Complete(_) => IO.unit
            case ReasoningResult.Incomplete(_, reasons) =>
              IO.println(
                s"warning: reasoning incomplete (${reasons.toList.sorted.mkString(", ")}); " +
                  "the explanation may be partial"
              )
          _ <- explanation match
            case None => IO.println("not entailed; nothing to explain")
            case Some(found) =>
              IO.println(s"${view.verbalizer.verbalize(axiom)}") *>
                IO.println(
                  if found.isAsserted then "  asserted directly"
                  else s"  derived, ${found.justifications.size} justification(s):"
                ) *>
                found.justifications.toList.sorted.zipWithIndex.traverse_ : (justification, i) =>
                  IO.println(s"  ${i + 1}. because:") *>
                    justification.premises.toList.sorted.traverse_ : premise =>
                      val described = premise match
                        case Support.Asserted(id) =>
                          view.state
                            .axiom(id)
                            .map(r => view.verbalizer.verbalize(r.axiom))
                            .getOrElse(id.value)
                        case Support.FromFluent(id) =>
                          view.state
                            .fluent(id)
                            .map(view.verbalizer.verbalize)
                            .getOrElse(id.value)
                      IO.println(s"       - $described")
        yield result match
          case ReasoningResult.Complete(_)      => ExitCode.Success
          case ReasoningResult.Incomplete(_, _) => ExitCode.Error

      case Command.Check =>
        for
          closure <- kb.closure
          problems = Consistency.check(closure)
          violations <- kb.policyViolations
          records <- kb.records
          warnings = Profile.warnings(records.map(_.axiom))
          _ <- IO.println(s"axioms: ${records.length}")
          _ <-
            if !closure.complete then
              IO.println(
                s"consistency: UNKNOWN — reasoning incomplete " +
                  s"(${closure.incompleteReasons.toList.sorted.mkString(", ")})"
              )
            else if problems.isEmpty then IO.println("consistency: ok")
            else IO.println("consistency: FAILED") *> print(problems.map("  " + _.render))
          _ <-
            if violations.isEmpty then IO.println("annotation policies: ok")
            else IO.println("annotation policies:") *> print(violations.map("  " + _))
          _ <-
            if warnings.isEmpty then IO.println("OWL 2 EL profile: ok")
            else
              IO.println(s"OWL 2 EL profile: ${warnings.length} axiom(s) outside EL") *>
                print(warnings.map((a, why) => s"  ${a.manchester} — $why"))
        yield
          if !closure.complete then ExitCode.Error
          else if problems.isEmpty && violations.isEmpty then ExitCode.Success
          else ExitCode(1)

      case Command.Journal(limit) =>
        for
          entries <- kb.journal.stream.compile.toList
          shown = limit.fold(entries)(n => entries.takeRight(n))
          _ <- shown.traverse_ : entry =>
            IO.println(
              f"${entry.seq}%5d  ${Timestamps.show(entry.at, zone)}  ${entry.operation.getClass.getSimpleName}"
            )
        yield ExitCode.Success

      case Command.Queue(mode, limit) =>
        for
          entries <- engine.queue(mode, limit)
          _ <-
            if entries.isEmpty then IO.println("queue is empty — nothing is due")
            else
              IO.println(s"${entries.length} item(s) due:") *>
                entries.zipWithIndex.traverse_((entry, i) => IO.println(Render.queueEntry(i, entry)))
        yield ExitCode.Success

      case Command.Answer(item, grade, latency) =>
        if !grade.isFinite || grade < 0.0 || grade > 1.0 then
          IO.println("grade must be a finite number in [0,1]").as(ExitCode.Error)
        else if latency < 0L then
          IO.println("latency must be non-negative").as(ExitCode.Error)
        else engine.review(ItemId.unsafe(item), grade, latency).flatMap {
          case None => IO.println(s"no such item: $item").as(ExitCode.Error)
          case Some(outcome) =>
            // Persist the review, or the next invocation would rebuild without it.
            workspace.recordReview(outcome.review) *>
              IO.println(
                f"belief ${outcome.review.beliefBefore}%.2f → ${outcome.review.beliefAfter}%.2f, " +
                  f"stability ${outcome.review.stabilityAfter}%.1f days"
              ).as(ExitCode.Success)
        }

      case Command.Items =>
        engine.items.flatMap: all =>
          if all.isEmpty then IO.println("no learning items").as(ExitCode.Success)
          else
            IO.println(s"${all.length} item(s):") *>
              all.sortBy(_.id.value).traverse_(i => IO.println(Render.item(i))).as(ExitCode.Success)

      case Command.Disclose(name, level, scopes) =>
        val policy = DisclosurePolicy(name, level, scopes.map(Workspace.iri).toSet)
        for
          closure <- kb.closure
          verbalizer <- kb.verbalizer(policy)
          assertions = closure.assertions.toList.sortBy(_.id.value)
          (disclosed, redacted) <- kb.disclosable(assertions, policy)
          _ <- IO.println(
            s"policy '$name': max=${level.toString.toLowerCase(Locale.ROOT)}" +
              (if scopes.isEmpty then "" else scopes.mkString(", scopes=[", ", ", "]"))
          )
          _ <- IO.println(s"${disclosed.length} disclosed, ${redacted.length} withheld")
          _ <- disclosed.traverse_ : (axiom, effective) =>
            IO.println(
              Render.disclosure(
                verbalizer,
                axiom,
                dev.librecybernetics.noesis.core.policy.DisclosureDecision.Disclose(effective)
              )
            )
          _ <- IO.whenA(redacted.nonEmpty)(
            IO.println(s"  ... and ${redacted.length} withheld:") *>
              redacted.traverse_((_, reason) => IO.println(s"  ✗ [redacted] — $reason"))
          )
          _ <- IO.whenA(!closure.complete)(
            IO.println(
              s"warning: reasoning incomplete (${closure.incompleteReasons.toList.sorted.mkString(", ")}); " +
                "the disclosure report is partial"
            )
          )
        yield if closure.complete then ExitCode.Success else ExitCode.Error

      case Command.Loans =>
        for
          state <- kb.state
          verbalizer <- kb.verbalizer
          out = Ledger.outOnLoan(state, CoreModule.me)
          in = Ledger.borrowed(state, CoreModule.me)
          _ <- IO.println("out on loan:")
          _ <-
            if out.isEmpty then IO.println("  (nothing)")
            else
              out.traverse_((resource, holder) =>
                IO.println(s"  ${verbalizer.label(resource)} → ${verbalizer.label(holder)}")
              )
          _ <- IO.println("borrowed:")
          _ <-
            if in.isEmpty then IO.println("  (nothing)")
            else
              in.traverse_((resource, owner) =>
                IO.println(s"  ${verbalizer.label(resource)} ← ${verbalizer.label(owner)}")
              )
        yield ExitCode.Success

      case Command.Export =>
        kb.state.flatMap(state => IO.println(Render.turtle(state))).as(ExitCode.Success)

      case Command.AsOf(date) =>
        for
          graph <- kb.graphAsOf(date)
          verbalizer <- kb.verbalizer
          assertions = graph.assertions.toList.sortBy(_.id.value)
          _ <- IO.println(s"as of $date — ${assertions.length} assertion(s):")
          _ <- assertions.traverse_(a => IO.println(s"  ${verbalizer.verbalize(a)}"))
        yield ExitCode.Success

      case Command.Contact(contactCommand) =>
        executeContact(workspace, contactCommand)

      case Command.Archive(archiveCommand) =>
        runArchive(workspace.root, archiveCommand)

      case Command.Agenda(on) =>
        agendaFor(workspace, on)

      // Read from the module contract, so no workspace is consulted: the vocabulary is the same
      // before the first fact as after the thousandth, which is when it is most needed.
      case Command.VocabSearch(query) =>
        print(Render.vocabMatches(query, Vocabulary.search(vocabulary, query))).as(ExitCode.Success)

      case Command.VocabShow(name) =>
        Vocabulary.find(vocabulary, name) match
          case Some(term) => print(Render.vocabTerm(term)).as(ExitCode.Success)
          case None =>
            print(
              List(
                s"no such term: $name",
                s"  `noesis vocab search $name` looks for one by name and by how it reads"
              )
            ).as(ExitCode.Error)

      case Command.Quiz(mode, limit) =>
        executeQuiz(workspace, mode, limit)

      case Command.Note(noteCommand) =>
        executeNote(workspace, zone, noteCommand)

      case Command.Backlinks(target) =>
        val entity = Workspace.iri(target)
        for
          state <- kb.state
          closure <- kb.closure
          _ <- print(Notes.backlinks(Backlinks.of(state, closure, entity), entity))
        yield ExitCode.Success

      case Command.Search(term) =>
        kb.state.flatMap(state => print(Notes.search(state, term))).as(ExitCode.Success)

  /** Shows what would be written and waits for the owner to accept it (SPEC §1.3, §3.5.5).
    *
    * Nothing reaches the journal until this returns true. The three views §3.5.5 requires are all
    * shown — the verbalization, the identifier, the Manchester rendering — together with the
    * annotations the cascade resolved, because the annotations are what the owner cannot otherwise
    * see and are what decides whether the fact may ever leave the machine.
    *
    * This is also the moment §1.3 relies on to teach the vocabulary: the owner types a term and is
    * shown what the system understood by it, which is why reporting the commit afterwards was not
    * a smaller version of the same thing.
    */
  private def confirmAssertion(
      workspace: Workspace,
      axiom: Axiom,
      annotations: AxiomAnnotations,
      skip: Boolean
  ): IO[Boolean] =
    if skip then IO.pure(true)
    else
      val record = AxiomRecord(axiom.id, axiom, annotations, AxiomStatus.Active, 0L)
      val policies = Workspace.config.policies

      for
        verbalizer <- workspace.kb.verbalizer
        _ <- IO.println("about to assert:")
        _ <- IO.println(Render.confirmable(verbalizer, axiom))
        _ <- IO.println(
          f"    sensitivity: ${PolicyCascade.sensitivity(record, policies)}" +
            annotations.sensitivity.fold(" (from the cascade)")(_ => " (yours)")
        )
        _ <- IO.println(
          f"    utility:     ${PolicyCascade.recallUtility(record, policies)}%.2f" +
            annotations.recallUtility.fold(" (from the cascade)")(_ => " (yours)")
        )
        _ <- IO.println(f"    confidence:  ${annotations.truthConfidence.getOrElse(1.0)}%.2f")
        _ <- IO.print("commit? [y/N] ")
        typed <- IO.readLine
      yield accepted(typed)

  /** Only an explicit yes commits. An empty line, a closed stdin or anything else does not, because
    * the fail-closed default here is "do not write".
    */
  private def accepted(response: String): Boolean =
    Option(response).map(_.trim.toLowerCase(Locale.ROOT)).exists(Set("y", "yes").contains)

  /** The shipped terms, derived once from the module contract. */
  private lazy val vocabulary: List[Vocabulary.Term] = Vocabulary.of(Modules.all)

  /** Every module's dated obligations for one day, in one queue (SPEC §5.2). */
  private def agendaFor(workspace: Workspace, on: java.time.LocalDate): IO[ExitCode] =
    for
      state <- workspace.kb.state
      verbalizer <- workspace.kb.verbalizer
      entries = Modules.agendaProducers(Modules.all).flatMap(_.entries(state, on))
      _ <- IO.println(Render.agenda(entries, verbalizer))
    yield ExitCode.Success

  // ── The review loop (SPEC §4.1, §4.3) ─────────────────────────────────────

  /** Asks the queued questions one at a time, grades each, and logs the outcome.
    *
    * The loop is sequential and re-reads the queue's entries in order rather than recomputing it
    * per answer: a review changes belief, so recomputing would let one correct answer reorder what
    * is still to come, and the owner would be unable to tell how long the session was.
    */
  private def executeQuiz(workspace: Workspace, mode: QueueMode, limit: Int): IO[ExitCode] =
    val engine = workspace.engine

    def askOne(index: Int, total: Int, entry: QueueEntry): IO[(Int, Int)] =
      engine.nextQuestion(entry).flatMap: question =>
        (question, Quiz.unaskable(question)) match
          case (_, Some(skipped)) =>
            IO.println(s"[$index/$total] ${entry.item.prompt}") *>
              IO.println(skipped.render).as((0, 0))

          case (Some(asked), None) =>
            for
              _ <- print(Quiz.ask(index, total, entry, asked))
              _ <- IO.print("  > ")
              typed <- IO.readLine
              started <- IO.monotonic
              outcome <- engine.answer(asked, Quiz.chosen(asked, typed), 0L)
              finished <- IO.monotonic
              scored <- outcome match
                case None =>
                  IO.println("  could not be graded").as((0, 0))
                case Some(result) =>
                  // The recorded latency is the owner's, measured around the read; a review logged
                  // with a fabricated one would distort the discrimination stats it feeds.
                  val measured = result.copy(
                    review = result.review.copy(latencyMs = (finished - started).toMillis)
                  )
                  workspace.recordReview(measured.review) *>
                    print(Quiz.verdict(asked, measured))
                      .as((1, if measured.review.grade >= 1.0 then 1 else 0))
            yield scored

          // `unaskable` returns a reason for every absent question, so this cannot arise.
          case (None, None) => IO.pure((0, 0))

    for
      entries <- engine.queue(mode, limit)
      code <-
        if entries.isEmpty then IO.println("nothing due — the queue is empty").as(ExitCode.Success)
        else
          for
            tallies <- entries.zipWithIndex.traverse: (entry, at) =>
              askOne(at + 1, entries.length, entry)
            asked = tallies.map(_._1).sum
            correct = tallies.map(_._2).sum
            _ <- IO.println(Quiz.summary(asked, correct, entries.length - asked))
          yield ExitCode.Success
    yield code

  // ── Notes (SPEC §8.5) ─────────────────────────────────────────────────────

  private def executeNote(workspace: Workspace, zone: ZoneId, command: NoteCommand): IO[ExitCode] =
    val kb = workspace.kb

    /** The note a command is about: the one named, or today's page. */
    def target(named: Option[String]): IO[Iri] =
      named match
        case Some(token) => IO.pure(Workspace.iri(token))
        case None        => today.map(NoteIds.daily)

    def today: IO[java.time.LocalDate] =
      IO.realTimeInstant.map(_.atZone(zone).toLocalDate)

    def outlineOf(note: Iri): IO[Outline.Note] = kb.state.map(Outline.of(_, note))

    /** Commits, then rebuilds the mirror, so that a file search never lags the journal. */
    def commit(intents: List[Intent]): IO[ExitCode] =
      Notes.commitAll(workspace, intents).flatTap: _ =>
        kb.state.flatMap(Notes.mirror(workspace.root, _))

    command match
      case NoteCommand.Today =>
        for
          day <- today
          code <- Notes.commitAll(workspace, NotesCapture.daily(day).toList)
          state <- kb.state
          _ <- Notes.mirror(workspace.root, state)
          _ <- print(Notes.show(Outline.of(state, NoteIds.daily(day))))
        yield code

      case NoteCommand.New(title, literature) =>
        val kind = if literature then NoteKind.Literature else NoteKind.Permanent
        val id = NoteIds.note(kind, title)
        for
          day <- today
          code <- commit(NotesCapture.note(id, kind, title, day).toList)
          _ <- IO.println(s"${id.display}  $title")
        yield code

      case NoteCommand.Append(text, on, named) =>
        for
          day <- today
          note <- named.map(token => IO.pure(Workspace.iri(token))).getOrElse(
            IO.pure(NoteIds.daily(Notes.resolveDate(on, day)))
          )
          // Opening the page is idempotent, so quick capture never has to ask whether it exists.
          _ <- Notes.commitAll(
            workspace,
            if named.isEmpty then NotesCapture.daily(Notes.resolveDate(on, day)).toList else Nil
          )
          outline <- outlineOf(note)
          blocks <- Notes.freshBlocks(NotesCapture.paragraphs(text).length)
          written <- IO.fromEither(
            NotesCapture.appendAll(outline, blocks, text).leftMap(problem => Problem(problem.render))
          )
          state <- kb.state
          links = blocks.zip(NotesCapture.paragraphs(text)).map((block, paragraph) =>
            Notes.mentions(state, block, paragraph)
          )
          code <- commit(written ++ links.flatMap(_._1))
          _ <- print(links.flatMap(_._2).map(question => s"  $question"))
        yield code

      case NoteCommand.Show(named, asOf) =>
        for
          note <- target(named)
          state <- kb.state
          outline = asOf.fold(Outline.of(state, note))(Outline.asOf(state, note, _))
          _ <- print(Notes.show(outline))
        yield ExitCode.Success

      case NoteCommand.ListNotes =>
        kb.state.flatMap(state => print(Notes.listing(Notes.all(state)))).as(ExitCode.Success)

      case NoteCommand.History(block) =>
        val id = Workspace.iri(block)
        for
          state <- kb.state
          wordings = state.fluents.values.toList
            .filter(f => f.statedSubject == id && f.statedProperty == NotesModule.text)
            .sortBy(_.validFrom.map(_.lowerBound.toEpochDay).getOrElse(Long.MinValue))
          _ <- print(
            if wordings.isEmpty then List(s"${id.display} has no recorded wording")
            else wordings.map(fluent => s"  ${fluent.describe}")
          )
        yield ExitCode.Success

      case NoteCommand.Edit(named, from) =>
        for
          note <- target(named)
          state <- kb.state
          before = Outline.of(state, note)
          buffer = NoteEditor.render(before, Notes.loadBearing(state, before))
          saved <- from match
            case Some(path) => fs2.io.file.Files[IO].readUtf8(path).compile.string
            case None       => Notes.inEditor(buffer)
          lines <- IO.fromEither(
            NoteEditor.parse(saved, before).leftMap(problem => Problem(problem.render))
          )
          matched = NoteEditor.align(before, lines)
          fresh <- Notes.freshBlocks(NoteEditor.additions(matched))
          intents <- IO.fromEither(
            NoteEditor.plan(before, matched, fresh).leftMap(problem => Problem(problem.render))
          )
          code <- commit(intents)
          _ <- IO.println(
            if intents.isEmpty then "no changes"
            else s"${intents.length} change(s), ${NoteEditor.removed(before, matched).length} removal(s)"
          )
        yield code

  /** A failure the owner caused and can act on, rendered without a stack trace. */
  private final case class Problem(detail: String) extends RuntimeException(detail)

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Builds an assertion, deciding from the ontology whether the value is a reference or a literal.
    *
    * This is the CLI's stand-in for §3.5's entity-resolution step. The property's declared range
    * decides first, because that is what the ontology is for: guessing from the value alone turns
    * `label drill` into a self-referential object assertion the moment an entity named `drill`
    * exists. Only an undeclared property falls back to inspecting the value, and the resulting axiom
    * is always echoed back before it is treated as committed.
    */
  private def buildAssertion(
      workspace: Workspace,
      subject: String,
      property: String,
      value: String
  ): IO[Axiom] =
    workspace.kb.closure.map: closure =>
      val s = Workspace.iri(subject)
      val p = Workspace.iri(property)
      val candidate = Workspace.iri(value)
      val view = closure.view

      def objectAssertion = Axiom.ObjectAssertion(s, p, candidate)
      def dataAssertion = Axiom.DataAssertion(s, p, Literal.parse(value))

      if p == Vocab.rdfType then Axiom.ClassAssertion(s, candidate)
      // A declared range makes this an object property, whatever the value looks like.
      else if view.ranges.contains(p) then objectAssertion
      // Labels are literals by definition, and are the case the value heuristic gets wrong most.
      else if p == Vocab.label then dataAssertion
      // Otherwise follow how the property is already used, then fall back to the value's shape.
      else if view.objectByProperty.contains(p) then objectAssertion
      else if view.dataByProperty.contains(p) then dataAssertion
      else if value.contains(':') then objectAssertion
      else dataAssertion

  private def node(value: String): Node =
    if value.contains(':') then Node.Ref(Iri(value)) else Node.Lit(Literal.parse(value))

  private def executeContact(
      workspace: Workspace,
      command: ContactCommand
  ): IO[ExitCode] =
    command match
      case ContactCommand.Add(name, id, organization) =>
        val contact = Workspace.iri(id.getOrElse(slug(name)))
        commitStructured(
          workspace,
          PrmCapture.contact(
            ContactInput(
              contact,
              name,
              if organization then ContactEntityKind.Organization else ContactEntityKind.Person,
              if organization then "professional" else "chosen"
            )
          )
        )

      case ContactCommand.MethodAdd(contact, value, kind, id, label, purpose, rank) =>
        val owner = Workspace.iri(contact)
        val method = id
          .map(Workspace.iri)
          .getOrElse(PrmIds.child(owner, "method", s"${kind.value}\u0000$value"))
        commitStructured(
          workspace,
          PrmCapture.method(
            ContactMethodInput(method, owner, kind, value, label, purpose, rank)
          )
        )

      case ContactCommand.AddressAdd(
            contact,
            formatted,
            id,
            street,
            extended,
            locality,
            region,
            postalCode,
            countryCode,
            label,
            purpose
          ) =>
        val owner = Workspace.iri(contact)
        val address = id
          .map(Workspace.iri)
          .getOrElse(PrmIds.child(owner, "address", formatted))
        commitStructured(
          workspace,
          PrmCapture.address(
            PostalAddressInput(
              address,
              owner,
              formatted,
              street,
              extended,
              locality,
              region,
              postalCode,
              countryCode,
              label,
              purpose
            )
          )
        )

      case ContactCommand.MethodRetire(id) =>
        workspace.kb.commit(PrmCapture.retire(Workspace.iri(id)))
          .flatMap(reportCommit(workspace, _))

      case ContactCommand.EmploymentAdd(person, organization, id, title, department, location) =>
        val employee = Workspace.iri(person)
        val employer = Workspace.iri(organization)
        val record = id
          .map(Workspace.iri)
          .getOrElse(PrmIds.child(employee, "employment", employer.value))
        workspace.kb
          .commit(
            PrmCapture.employment(
              EmploymentInput(record, employee, employer, title, department, location)
            )
          )
          .flatMap(reportCommit(workspace, _))

      case ContactCommand.InteractionAdd(participant, others, on, channel, id, note) =>
        val participants = (participant :: others).map(Workspace.iri).distinct
        val record = id
          .map(Workspace.iri)
          .getOrElse(
            PrmIds.record(
              "interaction",
              s"${participants.map(_.value).sorted.mkString("\u0000")}\u0000${on.render}\u0000$channel"
            )
          )
        commitStructured(
          workspace,
          PrmCapture.interaction(
            InteractionInput(record, participants, on, channel, summary = note)
          )
        )

      case ContactCommand.RelationshipAdd(
            first,
            second,
            others,
            kind,
            id,
            description,
            anniversary
          ) =>
        val participants = (first :: second :: others).map(Workspace.iri).distinct
        val record = id
          .map(Workspace.iri)
          .getOrElse(
            PrmIds.record(
              "relationship",
              s"$kind\u0000${participants.map(_.value).sorted.mkString("\u0000")}"
            )
          )
        commitStructured(
          workspace,
          PrmCapture.relationship(
            RelationshipInput(record, participants, kind, description, anniversary)
          )
        )

      case ContactCommand.NoteAdd(contact, body, kind, id, sensitivity) =>
        val owner = Workspace.iri(contact)
        val record = id.map(Workspace.iri).getOrElse(PrmIds.child(owner, "note", body))
        commitStructured(
          workspace,
          PrmCapture.note(NoteInput(record, owner, body, kind, sensitivity = sensitivity))
        )

      case ContactCommand.PreferenceAdd(contact, polarity, text, id, context) =>
        val owner = Workspace.iri(contact)
        val record = id
          .map(Workspace.iri)
          .getOrElse(PrmIds.child(owner, "preference", s"$polarity\u0000$text"))
        commitStructured(
          workspace,
          PrmCapture.preference(
            PreferenceInput(record, owner, polarity, text, context)
          )
        )

      case ContactCommand.FollowUpSet(contact, days, id, channel) =>
        val owner = Workspace.iri(contact)
        val record = id
          .map(Workspace.iri)
          .getOrElse(PrmIds.child(owner, "follow-up", channel.getOrElse("all")))
        commitStructured(
          workspace,
          PrmCapture.followUp(FollowUpInput(record, owner, days, channel))
        )

      case ContactCommand.ReminderAdd(contact, due, occasion, id, recurrence) =>
        val owner = Workspace.iri(contact)
        val record = id
          .map(Workspace.iri)
          .getOrElse(PrmIds.child(owner, "reminder", s"${due.lexical}\u0000$occasion"))
        commitStructured(
          workspace,
          PrmCapture.reminder(ReminderInput(record, owner, due, occasion, recurrence))
        )

      case ContactCommand.CompanionAdd(owner, name, id, others) =>
        val companions = (owner :: others).map(Workspace.iri).distinct
        val record = id
          .map(Workspace.iri)
          .getOrElse(PrmIds.record("companion", s"$name\u0000${companions.map(_.value).sorted.mkString}"))
        commitStructured(
          workspace,
          PrmCapture.companionAnimal(CompanionAnimalInput(record, name, companions))
        )

      case ContactCommand.CircleAdd(name, member, others, id) =>
        val members = (member :: others).map(Workspace.iri).distinct
        val record = id.map(Workspace.iri).getOrElse(PrmIds.record("circle", name))
        commitStructured(workspace, PrmCapture.circle(CircleInput(record, name, members)))

      case ContactCommand.GiftAdd(contact, description, status, id, occasion) =>
        val recipient = Workspace.iri(contact)
        val record = id
          .map(Workspace.iri)
          .getOrElse(PrmIds.child(recipient, "gift", s"$status\u0000$description"))
        commitStructured(
          workspace,
          PrmCapture.gift(
            GiftInput(record, description, to = Some(recipient), status = status, occasion = occasion)
          )
        )

      case ContactCommand.Show(contact) =>
        val target = Workspace.iri(contact)
        for
          state <- workspace.kb.state
          verbalizer <- workspace.kb.verbalizer
          code <-
            if !state.entities.contains(target) then
              IO.println(s"no such contact: $contact").as(ExitCode.Error)
            else
              IO.println(Render.contactCard(Prm.contactCard(state, target), verbalizer))
                .as(ExitCode.Success)
        yield code

      // The alias kept by US-15. One implementation, so the two can never disagree about what is due.
      case ContactCommand.Due(on) => agendaFor(workspace, on)

      case ContactCommand.Import(path, format, dryRun) =>
        Files[IO].readUtf8(Path(path)).compile.string.flatMap: document =>
          val normalized = format.toLowerCase(Locale.ROOT)
          val parsed = Modules
            .importers(Modules.all)
            .find(_.formats.contains(normalized))
            .toRight(List(s"unknown contact import format: $normalized"))
            .flatMap(_.parse(document))
          parsed match
            case Left(problems) =>
              print(problems.map("  " + _)).as(ExitCode.Error)
            case Right(batches) =>
              workspace.kb.state.flatMap: state =>
                val candidates = batches.flatMap: batch =>
                  val (name, methods) = Prm.importEvidence(batch.record, batch.intents)
                  Prm.duplicateCandidates(state, name, methods).map(batch.record -> _)
                val report = candidates.traverse_(
                  (entry: (Iri, DuplicateCandidate)) =>
                    val (incoming, candidate) = entry
                    IO.println(
                      s"possible duplicate ${incoming.display} → ${candidate.contact.display}: " +
                        candidate.reasons.mkString("; ")
                    )
                )
                if dryRun then
                  val operations = batches.map(_.intents.length).sum
                  report *> IO.println(
                    s"dry run: ${batches.length} record batch(es), $operations intent(s), nothing committed"
                  ).as(ExitCode.Success)
                else report *> commitBatches(workspace, batches)

      case ContactCommand.Export(contact, format, includeContactData, includeSocialGraph) =>
        val target = Workspace.iri(contact)
        for
          state <- workspace.kb.state
          closure <- workspace.kb.closure
          normalized = format.toLowerCase(Locale.ROOT)
          rendered =
            if !closure.complete then
              Left(
                List(
                  s"reasoning incomplete (${closure.incompleteReasons.toList.sorted.mkString(", ")}); " +
                    "refusing to produce a possibly partial contact export"
                )
              )
            else
              Modules
                .exporters(Modules.all)
                .find(_.formats.contains(normalized))
                .toRight(List(s"unknown contact export format: $normalized"))
                .flatMap(
                  _.render(
                    ExportContext.restricted(
                      state,
                      closure,
                      Workspace.config.policies,
                      DisclosurePolicy.personal("contact export"),
                      Workspace.config.namingProperties,
                      Workspace.config.namingSchemes
                    ),
                    target,
                    ExportOptions(includeContactData, includeSocialGraph)
                  )
                )
          code <- rendered match
            case Left(problems) => print(problems).as(ExitCode.Error)
            case Right(document) => IO.println(document).as(ExitCode.Success)
        yield code

  private def commitStructured(
      workspace: Workspace,
      planned: Either[List[String], NonEmptyList[Intent]]
  ): IO[ExitCode] =
    planned match
      case Left(problems) => print(problems.map("invalid contact input: " + _)).as(ExitCode.Error)
      case Right(intents) =>
        workspace.kb.commit(intents).flatMap(reportCommit(workspace, _))

  private def commitBatches(
      workspace: Workspace,
      batches: List[ImportBatch]
  ): IO[ExitCode] =
    batches.foldLeftM(ExitCode.Success): (status, batch) =>
      if status != ExitCode.Success then status.pure[IO]
      else
        IO.println(s"importing ${batch.record.display}") *>
          workspace.kb.commit(batch.intents).flatMap(reportCommit(workspace, _))

  private def slug(value: String): String =
    val ascii = java.text.Normalizer
      .normalize(value, java.text.Normalizer.Form.NFKD)
      .replaceAll("\\p{M}", "")
      .toLowerCase(Locale.ROOT)
      .replaceAll("[^a-z0-9]+", "-")
      .stripPrefix("-")
      .stripSuffix("-")
    if ascii.nonEmpty then ascii else PrmIds.record("contact", value).local

  private def reportCommit(
      workspace: Workspace,
      result: Either[dev.librecybernetics.noesis.core.kb.CommitRejected, CommitResult]
  ): IO[ExitCode] =
    result match
      case Left(rejected) => IO.println(rejected.render).as(ExitCode(1))
      case Right(commit) if commit.commit.entries.isEmpty =>
        IO.println("already true — nothing committed").as(ExitCode.Success)
      case Right(commit) =>
        for
          verbalizer <- workspace.kb.verbalizer
          _ <- IO.println(s"committed ${commit.commit.entries.length} operation(s):")
          _ <- commit.commit.entries.traverse_ : entry =>
            entry.operation match
              case dev.librecybernetics.noesis.journal.Operation.Assert(_, axiom, _) =>
                IO.println(Render.confirmable(verbalizer, axiom))
              case other => IO.println(s"  ${other.getClass.getSimpleName}")
          _ <- commit.profileWarnings.traverse_((axiom, why) =>
            IO.println(s"  warning: ${axiom.manchester} — $why")
          )
          // Drafting learning items is the learning engine reacting to core events (SPEC §4.1).
          drafted <- workspace.engine.handle(commit.events)
          _ <- IO.whenA(drafted.nonEmpty)(
            IO.println(s"  drafted ${drafted.length} learning item(s)")
          )
        yield ExitCode.Success

  private def print(lines: List[String]): IO[Unit] = lines.traverse_(IO.println)
