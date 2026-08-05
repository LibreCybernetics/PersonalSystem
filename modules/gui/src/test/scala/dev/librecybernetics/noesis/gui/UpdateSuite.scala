package dev.librecybernetics.noesis.gui

import scala.concurrent.duration.*

import cats.data.NonEmptyList
import munit.FunSuite

import dev.librecybernetics.noesis.app.*
import dev.librecybernetics.noesis.core.kb.CommitResult
import dev.librecybernetics.noesis.journal.Commit
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.lms.*

/** Display-independent interaction transcripts for the GNOME Model-View-Update loop. */
class UpdateSuite extends FunSuite:
  private val problem = OwnerProblem("failed", "because", "retry")
  private val iri = Iri("noesis:e/marco")
  private val axiom = Axiom.DataAssertion(iri, Vocab.label, Literal.string("Marco"))
  private val preview = new CommitPreview(
    NonEmptyList.one(axiom),
    AxiomAnnotations.ownerConfirmed,
    "Marco is named Marco",
    Sensitivity.Public,
    0.5,
    1.0
  )
  private val outcome = CommitOutcome(CommitResult(Commit(Nil), Nil, Nil), Nil, List("linked"))
  private val item = Item(ItemId.unsafe("item-1"), ItemKind.AtomicFact, Set(axiom.id), prompt = "name")
  private val entry = QueueEntry(item, QueueMode.Retention, 1.0, 0.2, 1.0, "due")
  private val review = ReviewState(ReviewPrompt(entry, None), 1.second)
  test("first run stays fail-closed until initialization succeeds"):
    val (checking, checkEffects) = Update(Model(), Event.Started)
    assert(checking.busy)
    assertEquals(checkEffects, List(Effect.CheckInitialization))

    val (firstRun, firstRunEffects) = Update(checking, Event.InitializationKnown(false))
    assertEquals(firstRun.surface, GuiSurface.FirstRun)
    assertEquals(firstRun.initialized, false)
    assertEquals(firstRunEffects, Nil)

    val (initializing, initializeEffects) = Update(firstRun, Event.InitializeRequested)
    assert(initializing.busy)
    assertEquals(initializeEffects, List(Effect.Initialize))

  test("a fact cannot commit before its exact preview exists"):
    val model = Model(initialized = true, surface = GuiSurface.CaptureFact)
    val (refused, effects) = Update(model, Event.CommitFactRequested)
    assertEquals(effects, Nil)
    assert(refused.feedback.exists(_.contains("there is no reviewed preview")))

  test("a blank note is refused in what, why, next order"):
    val model = Model(initialized = true, surface = GuiSurface.Today, noteDraft = "  ")
    val (refused, effects) = Update(model, Event.SaveNoteRequested)
    assertEquals(effects, Nil)
    val lines = refused.feedback.getOrElse(fail("missing refusal")).linesIterator.toList
    assertEquals(lines.length, 3)
    assertEquals(lines.headOption, Some("note not saved"))

  test("navigation triggers only the data required by the destination"):
    val model = Model(initialized = true, surface = GuiSurface.Search)
    val (today, todayEffects) = Update(model, Event.Navigate(GuiSurface.Today))
    assertEquals(today.surface, GuiSurface.Today)
    assertEquals(todayEffects, List(Effect.LoadAgenda))
    val (capture, captureEffects) = Update(today, Event.Navigate(GuiSurface.CaptureFact))
    assertEquals(capture.surface, GuiSurface.CaptureFact)
    assertEquals(captureEffects, Nil)

  test("a durable action cannot be enqueued twice while work is in flight"):
    val model = Model(initialized = true, busy = true, noteDraft = "one thought")
    val (unchanged, effects) = Update(model, Event.SaveNoteRequested)
    assertEquals(unchanged, model)
    assertEquals(effects, Nil)

  test("initialization success, failure, and duplicate requests are total"):
    val busy = Model(busy = true)
    assertEquals(Update(busy, Event.InitializeRequested), (busy, Nil))

    val (ready, effects) = Update(busy, Event.Initialized(Right(List("installed"))))
    assertEquals(ready.surface, GuiSurface.Today)
    assert(ready.initialized)
    assert(ready.busy)
    assertEquals(effects, List(Effect.LoadAgenda, Effect.LoadReview))

    val (failed, none) = Update(ready, Event.Initialized(Left(problem)))
    assertEquals(failed.busy, false)
    assertEquals(failed.feedback, Some(problem.render))
    assertEquals(none, Nil)

  test("agenda and note transitions retain load state and confirmation"):
    val model = Model(initialized = true, noteDraft = "thought")
    val (saving, saveEffects) = Update(model, Event.SaveNoteRequested)
    assert(saving.busy)
    assertEquals(saveEffects, List(Effect.SaveNote("thought")))

    val (saved, reload) = Update(saving, Event.NoteSaved(Right(outcome)))
    assertEquals(saved.noteDraft, "")
    assert(saved.feedback.exists(_.contains("linked")))
    assertEquals(reload, List(Effect.LoadAgenda))

    val entries = List(AgendaView(java.time.LocalDate.of(2026, 8, 5), "call", iri, "Marco", false))
    val (agenda, none) = Update(saved, Event.AgendaLoaded(Right(entries)))
    assertEquals(agenda.agenda, LoadState.Ready(entries))
    assertEquals(none, Nil)

    val (agendaFailed, _) = Update(agenda, Event.AgendaLoaded(Left(problem)))
    assertEquals(agendaFailed.agenda, LoadState.Failed(problem))
    val (noteFailed, _) = Update(agenda, Event.NoteSaved(Left(problem)))
    assertEquals(noteFailed.feedback, Some(problem.render))

  test("fact drafting, preview, cancellation, and commit cover every guard"):
    val draft = FactDraft("marco", "rdf:type", "crm:Person")
    val (changed, _) = Update(Model(initialized = true), Event.FactChanged(draft))
    assertEquals(changed.factDraft, draft)

    val (preparing, prepareEffects) = Update(changed, Event.PreviewRequested)
    assertEquals(prepareEffects, List(Effect.PrepareFact(draft)))
    assertEquals(Update(preparing, Event.PreviewRequested), (preparing, Nil))

    val (prepared, _) = Update(preparing, Event.FactPrepared(Right(preview)))
    assertEquals(prepared.preview, Some(preview))
    val (committing, commitEffects) = Update(prepared, Event.CommitFactRequested)
    assertEquals(commitEffects, List(Effect.CommitFact(preview)))
    assertEquals(Update(committing, Event.CommitFactRequested), (committing, Nil))

    val (committed, _) = Update(committing, Event.FactCommitted(Right(outcome)))
    assertEquals(committed.factDraft, FactDraft())
    assertEquals(committed.preview, None)
    val (cancelled, _) = Update(prepared, Event.CancelFact)
    assertEquals(cancelled.preview, None)

    List(Event.FactPrepared(Left(problem)), Event.FactCommitted(Left(problem))).foreach: event =>
      val (failed, effects) = Update(prepared, event)
      assertEquals(failed.feedback, Some(problem.render))
      assertEquals(effects, Nil)

  test("search and entity transitions distinguish guards, loading, success, and failure"):
    val (typed, _) = Update(Model(initialized = true), Event.SearchChanged("marco"))
    val (loading, effects) = Update(typed, Event.SearchRequested)
    assertEquals(loading.searchHits, LoadState.Loading)
    assertEquals(effects, List(Effect.Search("marco")))
    assertEquals(Update(loading, Event.SearchRequested), (loading, Nil))

    val hits = List(SearchHit.Entity(iri, "Marco"))
    assertEquals(Update(loading, Event.SearchLoaded(Right(hits)))._1.searchHits, LoadState.Ready(hits))
    assertEquals(Update(loading, Event.SearchLoaded(Left(problem)))._1.searchHits, LoadState.Failed(problem))
    assert(Update(Model(initialized = true), Event.SearchRequested)._1.feedback.exists(_.contains("blank")))

    val (entityLoading, entityEffects) = Update(typed, Event.EntityRequested(iri))
    assertEquals(entityLoading.surface, GuiSurface.Entity)
    assertEquals(entityEffects, List(Effect.LoadEntity(iri)))
    assertEquals(Update(entityLoading, Event.EntityRequested(iri)), (entityLoading, Nil))
    val view = EntityView("Marco", iri, Nil, Nil)
    assertEquals(Update(entityLoading, Event.EntityLoaded(Right(view)))._1.entity, LoadState.Ready(view))
    assertEquals(Update(entityLoading, Event.EntityLoaded(Left(problem)))._1.entity, LoadState.Failed(problem))

  test("review, answers, external changes, and feedback dismissal are explicit"):
    val base = Model(initialized = true, surface = GuiSurface.Learn)
    val (loading, loadEffects) = Update(base, Event.ReviewRequested)
    assertEquals(loadEffects, List(Effect.LoadReview))
    assertEquals(Update(loading, Event.ReviewRequested), (loading, Nil))
    val (loaded, _) = Update(loading, Event.ReviewLoaded(Right(Some(review))))
    assertEquals(loaded.review, LoadState.Ready(Some(review)))
    assertEquals(Update(loading, Event.ReviewLoaded(Left(problem)))._1.review, LoadState.Failed(problem))

    val blank = Update(loaded, Event.AnswerRequested(" "))._1
    assert(blank.feedback.exists(_.contains("blank")))
    val absent = Update(base, Event.AnswerRequested("answer"))._1
    assert(absent.feedback.exists(_.contains("no active question")))
    assertEquals(Update(loading, Event.AnswerRequested("answer")), (loading, Nil))

    val question = Question(
      "q",
      item.id,
      QuestionFormat.ShortAnswer,
      "Name?",
      AnswerSpec.Exact("Marco"),
      sourceHash = Question.hashOf(item.axioms)
    )
    val activeReview = review.copy(prompt = ReviewPrompt(entry, Some(question)))
    val active = loaded.copy(review = LoadState.Ready(Some(activeReview)))
    assertEquals(
      Update(active, Event.AnswerRequested("Marco"))._2,
      List(Effect.RecordAnswer(activeReview, "Marco"))
    )
    assertEquals(Update(active, Event.AnswerRecorded(Right("Correct")))._2, List(Effect.LoadReview))
    assertEquals(Update(active, Event.AnswerRecorded(Left(problem)))._1.feedback, Some(problem.render))

    assertEquals(Update(base, Event.ExternalChange)._2, List(Effect.LoadReview))
    assertEquals(
      Update(base.copy(surface = GuiSurface.Today), Event.ExternalChange)._2,
      List(Effect.LoadAgenda)
    )
    assertEquals(Update(base.copy(surface = GuiSurface.Search), Event.ExternalChange)._2, Nil)
    assertEquals(Update(Model(), Event.ExternalChange)._2, Nil)
    assertEquals(Update(base.copy(feedback = Some("done")), Event.DismissFeedback)._1.feedback, None)

  test("navigation is refused before initialization and initialization resumes Today"):
    val firstRun = Model()
    assertEquals(Update(firstRun, Event.Navigate(GuiSurface.Search)), (firstRun, Nil))
    val (today, effects) = Update(firstRun, Event.InitializationKnown(true))
    assertEquals(today.surface, GuiSurface.Today)
    assertEquals(effects, List(Effect.LoadAgenda, Effect.LoadReview))
