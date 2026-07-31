package dev.librecybernetics.noesis.cli.product

import java.util.Locale

import scala.util.matching.Regex

import dev.librecybernetics.noesis.cli.meta.Surface

/** A journey, and the step numbers its table actually defines. */
final case class Journey(id: Int, title: String, steps: Set[Int])

/** One citation from a story's metadata line: `J5` or `J5.2`. */
final case class JourneyRef(journey: Int, step: Option[Int]):
  def render: String = step.fold(s"J$journey")(number => s"J$journey.$number")

/** A story, with the parts the traceability rules require it to carry. */
final case class Story(
    id: Int,
    label: String,
    title: String,
    status: Option[String],
    missingFields: List[String],
    hasAcceptance: Boolean,
    cites: List[JourneyRef]
)

/** An invocation found in a document, kept with the line it appeared on for error messages. */
final case class Invocation(path: List[String], line: Int):
  def render: String = (List("noesis") ++ path).mkString(" ")

/** `PRODUCT.md`, parsed into the parts the rules in [[ProductTrace]] reason about. */
final case class ProductDocument(
    journeys: List[Journey],
    stories: List[Story],
    proposed: Set[List[String]],
    invocations: List[Invocation],
    frictionRows: Set[Int],
    frictionRefs: Set[Int],
    storyRefs: Set[Int]
)

object ProductDocument:

  private val journeyHeading: Regex = """(?m)^###\s+J(\d+)\s+—\s+(.+)$""".r
  private val storyHeading: Regex = """(?m)^###\s+US-(\d+)\s+—\s+(.+)$""".r
  private val stepRow: Regex = """(?m)^\|\s*(\d+)\s*\|""".r
  private val metadataLine: Regex = """(?m)^\*Role:\*.*$""".r
  private val proposedHeading: Regex = """(?m)^##\s+.*Proposed commands.*$""".r
  private val fence: Regex = """(?s)```\n(.*?)```""".r
  private val journeyRef: Regex = """\bJ(\d+)(?:\.(\d+))?\b""".r
  private val frictionRow: Regex = """(?m)^\|\s*\*\*F(\d+)\*\*\s*\|""".r
  private val frictionRef: Regex = """\bF(\d+)\b""".r
  private val storyRef: Regex = """\bUS-(\d+)\b""".r

  /** An invocation in prose or a table cell.
    *
    * The scan stops at the first token that is not a bare lowercase word, so operands mostly fall
    * away on their own and the prefixed name in `noesis assert lia crm:birthday 05-12` contributes
    * `crm` and nothing after it. Whatever survives is resolved against the real command tree by
    * [[ProductTrace.longestKnown]], which is what actually separates commands from their arguments.
    */
  private val invocation: Regex = """\bnoesis((?:[ \t]+[a-z][a-z0-9-]*)+)""".r

  private val requiredFields = List("Journey:", "Spec:", "Status:")
  private val statuses = List("implemented", "partial", "not built")

  /** The bodies that follow each heading, up to the next heading of the same kind. */
  private def sections(text: String, heading: Regex): List[(Regex.Match, String)] =
    val matches = heading.findAllMatchIn(text).toList
    val bounds = matches.map(_.start).drop(1) :+ text.length
    matches.zip(bounds).map((found, end) => (found, text.substring(found.end, end)))

  private def parseJourneys(text: String): List[Journey] =
    sections(text, journeyHeading).map: (found, body) =>
      Journey(
        id = found.group(1).toInt,
        title = found.group(2).trim,
        steps = stepRow.findAllMatchIn(body).map(_.group(1).toInt).toSet
      )

  private def parseStories(text: String): List[Story] =
    sections(text, storyHeading).map: (found, body) =>
      val metadata = metadataLine.findFirstIn(body)
      Story(
        id = found.group(1).toInt,
        label = s"US-${found.group(1)}",
        title = found.group(2).trim,
        status = metadata.flatMap(line => statuses.find(line.toLowerCase(Locale.ROOT).contains)),
        missingFields =
          metadata.fold(requiredFields)(line => requiredFields.filterNot(line.contains)),
        hasAcceptance = body.contains("```"),
        cites = metadata.toList.flatMap: line =>
          journeyRef
            .findAllMatchIn(line)
            .map(ref => JourneyRef(ref.group(1).toInt, Option(ref.group(2)).map(_.toInt)))
            .toList
      )

  /** The fenced block under the "Proposed commands" heading. */
  private def parseProposed(text: String): Set[List[String]] =
    proposedHeading
      .findFirstMatchIn(text)
      .flatMap(found => fence.findFirstMatchIn(text.substring(found.end)))
      .toList
      .flatMap(_.group(1).linesIterator)
      .map(_.trim)
      .filter(_.startsWith("noesis "))
      .map(_.split("\\s+").toList.drop(1))
      .toSet

  def parseInvocations(text: String): List[Invocation] =
    val lineOf = lineIndex(text)
    invocation
      .findAllMatchIn(text)
      .map(found => Invocation(found.group(1).trim.split("\\s+").toList, lineOf(found.start)))
      .toList

  /** 1-based line number for a character offset, so failures point somewhere. */
  private def lineIndex(text: String): Int => Int =
    val breaks = text.zipWithIndex.collect { case ('\n', at) => at }.toVector
    offset => breaks.count(_ < offset) + 1

  def parse(text: String): ProductDocument =
    ProductDocument(
      journeys = parseJourneys(text),
      stories = parseStories(text),
      proposed = parseProposed(text),
      invocations = parseInvocations(text),
      frictionRows = frictionRow.findAllMatchIn(text).map(_.group(1).toInt).toSet,
      frictionRefs = frictionRef.findAllMatchIn(text).map(_.group(1).toInt).toSet,
      storyRefs = storyRef.findAllMatchIn(text).map(_.group(1).toInt).toSet
    )

/** The traceability rules.
  *
  * Each returns the offending items rather than a boolean, so a failing suite names every problem
  * at once instead of the first one. Whether a journey is worth serving is a review question and
  * is deliberately not among them; these check only that the document and the shipped surface
  * describe the same product.
  */
object ProductTrace:

  /** The longest prefix of `path` that names a real command, which discards trailing operands. */
  def longestKnown(path: List[String], known: Set[List[String]]): Option[List[String]] =
    path.inits.find(known.contains)

  private def render(path: List[String]): String = (List("noesis") ++ path).mkString(" ")

  /** Commands the CLI ships that no journey step or acceptance criterion exercises. */
  def uncovered(surface: Surface, document: ProductDocument): List[String] =
    val covered = document.invocations.flatMap(i => longestKnown(i.path, surface.paths)).toSet
    surface.leaves.filterNot(covered.contains).map(render).sorted

  /** Invocations naming neither a real command nor a declared proposal. */
  def unknownInvocations(
      surface: Surface,
      document: ProductDocument,
      source: String,
      invocations: List[Invocation]
  ): List[String] =
    invocations
      .filter: found =>
        longestKnown(found.path, surface.paths).isEmpty &&
          longestKnown(found.path, document.proposed).isEmpty
      .map(found => s"$source:${found.line} invokes `${found.render}`, which is not a command")
      .distinct
      .sorted

  /** Proposals that have since been implemented, so the block cannot rot into a lie. */
  def staleProposals(surface: Surface, document: ProductDocument): List[String] =
    document.proposed.intersect(surface.paths).toList.map(render).sorted

  def citationsToMissingSteps(document: ProductDocument): List[String] =
    val steps = document.journeys.map(journey => journey.id -> journey.steps).toMap
    for
      story <- document.stories
      ref <- story.cites
      failure <- steps.get(ref.journey) match
        case None => Some(s"${story.label} cites ${ref.render}, but no such journey exists")
        case Some(defined) if ref.step.exists(step => !defined.contains(step)) =>
          Some(s"${story.label} cites ${ref.render}, but that journey has no such step")
        case _ => None
    yield failure

  def unclaimedJourneys(document: ProductDocument): List[String] =
    val claimed = document.stories.flatMap(_.cites).map(_.journey).toSet
    document.journeys.filterNot(journey => claimed.contains(journey.id)).map { journey =>
      s"J${journey.id} (${journey.title}) is served by no story"
    }

  def malformedStories(document: ProductDocument): List[String] =
    document.stories.flatMap: story =>
      val missing = story.missingFields.map(field => s"${story.label} is missing `*$field*`")
      val status =
        if story.status.isDefined then Nil
        else List(s"${story.label} states no status among implemented, partial, not built")
      val acceptance =
        if story.hasAcceptance then Nil else List(s"${story.label} has no acceptance criteria block")
      missing ++ status ++ acceptance

  def danglingReferences(document: ProductDocument): List[String] =
    val stories = document.stories.map(_.id).toSet
    val frictions = (document.frictionRefs -- document.frictionRows).toList.sorted
      .map(id => s"F$id is referenced but has no friction-ledger row")
    val missing = (document.storyRefs -- stories).toList.sorted
      .map(id => f"US-$id%02d is referenced but is not defined")
    frictions ++ missing

  /** Help-text conventions from `UX.md` §2 and §3, checked against what decline will print. */
  def helpViolations(surface: Surface): List[String] =
    surface.nodes.flatMap: node =>
      val empty = if node.help.trim.isEmpty then List(s"`${node.name}` has no help text") else Nil
      val capital =
        if node.help.headOption.exists(_.isUpper) then
          List(s"`${node.name}` help starts with a capital: ${node.help}")
        else Nil
      val period =
        if node.help.endsWith(".") then List(s"`${node.name}` help ends with a period: ${node.help}")
        else Nil
      val name =
        if node.name.matches("[a-z][a-z0-9-]*") then Nil
        else List(s"`${node.name}` is not lower-case kebab")
      empty ++ capital ++ period ++ name

  /** Two sibling commands answering to one name would make the tree ambiguous. */
  def duplicateSiblings(surface: Surface): List[String] =
    surface.nodes.flatMap: node =>
      node.children
        .groupBy(_.name)
        .collect { case (name, siblings) if siblings.length > 1 => s"`${node.name}` has ${siblings.length} children named `$name`" }
        .toList
