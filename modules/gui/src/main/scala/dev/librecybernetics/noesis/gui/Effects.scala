package dev.librecybernetics.noesis.gui

import java.time.LocalDate
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream

import dev.librecybernetics.noesis.app.*

/** The exact owner use cases the desktop can request.
  *
  * Keeping this port narrower than [[OwnerSession]] makes orchestration deterministic without
  * replacing the real journal/filesystem boundary in integration tests (DESIGN, Effect boundaries).
  */
// Abstract signatures have no executable body; keep the changed-line gate on their live interpreter.
// $COVERAGE-OFF$
private[gui] trait OwnerActions:
  def initialized: IO[Boolean]
  def initialize: IO[Either[OwnerProblem, List[String]]]
  def agenda(on: LocalDate): IO[List[AgendaView]]
  def appendToday(text: String, on: LocalDate): IO[Either[OwnerProblem, CommitOutcome]]
  def prepareFact(input: FactInput): IO[Either[OwnerProblem, CommitPreview]]
  def commit(preview: CommitPreview): IO[Either[OwnerProblem, CommitOutcome]]
  def search(query: String): IO[List[SearchHit]]
  def entity(iri: dev.librecybernetics.noesis.logic.Iri): IO[EntityView]
  def queue(limit: Int): IO[List[dev.librecybernetics.noesis.lms.QueueEntry]]
  def question(entry: dev.librecybernetics.noesis.lms.QueueEntry): IO[ReviewPrompt]
  def answer(
      question: dev.librecybernetics.noesis.lms.Question,
      response: String,
      latencyMs: Long
  ): IO[Either[OwnerProblem, dev.librecybernetics.noesis.lms.ReviewOutcome]]
  def changes: Stream[IO, SessionPosition]
// $COVERAGE-ON$

private[gui] object OwnerActions:
  def live(session: OwnerSession): OwnerActions = new OwnerActions:
    def initialized = session.initialized
    def initialize = session.initialize
    def agenda(on: LocalDate) = session.agenda(on)
    def appendToday(text: String, on: LocalDate) = session.appendToday(text, on)
    def prepareFact(input: FactInput) = session.prepareFact(input)
    def commit(preview: CommitPreview) = session.commit(preview)
    def search(query: String) = session.search(query)
    def entity(iri: dev.librecybernetics.noesis.logic.Iri) = session.entity(iri)
    def queue(limit: Int) = session.queue(limit = limit)
    def question(entry: dev.librecybernetics.noesis.lms.QueueEntry) = session.question(entry)
    def answer(
        question: dev.librecybernetics.noesis.lms.Question,
        response: String,
        latencyMs: Long
    ) = session.answer(question, response, latencyMs)
    def changes = session.changes

private[gui] trait GuiClock:
  def today: IO[LocalDate]
  def monotonic: IO[FiniteDuration]

private[gui] object GuiClock:
  val live: GuiClock = new GuiClock:
    def today: IO[LocalDate] =
      IO.realTimeInstant.map(_.atZone(java.time.ZoneId.systemDefault()).toLocalDate)
    def monotonic: IO[FiniteDuration] = IO.monotonic

/** Interprets reducer effects against narrow owner and time capabilities (DESIGN, Desktop MVU). */
final class Effects private[gui] (actions: OwnerActions, clock: GuiClock):
  def run(effect: Effect): IO[Event] =
    effect match
      case Effect.CheckInitialization =>
        recover("workspace not opened", "check that the workspace directory is readable")(
          actions.initialized
        ).map:
          case Right(initialized) => Event.InitializationKnown(initialized)
          case Left(problem)      => Event.Initialized(Left(problem))

      case Effect.Initialize =>
        actions.initialize.attempt.map:
          case Right(result) => Event.Initialized(result)
          case Left(cause) =>
            Event.Initialized(
              Left(
                problem(
                  "workspace not initialized",
                  cause,
                  "check the directory permissions and try again"
                )
              )
            )

      case Effect.LoadAgenda =>
        clock.today.flatMap: today =>
          recover("agenda not loaded", "check the journal, then retry")(
            actions.agenda(today)
          ).map(Event.AgendaLoaded.apply)

      case Effect.SaveNote(text) =>
        clock.today.flatMap: today =>
          actions.appendToday(text, today).attempt.map:
            case Right(result) => Event.NoteSaved(result)
            case Left(cause)   => Event.NoteSaved(Left(problem("note not saved", cause, "retry the save")))

      case Effect.PrepareFact(draft) =>
        validate(draft) match
          case Left(problem) => IO.pure(Event.FactPrepared(Left(problem)))
          case Right(input) =>
            actions.prepareFact(input).attempt.map:
              case Right(result) => Event.FactPrepared(result)
              case Left(cause) =>
                Event.FactPrepared(Left(problem("fact not prepared", cause, "check the values")))

      case Effect.CommitFact(preview) =>
        actions.commit(preview).attempt.map:
          case Right(result) => Event.FactCommitted(result)
          case Left(cause) =>
            Event.FactCommitted(Left(problem("fact not committed", cause, "review and retry")))

      case Effect.Search(query) =>
        recover("search not completed", "change the query or inspect the journal")(
          actions.search(query)
        ).map(Event.SearchLoaded.apply)

      case Effect.LoadEntity(iri) =>
        recover("entity not loaded", "check the identifier or return to search")(
          actions.entity(iri)
        ).map(Event.EntityLoaded.apply)

      case Effect.LoadReview =>
        val load = for
          entries <- actions.queue(limit = 1)
          prompt <- entries.headOption.traverse(actions.question)
          now <- clock.monotonic
        yield prompt.map(ReviewState(_, now))
        recover("learning queue not loaded", "inspect the journal, then retry")(load)
          .map(Event.ReviewLoaded.apply)

      case Effect.RecordAnswer(review, response) =>
        review.prompt.question match
          case None =>
            IO.pure(
              Event.AnswerRecorded(
                Left(
                  OwnerProblem(
                    "answer not recorded",
                    "the queued item has no usable question",
                    "refresh the queue or use the CLI to inspect the item"
                  )
                )
              )
            )
          case Some(question) =>
            clock.monotonic.flatMap: finished =>
              val latency = (finished - review.askedAt).toMillis.max(0L)
              actions.answer(question, response, latency).attempt.map:
                case Right(Right(outcome)) =>
                  val verdict = if outcome.review.grade >= 1.0 then "Correct" else "Not yet"
                  Event.AnswerRecorded(Right(verdict))
                case Right(Left(problem)) => Event.AnswerRecorded(Left(problem))
                case Left(cause) =>
                  Event.AnswerRecorded(
                    Left(problem("answer not recorded", cause, "retry or skip this item"))
                  )

  private def validate(draft: FactDraft): Either[OwnerProblem, FactInput] =
    val subject = draft.subject.trim
    val property = draft.property.trim
    val value = draft.value.trim
    if subject.isEmpty then
      Left(OwnerProblem("fact not prepared", "the subject is blank", "enter an identifier"))
    else if property.isEmpty then
      Left(OwnerProblem("fact not prepared", "the property is blank", "choose a vocabulary term"))
    else if value.isEmpty then
      Left(OwnerProblem("fact not prepared", "the value is blank", "enter a value"))
    else
      val subjectIri = Workspace.iri(subject)
      val choice =
        if draft.createSubject then EntityChoice.New(subjectIri, subject)
        else EntityChoice.Existing(subjectIri)
      Right(FactInput(choice, Workspace.iri(property), value))

  private def recover[A](what: String, next: String)(io: IO[A]): IO[Either[OwnerProblem, A]] =
    io.attempt.map(_.leftMap(problem(what, _, next)))

  private def problem(what: String, cause: Throwable, next: String): OwnerProblem =
    val why = Option(cause.getMessage).filter(_.nonEmpty).getOrElse(cause.getClass.getSimpleName)
    OwnerProblem(what, why, next)

object Effects:
  private[gui] def live(session: OwnerSession): Effects =
    Effects(OwnerActions.live(session), GuiClock.live)
