package dev.librecybernetics.noesis.gui

import dev.librecybernetics.noesis.app.{AgendaView, EntityView, SearchHit}
import dev.librecybernetics.noesis.logic.Iri
import dev.librecybernetics.noesis.lms.AnswerSpec

enum SearchRow:
  case Entity(index: Int, iri: Iri, text: String)
  case Text(index: Int, text: String)

/** Stable evidence read back from either real widget tree (TESTING, GUI scenarios). */
final case class DesktopSnapshot(
    surface: String,
    agenda: String,
    preview: String,
    review: String,
    search: String,
    entity: String,
    feedback: String,
    commitEnabled: Boolean,
    answerEnabled: Boolean
)

/** Toolkit-free rendering input, so GTK and ScalaFX cannot acquire separate output vocabularies. */
final case class DesktopPresentation(
    surface: GuiSurface,
    agenda: String,
    preview: String,
    review: String,
    search: String,
    searchRows: List[SearchRow],
    entity: String,
    feedback: Option[String],
    busy: Boolean,
    commitEnabled: Boolean,
    cancelEnabled: Boolean,
    answerEnabled: Boolean,
    clearNoteDraft: Boolean,
    clearFactDraft: Boolean
)

object DesktopPresentation:
  def from(model: Model): DesktopPresentation =
    DesktopPresentation(
      surface = model.surface,
      agenda = renderAgenda(model.agenda),
      preview = renderPreview(model),
      review = renderReview(model),
      search = renderSearch(model.searchHits),
      searchRows = renderSearchRows(model.searchHits),
      entity = renderEntity(model.entity),
      feedback = model.feedback,
      busy = model.busy,
      commitEnabled = model.preview.nonEmpty && !model.busy,
      cancelEnabled = model.preview.nonEmpty && !model.busy,
      answerEnabled = hasQuestion(model) && !model.busy,
      clearNoteDraft = model.noteDraft.isEmpty,
      clearFactDraft = model.factDraft == FactDraft()
    )

  private def renderAgenda(state: LoadState[List[AgendaView]]): String =
    state match
      case LoadState.Idle | LoadState.Loading => "Loading agenda…"
      case LoadState.Failed(problem)          => problem.render
      case LoadState.Ready(Nil)               => "Nothing due."
      case LoadState.Ready(entries) =>
        entries.map: entry =>
          val marker = if entry.overdue then "Overdue" else "Due"
          s"$marker ${entry.due}: ${entry.summary} — ${entry.subjectLabel} (${entry.subject.value})"
        .mkString("\n")

  private def renderPreview(model: Model): String =
    model.preview.fold("Nothing waiting for confirmation."): preview =>
      val formal = preview.axioms.toList.map(axiom => s"${axiom.id.value}\n${axiom.manchester}")
      f"${preview.verbalization}\n${formal.mkString("\n")}\nSensitivity: ${preview.sensitivity}%s\nUtility: ${preview.utility}%.2f\nConfidence: ${preview.confidence}%.2f"

  private def renderReview(model: Model): String =
    model.review match
      case LoadState.Idle | LoadState.Loading => "Loading the learning queue…"
      case LoadState.Failed(problem)          => problem.render
      case LoadState.Ready(None)              => "Nothing due — the queue is empty."
      case LoadState.Ready(Some(review)) =>
        review.prompt.question match
          case None =>
            s"${review.prompt.entry.item.prompt}\n${review.prompt.entry.reason}\nNo usable question is available."
          case Some(question) =>
            val guidance = question.answer match
              case AnswerSpec.Rubric(criteria) => s"Rubric: $criteria"
              case _                           => "Type your answer below."
            s"${question.prompt}\nWhy now: ${review.prompt.entry.reason}\n$guidance"

  private def hasQuestion(model: Model): Boolean =
    model.review match
      case LoadState.Ready(Some(review)) => review.prompt.question.nonEmpty
      case _                             => false

  private def renderSearch(state: LoadState[List[SearchHit]]): String =
    state match
      case LoadState.Idle            => "Enter a query."
      case LoadState.Loading         => "Searching…"
      case LoadState.Failed(problem) => problem.render
      case LoadState.Ready(Nil)      => "No matches."
      case LoadState.Ready(hits)     => s"${hits.length} match(es)."

  private def renderSearchRows(state: LoadState[List[SearchHit]]): List[SearchRow] =
    state match
      case LoadState.Ready(hits) =>
        hits.zipWithIndex.map: entry =>
          val (hit, index) = entry
          hit match
            case SearchHit.Entity(iri, label) =>
              SearchRow.Entity(index, iri, s"Open $label — ${iri.value}")
            case other => SearchRow.Text(index, renderHit(other))
      case _ => Nil

  private def renderHit(hit: SearchHit): String =
    hit match
      case SearchHit.Entity(iri, label) => s"Entity — $label — ${iri.value}"
      case SearchHit.NoteBlock(note, title, _, text) => s"Note — $title — ${note.value}\n  $text"
      case SearchHit.Term(term) =>
        s"Term — ${term.iri.value} — ${term.template.getOrElse(term.role.toString)}"

  private def renderEntity(state: LoadState[EntityView]): String =
    state match
      case LoadState.Idle | LoadState.Loading => "Loading entity…"
      case LoadState.Failed(problem)          => problem.render
      case LoadState.Ready(entity) =>
        val states = entity.states.map(value => s"State — $value")
        val facts = entity.facts.map: fact =>
          val belief = fact.belief.fold("")(value => f" — belief $value%.2f")
          s"${fact.verbalization}$belief\n  ${fact.id.value}\n  ${fact.manchester}"
        (s"${entity.label}\n${entity.iri.value}" :: states ++ facts).mkString("\n")
