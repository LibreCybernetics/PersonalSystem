package dev.librecybernetics.noesis.gui

import scala.concurrent.duration.*

import cats.data.NonEmptyList
import munit.FunSuite

import dev.librecybernetics.noesis.app.*
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.lms.*
import dev.librecybernetics.noesis.vocab.Vocabulary

/** Exact owner-visible text is shared data rather than a toolkit-specific interpretation. */
class PresentationSuite extends FunSuite:
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
  private val item = Item(ItemId.unsafe("item-1"), ItemKind.AtomicFact, Set(axiom.id), prompt = "name")
  private val entry = QueueEntry(item, QueueMode.Retention, 1.0, 0.2, 1.0, "belief is low")
  private val question = Question(
    "q-exact",
    item.id,
    QuestionFormat.ShortAnswer,
    "Name?",
    AnswerSpec.Exact("Marco"),
    sourceHash = Question.hashOf(item.axioms)
  )
  private val problem = OwnerProblem("failed", "journal", "retry")

  test("presentation projects control state and formal fact depth exactly"):
    val result = DesktopPresentation.from(
      Model(
        surface = GuiSurface.CaptureFact,
        busy = false,
        noteDraft = "draft",
        factDraft = FactDraft("marco", "core:label", "Marco"),
        preview = Some(preview),
        feedback = Some("Ready")
      )
    )
    assertEquals(result.surface, GuiSurface.CaptureFact)
    assert(result.preview.contains(axiom.id.value))
    assert(result.preview.contains(axiom.manchester))
    assert(result.preview.contains("Sensitivity: Public"))
    assert(result.commitEnabled)
    assert(result.cancelEnabled)
    assertEquals(result.feedback, Some("Ready"))
    assertEquals(result.clearNoteDraft, false)
    assertEquals(result.clearFactDraft, false)

  test("agenda rendering covers loading, refusal, empty, due, and overdue"):
    val due = AgendaView(java.time.LocalDate.of(2026, 8, 5), "call", iri, "Marco", false)
    val overdue = due.copy(overdue = true)
    val cases = List(
      LoadState.Idle -> "Loading agenda…",
      LoadState.Loading -> "Loading agenda…",
      LoadState.Failed(problem) -> problem.render,
      LoadState.Ready(Nil) -> "Nothing due.",
      LoadState.Ready(List(due, overdue)) ->
        s"Due 2026-08-05: call — Marco (${iri.value})\nOverdue 2026-08-05: call — Marco (${iri.value})"
    )
    cases.foreach: entry =>
      val (state, expected) = entry
      assertEquals(DesktopPresentation.from(Model(agenda = state)).agenda, expected)

  test("review rendering never reveals an answer and covers every load state"):
    val noQuestion = ReviewState(ReviewPrompt(entry, None), 1.second)
    val exact = ReviewState(ReviewPrompt(entry, Some(question)), 1.second)
    val rubric = exact.copy(prompt = ReviewPrompt(entry, Some(question.copy(answer = AnswerSpec.Rubric("name him")))))
    val cases = List(
      LoadState.Idle -> "Loading the learning queue…",
      LoadState.Loading -> "Loading the learning queue…",
      LoadState.Failed(problem) -> problem.render,
      LoadState.Ready(None) -> "Nothing due — the queue is empty."
    )
    cases.foreach: entry =>
      val (state, expected) = entry
      assertEquals(DesktopPresentation.from(Model(review = state)).review, expected)
    assert(DesktopPresentation.from(Model(review = LoadState.Ready(Some(noQuestion)))).review.contains("No usable"))
    val exactView = DesktopPresentation.from(Model(review = LoadState.Ready(Some(exact))))
    assert(exactView.review.contains("Name?"))
    assert(!exactView.review.contains("Marco"))
    assert(exactView.answerEnabled)
    assert(
      DesktopPresentation.from(Model(review = LoadState.Ready(Some(rubric)))).review.contains("Rubric: name him")
    )

  test("search rendering preserves typed entity actions and textual results"):
    val term = Vocabulary.Term(
      Vocab.label,
      "core",
      Vocabulary.Role.Property,
      Nil,
      Nil,
      Some("{s} is named {o}"),
      Sensitivity.Public,
      0.5,
      None,
      false
    )
    val hits = List[SearchHit](
      SearchHit.Entity(iri, "Marco"),
      SearchHit.NoteBlock(Iri("noesis:note/today"), "Today", Iri("noesis:block/1"), "Met Marco"),
      SearchHit.Term(term)
    )
    val ready = DesktopPresentation.from(Model(searchHits = LoadState.Ready(hits)))
    assertEquals(ready.search, "3 match(es).")
    ready.searchRows match
      case SearchRow.Entity(0, found, text) :: SearchRow.Text(1, note) :: SearchRow.Text(2, termText) :: Nil =>
        assertEquals(found, iri)
        assert(text.contains("Open Marco"))
        assert(note.contains("Met Marco"))
        assert(termText.contains(Vocab.label.value))
      case other => fail(s"unexpected rows: $other")
    assertEquals(DesktopPresentation.from(Model()).search, "Enter a query.")
    assertEquals(DesktopPresentation.from(Model(searchHits = LoadState.Loading)).search, "Searching…")
    assertEquals(
      DesktopPresentation.from(Model(searchHits = LoadState.Failed(problem))).search,
      problem.render
    )
    assertEquals(DesktopPresentation.from(Model(searchHits = LoadState.Ready(Nil))).search, "No matches.")

  test("entity rendering covers loading, refusal, and actionable identifiers"):
    val entity = EntityView(
      "Marco",
      iri,
      List(FactView(axiom.id, "Marco is named Marco", axiom.manchester, Some(0.8))),
      List("Marco works at Acme")
    )
    assertEquals(DesktopPresentation.from(Model()).entity, "Loading entity…")
    assertEquals(DesktopPresentation.from(Model(entity = LoadState.Failed(problem))).entity, problem.render)
    val ready = DesktopPresentation.from(Model(entity = LoadState.Ready(entity))).entity
    assert(ready.contains("Marco works at Acme"))
    assert(ready.contains(axiom.id.value))
    assert(ready.contains("belief 0.80"))

  test("busy state disables every durable or answer action"):
    val review = ReviewState(ReviewPrompt(entry, Some(question)), 1.second)
    val result = DesktopPresentation.from(
      Model(busy = true, preview = Some(preview), review = LoadState.Ready(Some(review)))
    )
    assertEquals(result.commitEnabled, false)
    assertEquals(result.cancelEnabled, false)
    assertEquals(result.answerEnabled, false)
    assert(result.clearNoteDraft)
    assert(result.clearFactDraft)

  test("desktop arguments preserve defaults and name the invoking executable"):
    assertEquals(DesktopArguments.parse(Nil, "noesis-gui"), Right(Workspace.defaultRoot))
    assertEquals(
      DesktopArguments.parse(List("--workspace", "/tmp/noesis"), "noesis-gui-scalafx"),
      Right(fs2.io.file.Path("/tmp/noesis"))
    )
    val problem = DesktopArguments
      .parse(List("--unknown"), "noesis-gui-scalafx")
      .left
      .getOrElse(fail("invalid arguments were accepted"))
    assertEquals(problem.next, "run noesis-gui-scalafx --workspace /path/to/workspace")

  test("surface and control handles are unique and retain the shipped values"):
    assertEquals(GuiSurface.values.map(_.id).distinct.length, GuiSurface.values.length)
    assertEquals(GuiControl.values.map(_.id).distinct.length, GuiControl.values.length)
    assertEquals(GuiSurface.FirstRun.id, "gui:first-run")
    assertEquals(GuiControl.Initialize.id, "gui:first-run:start")
