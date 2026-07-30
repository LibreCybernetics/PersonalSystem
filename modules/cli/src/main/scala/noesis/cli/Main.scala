package noesis.cli

import java.util.Locale

import cats.data.{NonEmptyList, Validated}
import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.{Argument, Opts}
import fs2.io.file.Path
import noesis.core.capture.Intent
import noesis.core.kb.CommitResult
import noesis.core.model.*
import noesis.core.policy.DisclosurePolicy
import noesis.core.query.Query
import noesis.core.reason.Profile
import noesis.lms.{ItemId, QueueMode}
import noesis.vocab.{CoreModule, Ledger}

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
      confidence: Option[Double]
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

object Main
    extends CommandIOApp(
      name = "noesis",
      header = "Noesis — a single-user knowledge & learning system on a formal knowledge base",
      version = "0.1.0"
    ):

  // ── Argument parsers ──────────────────────────────────────────────────────

  private given Argument[Sensitivity] = Argument.from("public|internal|personal|sensitive"): raw =>
    Sensitivity.parse(raw).fold(err => Validated.invalidNel(err), Validated.valid)

  private given Argument[PartialDate] = Argument.from("date"): raw =>
    PartialDate.parse(raw).fold(err => Validated.invalidNel(err), Validated.valid)

  private given Argument[QueueMode] = Argument.from("retention|elucidation|mixed"): raw =>
    QueueMode.values
      .find(_.toString.equalsIgnoreCase(raw))
      .fold(Validated.invalidNel(s"unknown queue mode: $raw"))(Validated.valid)

  private given Argument[java.time.LocalDate] = Argument.from("yyyy-mm-dd"): raw =>
    Either
      .catchNonFatal(java.time.LocalDate.parse(raw))
      .fold(e => Validated.invalidNel(s"not a date: ${e.getMessage}"), Validated.valid)

  private val rootOpt: Opts[Path] = Opts
    .option[String]("root", "workspace directory (default ~/.noesis)")
    .map(Path(_))
    .withDefault(Workspace.defaultRoot)

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
      Opts.option[Double]("confidence", "truth confidence [0,1]; defaults to 1.0").orNone
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

  def main: Opts[IO[ExitCode]] =
    (
      rootOpt,
      init orElse assertCmd orElse retract orElse closeState orElse supersede orElse
        show orElse query orElse entails orElse explain orElse check orElse journal orElse
        queue orElse answer orElse items orElse disclose orElse loans orElse exportCmd orElse asOf
    ).mapN(run)

  // ── Execution ─────────────────────────────────────────────────────────────

  private def run(root: Path, command: Command): IO[ExitCode] =
    Workspace.open(root).flatMap(execute(_, command))

  private def execute(workspace: Workspace, command: Command): IO[ExitCode] =
    val kb = workspace.kb
    val engine = workspace.engine

    command match
      case Command.Init =>
        Workspace.install(workspace).flatMap(lines => print(lines).as(ExitCode.Success))

      case Command.Assert(subject, property, value, sensitivity, scope, utility, confidence) =>
        val annotations = AxiomAnnotations(
          truthConfidence = confidence.orElse(Some(1.0)),
          sensitivity = sensitivity,
          knowledgeScope = scope.map(Workspace.iri).toSet,
          recallUtility = utility
        )
        for
          axiom <- buildAssertion(workspace, subject, property, value)
          result <- kb.commit(NonEmptyList.one(Intent.Assert(axiom, annotations)))
          code <- reportCommit(workspace, result)
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
        Query.parse(pattern) match
          case Left(err) => IO.println(s"bad pattern: $err").as(ExitCode.Error)
          case Right(bgp) =>
            for
              solutions <- kb.query(bgp)
              verbalizer <- kb.verbalizer
              order = bgp.variables.toList.sorted
              _ <-
                if solutions.isEmpty then IO.println("no solutions")
                else
                  solutions.distinct.traverse_ : solution =>
                    val cells = order.map: variable =>
                      val rendered = solution
                        .get(variable)
                        .map:
                          case Node.Ref(iri) => verbalizer.label(iri)
                          case Node.Lit(lit) => lit.text
                        .getOrElse("-")
                      s"?$variable=$rendered"
                    IO.println("  " + cells.mkString("  "))
            yield ExitCode.Success

      case Command.Entails(subject, property, value) =>
        for
          axiom <- buildAssertion(workspace, subject, property, value)
          entailed <- kb.entails(axiom)
          verbalizer <- kb.verbalizer
          _ <- IO.println(
            if entailed then s"yes — ${verbalizer.verbalize(axiom)}"
            else s"no — ${verbalizer.verbalize(axiom)} is not entailed"
          )
        yield if entailed then ExitCode.Success else ExitCode(1)

      case Command.Explain(subject, property, value) =>
        for
          axiom <- buildAssertion(workspace, subject, property, value)
          explanation <- kb.explain(axiom)
          verbalizer <- kb.verbalizer
          state <- kb.state
          _ <- explanation match
            case None => IO.println("not entailed; nothing to explain")
            case Some(found) =>
              IO.println(s"${verbalizer.verbalize(axiom)}") *>
                IO.println(
                  if found.isAsserted then "  asserted directly"
                  else s"  derived, ${found.justifications.size} justification(s):"
                ) *>
                found.justifications.toList.sorted.zipWithIndex.traverse_ : (justification, i) =>
                  IO.println(s"  ${i + 1}. because:") *>
                    justification.premises.toList.sorted.traverse_ : premise =>
                      val described = premise match
                        case Support.Asserted(id) =>
                          state.axiom(id).map(r => verbalizer.verbalize(r.axiom)).getOrElse(id.value)
                        case Support.FromFluent(id) =>
                          state.fluent(id).map(verbalizer.verbalize).getOrElse(id.value)
                      IO.println(s"       - $described")
        yield ExitCode.Success

      case Command.Check =>
        for
          problems <- kb.inconsistencies
          violations <- kb.policyViolations
          records <- kb.records
          warnings = Profile.warnings(records.map(_.axiom))
          _ <- IO.println(s"axioms: ${records.length}")
          _ <-
            if problems.isEmpty then IO.println("consistency: ok")
            else IO.println("consistency: FAILED") *> print(problems.map("  " + _.render))
          _ <-
            if violations.isEmpty then IO.println("annotation policies: ok")
            else IO.println("annotation policies:") *> print(violations.map("  " + _))
          _ <-
            if warnings.isEmpty then IO.println("OWL 2 EL profile: ok")
            else
              IO.println(s"OWL 2 EL profile: ${warnings.length} axiom(s) outside EL") *>
                print(warnings.map((a, why) => s"  ${a.manchester} — $why"))
        yield if problems.isEmpty then ExitCode.Success else ExitCode(1)

      case Command.Journal(limit) =>
        for
          entries <- kb.journal.stream.compile.toList
          shown = limit.fold(entries)(n => entries.takeRight(n))
          _ <- shown.traverse_ : entry =>
            IO.println(f"${entry.seq}%5d  ${entry.at}  ${entry.operation.getClass.getSimpleName}")
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
        engine.review(ItemId.unsafe(item), grade, latency).flatMap {
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
          verbalizer <- kb.verbalizer
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
                noesis.core.policy.DisclosureDecision.Disclose(effective)
              )
            )
          _ <- IO.whenA(redacted.nonEmpty)(
            IO.println(s"  ... and ${redacted.length} withheld:") *>
              redacted.traverse_((_, reason) => IO.println(s"  ✗ [redacted] — $reason"))
          )
        yield ExitCode.Success

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

  private def reportCommit(
      workspace: Workspace,
      result: Either[noesis.core.kb.CommitRejected, CommitResult]
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
              case noesis.core.journal.Operation.Assert(_, axiom, _) =>
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
