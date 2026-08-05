package dev.librecybernetics.noesis.gui

import scala.concurrent.duration.*
import scala.util.control.NonFatal

import cats.data.NonEmptyList
import munit.FunSuite
import org.gnome.adw.Application

import dev.librecybernetics.noesis.app.*
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.lms.*
import dev.librecybernetics.noesis.vocab.Vocabulary

/** The deterministic interaction transcript for the real GTK widget tree, run under Xvfb. */
class DesktopViewSuite extends FunSuite:
  test("every desktop surface and load-state rendering produces stable evidence"):
    val application = Application("dev.librecybernetics.Noesis.Test")
    var failure: Option[String] = None
    var activated = false
    val _ = application.onActivate: () =>
      try
        activated = true
        val iri = Iri("noesis:e/marco")
        val axiom = Axiom.DataAssertion(iri, Vocab.label, Literal.string("Marco"))
        val preview = new CommitPreview(
          NonEmptyList.one(axiom),
          AxiomAnnotations.ownerConfirmed,
          "Marco is named Marco",
          Sensitivity.Public,
          0.5,
          1.0
        )
        val item = Item(ItemId.unsafe("item-1"), ItemKind.AtomicFact, Set(axiom.id), prompt = "name")
        val entry = QueueEntry(item, QueueMode.Retention, 1.0, 0.2, 1.0, "belief is low")
        val exact = Question(
          "q-exact",
          item.id,
          QuestionFormat.ShortAnswer,
          "Name?",
          AnswerSpec.Exact("Marco"),
          sourceHash = Question.hashOf(item.axioms)
        )
        val rubric = exact.copy(id = "q-rubric", answer = AnswerSpec.Rubric("recognize the person"))
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
        val view = DesktopView(application, "/tmp/noesis-desktop-test", _ => ())

        GuiSurface.values.foreach(surface => view.render(Model(surface = surface)))
        List[LoadState[List[AgendaView]]](
          LoadState.Idle,
          LoadState.Loading,
          LoadState.Failed(OwnerProblem("agenda failed", "journal", "retry")),
          LoadState.Ready(Nil),
          LoadState.Ready(
            List(AgendaView(java.time.LocalDate.of(2026, 8, 5), "call", iri, "Marco", true))
          )
        ).foreach(value => view.render(Model(surface = GuiSurface.Today, agenda = value)))
        view.render(Model(surface = GuiSurface.CaptureFact, preview = Some(preview)))

        val reviews = List[LoadState[Option[ReviewState]]](
          LoadState.Idle,
          LoadState.Loading,
          LoadState.Failed(OwnerProblem("review failed", "journal", "retry")),
          LoadState.Ready(None),
          LoadState.Ready(Some(ReviewState(ReviewPrompt(entry, None), 1.second))),
          LoadState.Ready(Some(ReviewState(ReviewPrompt(entry, Some(exact)), 1.second))),
          LoadState.Ready(Some(ReviewState(ReviewPrompt(entry, Some(rubric)), 1.second)))
        )
        reviews.foreach(value => view.render(Model(surface = GuiSurface.Learn, review = value)))

        val hits = List[SearchHit](
          SearchHit.Entity(iri, "Marco"),
          SearchHit.NoteBlock(Iri("noesis:note/today"), "Today", Iri("noesis:block/1"), "Met Marco"),
          SearchHit.Term(term)
        )
        List[LoadState[List[SearchHit]]](
          LoadState.Idle,
          LoadState.Loading,
          LoadState.Failed(OwnerProblem("search failed", "journal", "retry")),
          LoadState.Ready(Nil),
          LoadState.Ready(hits)
        ).foreach(value => view.render(Model(surface = GuiSurface.Search, searchHits = value)))

        val entity = EntityView(
          "Marco",
          iri,
          List(FactView(axiom.id, "Marco is named Marco", axiom.manchester, Some(0.8))),
          List("Marco works at Acme")
        )
        List[LoadState[EntityView]](
          LoadState.Idle,
          LoadState.Loading,
          LoadState.Failed(OwnerProblem("entity failed", "journal", "retry")),
          LoadState.Ready(entity)
        ).foreach(value => view.render(Model(surface = GuiSurface.Entity, entity = value)))

        view.render(
          Model(
            surface = GuiSurface.Learn,
            review = LoadState.Ready(Some(ReviewState(ReviewPrompt(entry, Some(exact)), 1.second))),
            feedback = Some("Ready")
          )
        )
        val transcript = view.snapshot
        assertEquals(transcript.surface, "gui-learn")
        assert(transcript.review.contains("Name?"))
        assertEquals(transcript.feedback, "Ready")
        assert(transcript.answerEnabled)
      catch
        case NonFatal(cause) => failure = Some(Option(cause.getMessage).getOrElse(cause.toString))
      finally application.quit()

    val _ = application.run(Array.empty[String])
    failure.foreach(message => fail(message))
    assert(activated, "GTK activation callback did not run")
