package dev.librecybernetics.noesis.cli

import java.time.LocalDate

import com.monovore.decline.{Command as DeclineCommand}
import munit.FunSuite

import dev.librecybernetics.noesis.cli.meta.CommandSurface

/** Runtime evidence that every command derived from the typed AST is actually parseable. */
class CommandParserSuite extends FunSuite:
  private val today = LocalDate.of(2026, 8, 5)
  private val parser = DeclineCommand("noesis", "test", false)(Main.commandInput(today))
  private val surface = CommandSurface.ofModule("dev.librecybernetics.noesis.cli.Main", "commandInput")

  private val examples: List[(List[String], List[String])] = List(
    List("init") -> List("init"),
    List("assert") -> List("assert", "marco", "rdf:type", "crm:Person", "--yes"),
    List("retract") -> List("retract", "ax_1"),
    List("close") -> List("close", "marco", "crm:worksAt"),
    List("supersede") -> List("supersede", "marco", "crm:worksAt", "acme"),
    List("show") -> List("show", "marco"),
    List("query") -> List("query", "?s rdf:type crm:Person"),
    List("entails") -> List("entails", "marco", "rdf:type", "crm:Person"),
    List("explain") -> List("explain", "marco", "rdf:type", "crm:Person"),
    List("check") -> List("check"),
    List("journal") -> List("journal", "--limit", "5"),
    List("vocab", "search") -> List("vocab", "search", "birthday"),
    List("vocab", "show") -> List("vocab", "show", "crm:birthday"),
    List("agenda") -> List("agenda"),
    List("queue") -> List("queue", "--mode", "retention", "--limit", "2"),
    List("quiz") -> List("quiz", "--mode", "elucidation", "--limit", "2"),
    List("review") -> List("review", "item-1", "1.0", "--latency", "250"),
    List("items") -> List("items"),
    List("disclose") -> List("disclose", "assistant", "--level", "internal", "--scope", "work"),
    List("loans") -> List("loans"),
    List("export") -> List("export"),
    List("as-of") -> List("as-of", "2026-08-01"),
    List("contact", "add") -> List("contact", "add", "Marco", "--organization"),
    List("contact", "method-add") ->
      List("contact", "method-add", "marco", "m@example.test", "--kind", "email"),
    List("contact", "address-add") ->
      List("contact", "address-add", "marco", "1 Main St", "--country", "MX"),
    List("contact", "method-retire") -> List("contact", "method-retire", "method-1"),
    List("contact", "employment-add") ->
      List("contact", "employment-add", "marco", "--at", "acme"),
    List("contact", "interaction-add") ->
      List("contact", "interaction-add", "marco", "--on", "2026-08-01", "--channel", "phone"),
    List("contact", "relationship-add") ->
      List("contact", "relationship-add", "marco", "lia", "--kind", "friend"),
    List("contact", "note-add") -> List("contact", "note-add", "marco", "Met at work"),
    List("contact", "preference-add") ->
      List("contact", "preference-add", "marco", "likes", "coffee"),
    List("contact", "follow-up-set") ->
      List("contact", "follow-up-set", "marco", "--every", "30"),
    List("contact", "reminder-add") ->
      List("contact", "reminder-add", "marco", "--due", "08-05", "--occasion", "birthday"),
    List("contact", "companion-add") -> List("contact", "companion-add", "marco", "Pico"),
    List("contact", "circle-add") -> List("contact", "circle-add", "friends", "marco"),
    List("contact", "gift-add") -> List("contact", "gift-add", "marco", "book"),
    List("contact", "show") -> List("contact", "show", "marco"),
    List("contact", "due") -> List("contact", "due"),
    List("contact", "import") ->
      List("contact", "import", "contacts.vcf", "--format", "vcard", "--dry-run"),
    List("contact", "export") ->
      List("contact", "export", "marco", "--format", "vcard", "--include-contact-data"),
    List("archive", "create") -> List("archive", "create", "/tmp/archive"),
    List("archive", "verify") -> List("archive", "verify", "/tmp/archive"),
    List("archive", "restore") -> List("archive", "restore", "/tmp/archive", "/tmp/restored"),
    List("note", "today") -> List("note", "today"),
    List("note", "new") -> List("note", "new", "Design notes", "--literature"),
    List("note", "append") -> List("note", "append", "one thought"),
    List("note", "edit") -> List("note", "edit"),
    List("note", "show") -> List("note", "show"),
    List("note", "list") -> List("note", "list"),
    List("note", "history") -> List("note", "history", "block-1"),
    List("backlinks") -> List("backlinks", "marco"),
    List("search") -> List("search", "Marco")
  )

  test("one canonical argv parses for every shipped command leaf"):
    val parsed = examples.map: entry =>
      val (path, argv) = entry
      parser.parse(argv) match
        case Left(help) => fail(s"${path.mkString(" ")} did not parse:\n$help")
        case Right(_)   => path
    assertEquals(parsed.toSet, surface.leaves.toSet)
    assertEquals(parsed.length, surface.leaves.length)

  test("the injected date owns both agenda defaults"):
    val agenda = parser.parse(List("agenda")).map(_._3)
    val contactDue = parser.parse(List("contact", "due")).map(_._3)
    assertEquals(agenda, Right(Command.Agenda(today)))
    assertEquals(contactDue, Right(Command.Contact(ContactCommand.Due(today))))

  test("the shipped entry point composes parsing with command execution"):
    val entryPoint = DeclineCommand("noesis", "test", false)(Main.main)
    entryPoint.parse(List("check")) match
      case Left(help) => fail(s"entry point did not parse:\n$help")
      case Right(_)   => ()

  test("boundary parsers return exact actionable rejections"):
    def rejection(args: List[String]): String = parser.parse(args) match
      case Left(help) => help.toString
      case Right(value) => fail(s"unexpectedly parsed: $value")
    val badMode = rejection(List("queue", "--mode", "random"))
    val badDate = rejection(List("agenda", "--on", "tomorrow"))
    val badDays = rejection(List("contact", "follow-up-set", "marco", "--every", "0"))
    assert(badMode.contains("unknown queue mode: random"), badMode)
    assert(badDate.contains("not a date:"), badDate)
    assert(badDays.contains("positive"), badDays)
