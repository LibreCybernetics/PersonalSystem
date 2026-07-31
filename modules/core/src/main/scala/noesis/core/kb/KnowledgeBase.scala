package noesis.core.kb

import java.time.LocalDate

import cats.data.NonEmptyList
import cats.effect.std.{Mutex, UUIDGen}
import cats.effect.{Async, Ref}
import cats.syntax.all.*
import noesis.core.capture.{Capture, CaptureProblem, Intent}
import noesis.core.event.{Event, EventBus, Events}
import noesis.core.module.ExportContext
import noesis.journal.{Commit, Journal, Operation}
import noesis.logic.*
import noesis.core.policy.*
import noesis.core.projection.{AxiomRecord, KbState, Projections}
import noesis.reasoner.query.{BasicGraphPattern, Query, Solution}
import noesis.reasoner.*
import noesis.core.verbalize.{Naming, Templates, Verbalizer}

/** Why a commit was refused. Inconsistent commits are rejected *with a justification* (SPEC §3.4). */
enum CommitRejected:
  case Inconsistent(problems: List[Inconsistency])
  case Incomplete(reason: String)
  case Invalid(problems: List[String])
  case NotCaptured(problems: NonEmptyList[CaptureProblem])

  def render: String = this match
    case Inconsistent(problems) =>
      s"commit rejected — inconsistent:\n${problems.map("  " + _.render).mkString("\n")}"
    case Incomplete(reason) =>
      s"commit rejected — reasoning incomplete: $reason"
    case Invalid(problems) =>
      s"commit rejected — invalid:\n${problems.map("  " + _).mkString("\n")}"
    case NotCaptured(problems) =>
      val lines = problems.toList.map(p => s"  ${p.detail}")
      s"commit rejected — cannot capture:\n${lines.mkString("\n")}"

/** The result of a successful commit, including what the owner should be told about it. */
final case class CommitResult(
    commit: Commit,
    events: List[Event],
    /** Axioms that left the EL profile (SPEC §3.1 — a warning, not a rejection). */
    profileWarnings: List[(Axiom, String)]
)

/** Everything needed to configure a knowledge base instance.
  *
  * Modules contribute here — rules, templates, policies — rather than by registering callbacks, so
  * the configuration of a running KB is one inspectable value (SPEC §5.1).
  */
final case class KbConfig(
    rules: List[Rule] = RdfsRules.all,
    policies: PolicyBook = PolicyBook.empty,
    templates: Templates = Templates.empty,
    reasoner: ReasonerConfig = ReasonerConfig.default,
    namingProperties: List[Iri] = Naming.defaultNamingProperties,
    namingSchemes: List[Naming.Scheme] = Nil,
    validators: List[StateValidator] = Nil
):
  def withRules(more: List[Rule]): KbConfig = copy(rules = rules ++ more)
  def withPolicies(more: PolicyBook): KbConfig = copy(policies = policies ++ more)
  def withTemplates(more: Templates): KbConfig = copy(templates = templates ++ more)
  def withNamingSchemes(more: List[Naming.Scheme]): KbConfig =
    copy(namingSchemes = namingSchemes ++ more)
  def withValidators(more: List[StateValidator]): KbConfig =
    copy(validators = validators ++ more)

object KbConfig:
  val default: KbConfig = KbConfig()

/** A reasoning answer that cannot conceal a configured resource limit.
  *
  * Partial values are sound but not complete. Callers must match the case explicitly, so an
  * exhausted closure cannot be mistaken for a definitive negative entailment or an exhaustive
  * query result (DESIGN evidence and uncertainty).
  */
enum ReasoningResult[+A]:
  case Complete(value: A)
  case Incomplete(partial: A, reasons: Set[String])

  def map[B](f: A => B): ReasoningResult[B] = this match
    case Complete(value)              => Complete(f(value))
    case Incomplete(partial, reasons) => Incomplete(f(partial), reasons)

object ReasoningResult:
  private[kb] def fromClosure[A](closure: Closure)(value: => A): ReasoningResult[A] =
    if closure.complete then ReasoningResult.Complete(value)
    else ReasoningResult.Incomplete(value, closure.incompleteReasons)

/** A policy-scoped read capability.
  *
  * Its state, closure, names and justifications were all restricted before construction. Generic
  * service consumers receive this value rather than the owner's raw projections, making query,
  * explanation and rendering share one disclosure boundary (DESIGN Zero Trust).
  */
final class DisclosureView private[kb] (
    val state: KbState,
    val closure: Closure,
    val verbalizer: Verbalizer
):
  def entails(axiom: Axiom): ReasoningResult[Boolean] =
    ReasoningResult.fromClosure(closure)(closure.contains(axiom))

  def explain(axiom: Axiom): ReasoningResult[Option[Explanation]] =
    ReasoningResult.fromClosure(closure)(closure.explain(axiom))

  def query(bgp: BasicGraphPattern): ReasoningResult[List[Solution]] =
    ReasoningResult.fromClosure(closure)(Query.solve(closure, bgp))

/** The Knowledge Core's service surface (SPEC §3.8).
  *
  * Two caches sit in front of the journal — the state fold and the reasoner closure — both
  * invalidated on commit and both rebuildable, exactly as §3.2 describes. The journal remains the
  * only thing that is written; every read goes through a projection.
  */
final class KnowledgeBase[F[_]: {Async, UUIDGen}] private (
    private[noesis] val journal: Journal[F],
    config: KbConfig,
    events: EventBus[F],
    stateCache: Ref[F, Option[KbState]],
    closureCache: Ref[F, Option[Closure]],
    commitLock: Mutex[F]
):

  // ── Projections ───────────────────────────────────────────────────────────

  /** The journal fold, rebuilt on demand and cached until the next commit. */
  private[noesis] def state: F[KbState] =
    stateCache.get.flatMap:
      case Some(cached) => cached.pure[F]
      case None =>
        journal.stream.compile.toList
          .map(KbState.replay)
          .flatTap(fresh => stateCache.set(Some(fresh)))

  /** The current graph: asserted axioms plus ongoing fluents (SPEC §3.6). */
  private[noesis] def currentGraph: F[Graph] = state.map(Projections.current)

  /** The asserted graph, with fluents left un-materialized. */
  private[noesis] def assertedGraph: F[Graph] = state.map(Projections.asserted)

  /** The graph as it stood on `date`, for point-in-time queries. */
  private[noesis] def graphAsOf(date: LocalDate): F[Graph] = state.map(Projections.asOf(_, date))

  /** The state as of a journal sequence — time travel (SPEC §3.2). */
  private[noesis] def stateAt(seq: Long): F[KbState] =
    journal.stream.compile.toList.map(KbState.replayUntil(_, seq))

  /** The materialized closure over the current graph, cached until the next commit. */
  private[noesis] def closure: F[Closure] =
    closureCache.get.flatMap:
      case Some(cached) => cached.pure[F]
      case None =>
        currentGraph
          .map(Reasoner.closure(_, config.rules, config.reasoner))
          .flatTap(fresh => closureCache.set(Some(fresh)))

  // ── Reasoning services (SPEC §3.4) ────────────────────────────────────────

  private[noesis] def entails(axiom: Axiom): F[Boolean] = closure.map(_.contains(axiom))

  private[noesis] def explain(axiom: Axiom): F[Option[Explanation]] =
    closure.map(_.explain(axiom))

  private[noesis] def inconsistencies: F[List[Inconsistency]] = closure.map(Consistency.check)

  private[noesis] def query(bgp: BasicGraphPattern): F[List[Solution]] =
    closure.map(Query.solve(_, bgp))

  // ── Annotations & disclosure ───────────────────────────────────────────────

  private[noesis] def effectiveAnnotations(id: AxiomId): F[Option[EffectiveAnnotations]] =
    state.map(_.axiom(id).map(PolicyCascade.resolve(_, config.policies)))

  /** Filters a set of axioms for an external party (SPEC §3.3.1, §9). */
  private[noesis] def disclosable(
      axioms: Iterable[Axiom],
      policy: DisclosurePolicy
  ): F[(List[(Axiom, EffectiveDisclosure)], List[(Axiom, String)])] =
    for
      current <- state
      cl <- closure
    yield Disclosure.partition(axioms, cl, new SupportResolver(current, config.policies), policy)

  /** The disclosure decision for one axiom, with the reason when it is withheld. */
  private[noesis] def disclosureOf(
      axiom: Axiom,
      policy: DisclosurePolicy
  ): F[DisclosureDecision] =
    for
      current <- state
      cl <- closure
    yield Disclosure.decide(axiom, cl, new SupportResolver(current, config.policies), policy)

  /** Builds the only generic read surface exposed outside the owner implementation. */
  def disclosureView(policy: DisclosurePolicy): F[DisclosureView] =
    (state, closure).mapN: (current, cl) =>
      val context = ExportContext.restricted(
        current,
        cl,
        config.policies,
        policy,
        config.namingProperties,
        config.namingSchemes
      )
      new DisclosureView(
        context.state,
        context.closure,
        new Verbalizer(context.naming, config.templates)
      )

  // ── Verbalization (SPEC §5.2) ─────────────────────────────────────────────

  private[noesis] def verbalizer: F[Verbalizer] =
    state.map: s =>
      new Verbalizer(
        Naming.from(s, config.namingProperties, config.namingSchemes),
        config.templates
      )

  /** A verbalizer whose labels have crossed the same disclosure boundary as its facts.
    *
    * Rendering a permitted relationship with an unfiltered current name would disclose a separate
    * naming fact. Building from [[ExportContext]] keeps that transitive egress inside the
    * least-authority projection (DESIGN data minimization).
    */
  private[noesis] def verbalizer(policy: DisclosurePolicy): F[Verbalizer] =
    (state, closure).mapN: (current, cl) =>
      val context = ExportContext.restricted(
        current,
        cl,
        config.policies,
        policy,
        config.namingProperties,
        config.namingSchemes
      )
      new Verbalizer(context.naming, config.templates)

  private[noesis] def verbalize(axiom: Axiom): F[String] =
    verbalizer.map(_.verbalize(axiom))

  // ── Commit (SPEC §3.5.6) ──────────────────────────────────────────────────

  /** Plans, validates and commits a bundle of intents atomically.
    *
    * The order matters and is the spec's: plan → consistency pre-flight on a *scratch* projection →
    * append → invalidate → emit. Nothing reaches the journal until the reasoner has accepted it, so
    * the journal never records a state the system considers impossible.
    */
  def commit(intents: NonEmptyList[Intent]): F[Either[CommitRejected, CommitResult]] =
    commitLock.lock.surround(commitAttempt(intents))

  private def commitAttempt(
      intents: NonEmptyList[Intent]
  ): F[Either[CommitRejected, CommitResult]] =
    for
      before <- state
      planned <- Capture.plan[F](before, intents)
      result <- planned match
        case Left(problems) =>
          CommitRejected.NotCaptured(problems).asLeft[CommitResult].pure[F]
        case Right(Nil) =>
          // Everything was already true. §3.5.4's redundancy check offers to skip rather than
          // writing a no-op, so the journal stays free of entries that changed nothing.
          CommitResult(Commit(Nil), Nil, Nil).asRight[CommitRejected].pure[F]
        case Right(operations) =>
          commitOperations(before, operations).flatMap:
            case Some(result) => result.pure[F]
            case None =>
              // Another process committed after this attempt read `before`. Its facts may change
              // capture, consistency or a module validator, so rebuild and repeat the whole
              // pre-flight instead of merely assigning later sequence numbers.
              invalidate *> commitAttempt(intents)
    yield result

  private def commitOperations(
      before: KbState,
      operations: List[Operation]
  ): F[Option[Either[CommitRejected, CommitResult]]] =
    NonEmptyList.fromList(operations) match
      case None =>
        Some(CommitResult(Commit(Nil), Nil, Nil).asRight[CommitRejected]).pure[F]

      case Some(ops) =>
        val scratch = ops.foldLeft(before): (s, op) =>
          KbState.step(s, noesis.journal.JournalEntry(s.seq + 1, java.time.Instant.EPOCH, op))
        val scratchClosure =
          Reasoner.closure(Projections.current(scratch), config.rules, config.reasoner)

        if !scratchClosure.complete then
          Some(
            CommitRejected
              .Incomplete(
                scratchClosure.incompleteReasons.toList.sorted.mkString(", ")
              )
              .asLeft[CommitResult]
          ).pure[F]
        else Consistency.check(scratchClosure) match
          case problems if problems.nonEmpty =>
            Some(CommitRejected.Inconsistent(problems).asLeft[CommitResult]).pure[F]

          case _ =>
            val validationProblems =
              CoreValidation.validate(scratch, config.policies) ++
                config.validators.flatMap(validator =>
                  validator.validate(scratch, scratchClosure).map(problem =>
                    s"${validator.name}: $problem"
                  )
                )
            if validationProblems.nonEmpty then
              Some(CommitRejected.Invalid(validationProblems).asLeft[CommitResult]).pure[F]
            else
              for
                beforeClosure <- closure
                appended <- journal.appendIfCurrent(before.seq, ops)
                result <- appended match
                  case None => none[Either[CommitRejected, CommitResult]].pure[F]
                  case Some(commit) =>
                    for
                      _ <- invalidate
                      emitted = eventsFor(before, ops, beforeClosure, scratchClosure)
                      _ <- events.publish(emitted)
                      warnings = Profile.warnings(ops.toList.collect {
                        case Operation.Assert(_, axiom, _) => axiom
                      })
                    yield Some(CommitResult(commit, emitted, warnings).asRight)
              yield result

  /** Convenience for the overwhelmingly common single-assertion case. */
  def assert(axiom: Axiom, annotations: AxiomAnnotations = AxiomAnnotations.ownerConfirmed)
      : F[Either[CommitRejected, CommitResult]] =
    commit(NonEmptyList.one(Intent.Assert(axiom, annotations)))

  private def invalidate: F[Unit] =
    stateCache.set(None) *> closureCache.set(None)

  /** The event stream for a commit (SPEC §2).
    *
    * Axiom and state events are derived by [[Events]], the same function that rebuilds projections
    * from the journal — so a subscriber rebuilding after a restart sees exactly the events it would
    * have seen live. The entailment diff is added here, since only the commit path has both closures.
    */
  private def eventsFor(
      before: KbState,
      operations: NonEmptyList[Operation],
      beforeClosure: Closure,
      afterClosure: Closure
  ): List[Event] =
    val added = afterClosure.axioms -- beforeClosure.axioms
    val removed = beforeClosure.axioms -- afterClosure.axioms
    val entailmentEvents =
      if added.isEmpty && removed.isEmpty then Nil
      else List(Event.EntailmentChanged(added, removed))

    Events.forOperations(before, operations.toList) ++ entailmentEvents

  /** Records that must be re-examined by the reclassification queue (SPEC §12.8). */
  private[noesis] def policyViolations: F[List[String]] =
    state.map(s => s.activeAxioms.toList.flatMap(PolicyCascade.validate(_, config.policies)))

  private[noesis] def records: F[List[AxiomRecord]] = state.map(_.activeAxioms.toList)

object KnowledgeBase:
  /** A knowledge base with no event subscribers — the one-shot CLI and test case. */
  def apply[F[_]: {Async, UUIDGen}](
      journal: Journal[F],
      config: KbConfig = KbConfig.default
  ): F[KnowledgeBase[F]] =
    withEvents(journal, config, EventBus.noop[F])

  def withEvents[F[_]: {Async, UUIDGen}](
      journal: Journal[F],
      config: KbConfig,
      events: EventBus[F]
  ): F[KnowledgeBase[F]] =
    for
      stateCache <- Ref.of[F, Option[KbState]](None)
      closureCache <- Ref.of[F, Option[Closure]](None)
      commitLock <- Mutex[F]
    yield new KnowledgeBase[F](journal, config, events, stateCache, closureCache, commitLock)
