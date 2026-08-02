package dev.librecybernetics.noesis.gui

import scala.concurrent.duration.FiniteDuration

import dev.librecybernetics.noesis.app.*
import dev.librecybernetics.noesis.logic.Iri

enum LoadState[+A]:
  case Idle
  case Loading
  case Ready(value: A)
  case Failed(problem: OwnerProblem)

final case class FactDraft(
    subject: String = "",
    property: String = "",
    value: String = "",
    createSubject: Boolean = false
)

final case class ReviewState(prompt: ReviewPrompt, askedAt: FiniteDuration)

final case class Model(
    surface: GuiSurface = GuiSurface.FirstRun,
    initialized: Boolean = false,
    busy: Boolean = false,
    agenda: LoadState[List[AgendaView]] = LoadState.Idle,
    noteDraft: String = "",
    factDraft: FactDraft = FactDraft(),
    preview: Option[CommitPreview] = None,
    searchQuery: String = "",
    searchHits: LoadState[List[SearchHit]] = LoadState.Idle,
    entity: LoadState[EntityView] = LoadState.Idle,
    review: LoadState[Option[ReviewState]] = LoadState.Idle,
    feedback: Option[String] = None
)

enum Event:
  case Started
  case InitializationKnown(initialized: Boolean)
  case InitializeRequested
  case Initialized(result: Either[OwnerProblem, List[String]])
  case Navigate(surface: GuiSurface)
  case AgendaLoaded(result: Either[OwnerProblem, List[AgendaView]])
  case NoteChanged(value: String)
  case SaveNoteRequested
  case NoteSaved(result: Either[OwnerProblem, CommitOutcome])
  case FactChanged(draft: FactDraft)
  case PreviewRequested
  case FactPrepared(result: Either[OwnerProblem, CommitPreview])
  case CancelFact
  case CommitFactRequested
  case FactCommitted(result: Either[OwnerProblem, CommitOutcome])
  case SearchChanged(query: String)
  case SearchRequested
  case SearchLoaded(result: Either[OwnerProblem, List[SearchHit]])
  case EntityRequested(iri: Iri)
  case EntityLoaded(result: Either[OwnerProblem, EntityView])
  case ReviewRequested
  case ReviewLoaded(result: Either[OwnerProblem, Option[ReviewState]])
  case AnswerRequested(response: String)
  case AnswerRecorded(result: Either[OwnerProblem, String])
  case ExternalChange
  case DismissFeedback

enum Effect:
  case CheckInitialization
  case Initialize
  case LoadAgenda
  case SaveNote(text: String)
  case PrepareFact(draft: FactDraft)
  case CommitFact(preview: CommitPreview)
  case Search(query: String)
  case LoadEntity(iri: Iri)
  case LoadReview
  case RecordAnswer(review: ReviewState, response: String)

object Update:
  def apply(model: Model, event: Event): (Model, List[Effect]) =
    event match
      case Event.Started =>
        (
          model.copy(busy = true, feedback = Some("Opening workspace…")),
          List(Effect.CheckInitialization)
        )

      case Event.InitializationKnown(false) =>
        (
          model.copy(
            surface = GuiSurface.FirstRun,
            initialized = false,
            busy = false,
            feedback = None
          ),
          Nil
        )

      case Event.InitializationKnown(true) =>
        (
          model.copy(surface = GuiSurface.Today, initialized = true, busy = true, feedback = None),
          List(Effect.LoadAgenda, Effect.LoadReview)
        )

      case Event.InitializeRequested if !model.busy =>
        (
          model.copy(busy = true, feedback = Some("Initializing workspace…")),
          List(Effect.Initialize)
        )

      case Event.InitializeRequested => (model, Nil)

      case Event.Initialized(Right(_)) =>
        (
          model.copy(
            surface = GuiSurface.Today,
            initialized = true,
            busy = true,
            feedback = Some("Workspace initialized")
          ),
          List(Effect.LoadAgenda, Effect.LoadReview)
        )

      case Event.Initialized(Left(problem)) => failed(model, problem)

      case Event.Navigate(surface) if model.initialized =>
        val effects = surface match
          case GuiSurface.Today => List(Effect.LoadAgenda)
          case GuiSurface.Learn => List(Effect.LoadReview)
          case _                => Nil
        (model.copy(surface = surface, feedback = None), effects)

      case Event.Navigate(_) => (model, Nil)

      case Event.AgendaLoaded(Right(entries)) =>
        (model.copy(busy = false, agenda = LoadState.Ready(entries)), Nil)

      case Event.AgendaLoaded(Left(problem)) =>
        (model.copy(busy = false, agenda = LoadState.Failed(problem)), Nil)

      case Event.NoteChanged(value) => (model.copy(noteDraft = value), Nil)

      case Event.SaveNoteRequested if model.busy => (model, Nil)

      case Event.SaveNoteRequested if model.noteDraft.trim.nonEmpty =>
        (
          model.copy(busy = true, feedback = Some("Saving note…")),
          List(Effect.SaveNote(model.noteDraft))
        )

      case Event.SaveNoteRequested =>
        failed(model, OwnerProblem("note not saved", "the note is blank", "write something, then save again"))

      case Event.NoteSaved(Right(outcome)) =>
        (
          model.copy(
            noteDraft = "",
            busy = true,
            feedback = Some(("Note saved" :: outcome.messages).mkString("\n"))
          ),
          List(Effect.LoadAgenda)
        )

      case Event.NoteSaved(Left(problem)) => failed(model, problem)

      case Event.FactChanged(draft) => (model.copy(factDraft = draft, preview = None), Nil)

      case Event.PreviewRequested if !model.busy =>
        (
          model.copy(busy = true, feedback = Some("Preparing fact preview…")),
          List(Effect.PrepareFact(model.factDraft))
        )

      case Event.PreviewRequested => (model, Nil)

      case Event.FactPrepared(Right(preview)) =>
        (
          model.copy(
            busy = false,
            preview = Some(preview),
            feedback = Some("Fact ready for review")
          ),
          Nil
        )

      case Event.FactPrepared(Left(problem)) => failed(model, problem)

      case Event.CancelFact =>
        (model.copy(preview = None, busy = false, feedback = Some("Fact cancelled")), Nil)

      case Event.CommitFactRequested if model.busy => (model, Nil)

      case Event.CommitFactRequested =>
        model.preview match
          case Some(preview) =>
            (
              model.copy(busy = true, feedback = Some("Committing fact…")),
              List(Effect.CommitFact(preview))
            )
          case None =>
            failed(
              model,
              OwnerProblem("fact not saved", "there is no reviewed preview", "review the fact first")
            )

      case Event.FactCommitted(Right(_)) =>
        (
          model.copy(
            factDraft = FactDraft(),
            preview = None,
            busy = false,
            feedback = Some("Fact committed")
          ),
          Nil
        )

      case Event.FactCommitted(Left(problem)) => failed(model, problem)

      case Event.SearchChanged(query) => (model.copy(searchQuery = query), Nil)

      case Event.SearchRequested if model.busy => (model, Nil)

      case Event.SearchRequested if model.searchQuery.trim.nonEmpty =>
        (
          model.copy(busy = true, searchHits = LoadState.Loading, feedback = None),
          List(Effect.Search(model.searchQuery))
        )

      case Event.SearchRequested =>
        failed(model, OwnerProblem("search not run", "the query is blank", "enter a word or identifier"))

      case Event.SearchLoaded(Right(hits)) =>
        (model.copy(busy = false, searchHits = LoadState.Ready(hits)), Nil)

      case Event.SearchLoaded(Left(problem)) =>
        (model.copy(busy = false, searchHits = LoadState.Failed(problem)), Nil)

      case Event.EntityRequested(_) if model.busy => (model, Nil)

      case Event.EntityRequested(iri) =>
        (
          model.copy(surface = GuiSurface.Entity, busy = true, entity = LoadState.Loading),
          List(Effect.LoadEntity(iri))
        )

      case Event.EntityLoaded(Right(entity)) =>
        (model.copy(busy = false, entity = LoadState.Ready(entity)), Nil)

      case Event.EntityLoaded(Left(problem)) =>
        (model.copy(busy = false, entity = LoadState.Failed(problem)), Nil)

      case Event.ReviewRequested if !model.busy =>
        (model.copy(busy = true, review = LoadState.Loading), List(Effect.LoadReview))

      case Event.ReviewRequested => (model, Nil)

      case Event.ReviewLoaded(Right(review)) =>
        (model.copy(busy = false, review = LoadState.Ready(review)), Nil)

      case Event.ReviewLoaded(Left(problem)) =>
        (model.copy(busy = false, review = LoadState.Failed(problem)), Nil)

      case Event.AnswerRequested(_) if model.busy => (model, Nil)

      case Event.AnswerRequested(response) =>
        model.review match
          case LoadState.Ready(Some(review)) if response.trim.nonEmpty =>
            (
              model.copy(busy = true, feedback = Some("Recording answer…")),
              List(Effect.RecordAnswer(review, response))
            )
          case LoadState.Ready(Some(_)) =>
            failed(model, OwnerProblem("answer not recorded", "the answer is blank", "enter an answer"))
          case _ =>
            failed(model, OwnerProblem("answer not recorded", "there is no active question", "load the queue"))

      case Event.AnswerRecorded(Right(verdict)) =>
        (
          model.copy(busy = true, feedback = Some(verdict)),
          List(Effect.LoadReview)
        )

      case Event.AnswerRecorded(Left(problem)) => failed(model, problem)

      case Event.ExternalChange if model.initialized =>
        val effects = model.surface match
          case GuiSurface.Today => List(Effect.LoadAgenda)
          case GuiSurface.Learn => List(Effect.LoadReview)
          case _                => Nil
        (model, effects)

      case Event.ExternalChange => (model, Nil)
      case Event.DismissFeedback => (model.copy(feedback = None), Nil)

  private def failed(model: Model, problem: OwnerProblem): (Model, List[Effect]) =
    (model.copy(busy = false, feedback = Some(problem.render)), Nil)
