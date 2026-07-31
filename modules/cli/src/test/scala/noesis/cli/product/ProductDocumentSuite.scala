package noesis.cli.product

import munit.FunSuite

import noesis.cli.meta.{CommandNode, Surface}

/** The traceability rules, exercised against fixtures rather than against the live documents.
  *
  * `ProductTraceSuite` asserts that this repository is currently consistent; these cases assert
  * that the rules would notice if it stopped being.
  */
class ProductDocumentSuite extends FunSuite:

  private val surface = Surface(
    List(
      CommandNode("init", "create the workspace", Nil),
      CommandNode("check", "check consistency", Nil),
      CommandNode(
        "archive",
        "create or verify a portable archive",
        List(
          CommandNode("create", "capture the journal into an archive-directory", Nil),
          CommandNode("verify", "verify an archive", Nil)
        )
      )
    ),
    Nil
  )

  private val markdown =
    """### J1 — The first hour
      |
      || # | Command |
      ||---|---|
      || 1 | `noesis init` |
      || 2 | `noesis check` |
      |
      |### J2 — Leaving
      |
      || # | Command |
      ||---|---|
      || 1 | `noesis archive create /tmp/a` |
      || 2 | `noesis archive verify /tmp/a` |
      |
      |### US-01 — Create a workspace
      |
      |*Role:* Exiter · *Journey:* J1.1 · *Spec:* §10 · *Status:* implemented
      |
      |```
      |When   noesis init
      |Then   the directory is 0700
      |```
      |
      |### US-02 — Leave
      |
      |*Role:* Exiter · *Journey:* J2.1, J2.2 · *Spec:* §10 · *Status:* implemented
      |
      |```
      |When   noesis archive create /tmp/a
      |```
      |
      |## 8. Proposed commands (not implemented)
      |
      |```
      |noesis undo
      |```
      |""".stripMargin

  private val document = ProductDocument.parse(markdown)

  test("journeys carry the step numbers their tables define") {
    assertEquals(document.journeys.map(_.id), List(1, 2))
    assertEquals(document.journeys.map(_.steps), List(Set(1, 2), Set(1, 2)))
    assertEquals(document.journeys.map(_.title), List("The first hour", "Leaving"))
  }

  test("stories carry their status, citations and acceptance block") {
    val leaving = document.stories.filter(_.label == "US-02")
    assertEquals(leaving.map(_.status), List(Some("implemented")))
    assertEquals(leaving.flatMap(_.cites).map(_.render), List("J2.1", "J2.2"))
    assertEquals(leaving.map(_.hasAcceptance), List(true))
    assertEquals(leaving.flatMap(_.missingFields), Nil)
  }

  test("an invocation keeps its operands and resolution discards them") {
    val found = ProductDocument.parseInvocations("run `noesis assert lia crm:birthday 05-12` now")
    assertEquals(found.map(_.path), List(List("assert", "lia", "crm")))
    assertEquals(
      ProductTrace.longestKnown(List("assert", "lia", "crm"), Set(List("assert"))),
      Some(List("assert"))
    )
  }

  test("a prefixed name is not an invocation") {
    assertEquals(ProductDocument.parseInvocations("the entity noesis:e/lia exists"), Nil)
  }

  test("the longest known prefix wins, so a container never shadows its subcommand") {
    val known = Set(List("contact"), List("contact", "show"))
    assertEquals(ProductTrace.longestKnown(List("contact", "show", "lia"), known), Some(List("contact", "show")))
    assertEquals(ProductTrace.longestKnown(List("vocab", "search"), known), None)
  }

  test("a consistent document raises nothing") {
    assertEquals(ProductTrace.uncovered(surface, document), Nil)
    assertEquals(ProductTrace.unknownInvocations(surface, document, "fixture", document.invocations), Nil)
    assertEquals(ProductTrace.staleProposals(surface, document), Nil)
    assertEquals(ProductTrace.citationsToMissingSteps(document), Nil)
    assertEquals(ProductTrace.unclaimedJourneys(document), Nil)
    assertEquals(ProductTrace.malformedStories(document), Nil)
    assertEquals(ProductTrace.danglingReferences(document), Nil)
  }

  test("a command in no journey is reported") {
    val trimmed = ProductDocument.parse(markdown.replace("| 2 | `noesis check` |", "| 2 | nothing |"))
    assertEquals(ProductTrace.uncovered(surface, trimmed), List("noesis check"))
  }

  test("an invented command is reported, with the line it is on") {
    val invented = ProductDocument.parse(markdown + "\nrun `noesis reticulate splines` first\n")
    val failures = ProductTrace.unknownInvocations(surface, invented, "fixture", invented.invocations)
    assertEquals(failures.length, 1)
    assert(failures.exists(_.contains("`noesis reticulate splines`, which is not a command")))
  }

  test("a proposed command is allowed until it ships, and reported once it has") {
    val proposing = ProductDocument.parse(markdown + "\ntry `noesis undo` next\n")
    assertEquals(ProductTrace.unknownInvocations(surface, proposing, "fixture", proposing.invocations), Nil)

    val shipped = ProductDocument.parse(markdown.replace("noesis undo", "noesis check"))
    assertEquals(ProductTrace.staleProposals(surface, shipped), List("noesis check"))
  }

  test("a citation to a step that does not exist is reported") {
    val wrong = ProductDocument.parse(markdown.replace("*Journey:* J1.1", "*Journey:* J1.9"))
    assertEquals(
      ProductTrace.citationsToMissingSteps(wrong),
      List("US-01 cites J1.9, but that journey has no such step")
    )
  }

  test("a journey no story claims is reported") {
    val orphaned = ProductDocument.parse(markdown.replace("*Journey:* J2.1, J2.2", "*Journey:* J1.2"))
    assertEquals(ProductTrace.unclaimedJourneys(orphaned), List("J2 (Leaving) is served by no story"))
  }

  test("a story missing its status, fields or acceptance criteria is reported") {
    val stripped = ProductDocument.parse(
      markdown
        .replace("*Spec:* §10 · *Status:* implemented\n\n```\nWhen   noesis init\nThen   the directory is 0700\n```", "")
    )
    val failures = ProductTrace.malformedStories(stripped)
    assert(failures.exists(_.contains("US-01 is missing `*Spec:*`")), failures.toString)
    assert(failures.exists(_.contains("US-01 states no status")), failures.toString)
    assert(failures.exists(_.contains("US-01 has no acceptance criteria block")), failures.toString)
  }

  test("a reference to an undefined friction or story is reported") {
    val dangling = ProductDocument.parse(markdown + "\nthis is friction F7, tracked by US-99.\n")
    assertEquals(
      ProductTrace.danglingReferences(dangling),
      List(
        "F7 is referenced but has no friction-ledger row",
        "US-99 is referenced but is not defined"
      )
    )
  }

  test("a friction with a ledger row is not reported") {
    val ledgered = ProductDocument.parse(markdown + "\n| **F7** | something | J1.1 | a cause | Open |\n")
    assertEquals(ProductTrace.danglingReferences(ledgered), Nil)
  }

  test("help text that breaks the UX.md conventions is reported") {
    val shouting = Surface(List(CommandNode("init", "Create the workspace.", Nil)), Nil)
    assertEquals(
      ProductTrace.helpViolations(shouting),
      List(
        "`init` help starts with a capital: Create the workspace.",
        "`init` help ends with a period: Create the workspace."
      )
    )
    assertEquals(ProductTrace.helpViolations(surface), Nil)
  }

  test("two sibling commands sharing a name are reported") {
    val ambiguous = Surface(
      List(
        CommandNode(
          "contact",
          "personal relationship management",
          List(CommandNode("show", "a", Nil), CommandNode("show", "b", Nil))
        )
      ),
      Nil
    )
    assertEquals(ProductTrace.duplicateSiblings(ambiguous), List("`contact` has 2 children named `show`"))
    assertEquals(ProductTrace.duplicateSiblings(surface), Nil)
  }
