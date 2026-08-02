package dev.librecybernetics.noesis.gui

import java.time.LocalDate

import cats.effect.IO
import cats.syntax.all.*

import dev.librecybernetics.noesis.app.*

/** Interprets reducer effects against the shared owner-session boundary (DESIGN, Desktop MVU). */
final class Effects(session: OwnerSession):
  def run(effect: Effect): IO[Event] =
    effect match
      case Effect.CheckInitialization =>
        recover("workspace not opened", "check that the workspace directory is readable")(
          session.initialized
        ).map:
          case Right(initialized) => Event.InitializationKnown(initialized)
          case Left(problem)      => Event.Initialized(Left(problem))

      case Effect.Initialize =>
        session.initialize.attempt.map:
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
        recover("agenda not loaded", "check the journal, then retry")(
          session.agenda(LocalDate.now())
        ).map(Event.AgendaLoaded.apply)

      case Effect.SaveNote(text) =>
        session.appendToday(text, LocalDate.now()).attempt.map:
          case Right(result) => Event.NoteSaved(result)
          case Left(cause)   => Event.NoteSaved(Left(problem("note not saved", cause, "retry the save")))

      case Effect.PrepareFact(draft) =>
        validate(draft) match
          case Left(problem) => IO.pure(Event.FactPrepared(Left(problem)))
          case Right(input) =>
            session.prepareFact(input).attempt.map:
              case Right(result) => Event.FactPrepared(result)
              case Left(cause) =>
                Event.FactPrepared(Left(problem("fact not prepared", cause, "check the values")))

      case Effect.CommitFact(preview) =>
        session.commit(preview).attempt.map:
          case Right(result) => Event.FactCommitted(result)
          case Left(cause) =>
            Event.FactCommitted(Left(problem("fact not committed", cause, "review and retry")))

      case Effect.Search(query) =>
        recover("search not completed", "change the query or inspect the journal")(
          session.search(query)
        ).map(Event.SearchLoaded.apply)

      case Effect.LoadEntity(iri) =>
        recover("entity not loaded", "check the identifier or return to search")(
          session.entity(iri)
        ).map(Event.EntityLoaded.apply)

      case Effect.LoadReview =>
        val load = for
          entries <- session.queue(limit = 1)
          prompt <- entries.headOption.traverse(session.question)
          now <- IO.monotonic
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
            IO.monotonic.flatMap: finished =>
              val latency = (finished - review.askedAt).toMillis.max(0L)
              session.answer(question, response, latency).attempt.map:
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
