package dev.librecybernetics.noesis.gui

import org.gnome.adw.{
  ApplicationWindow,
  Breakpoint,
  BreakpointCondition,
  BreakpointConditionLengthType,
  HeaderBar,
  LengthUnit,
  NavigationPage,
  NavigationSplitView,
  Toast,
  ToastOverlay,
  ToolbarView
}
import org.gnome.gtk
import org.gnome.gtk.{Align, Box, Button, CheckButton, Entry, Label, Orientation, ScrolledWindow, Stack}

import dev.librecybernetics.noesis.app.{EntityView, SearchHit}
import dev.librecybernetics.noesis.lms.AnswerSpec

/** A thin GTK renderer: widgets emit Events, while all state transitions remain in [[Update]]. */
final class DesktopView(
    application: org.gnome.adw.Application,
    workspacePath: String,
    dispatch: Event => Unit
):
  val window = ApplicationWindow(application)
  window.setTitle("Noesis")
  window.setDefaultSize(960, 680)

  private val stack = Stack()
  private val split = NavigationSplitView()
  private val toastOverlay = ToastOverlay()
  private val initializeButton = Button.withLabel("Start Noesis")
  private val agendaLabel = contentLabel()
  private val noteEntry = Entry()
  private val saveNoteButton = Button.withLabel("Save note")
  private val subjectEntry = Entry()
  private val propertyEntry = Entry()
  private val valueEntry = Entry()
  private val newSubject = CheckButton()
  private val previewButton = Button.withLabel("Review fact")
  private val previewLabel = contentLabel()
  private val commitButton = Button.withLabel("Confirm and commit")
  private val cancelFactButton = Button.withLabel("Cancel")
  private val reviewLabel = contentLabel()
  private val answerEntry = Entry()
  private val answerButton = Button.withLabel("Submit answer")
  private val refreshReviewButton = Button.withLabel("Refresh queue")
  private val searchEntry = Entry()
  private val searchButton = Button.withLabel("Search")
  private val searchLabel = contentLabel()
  private val searchResults = Box(Orientation.VERTICAL, 6)
  private val entityEntry = Entry()
  private val entityButton = Button.withLabel("Open entity")
  private val entityLabel = contentLabel()
  private val feedbackLabel = contentLabel()
  private var lastFeedback: Option[String] = None
  private var workInFlight = false
  private var searchResultWidgets: List[gtk.Widget] = Nil

  build()

  def present(): Unit = window.present()

  def render(model: Model): Unit =
    workInFlight = model.busy
    stack.setVisibleChildName(surfaceName(model.surface))
    agendaLabel.setText(renderAgenda(model.agenda))
    previewLabel.setText(renderPreview(model))
    commitButton.setSensitive(model.preview.nonEmpty && !model.busy)
    cancelFactButton.setSensitive(model.preview.nonEmpty && !model.busy)
    reviewLabel.setText(renderReview(model))
    answerButton.setSensitive(hasQuestion(model) && !model.busy)
    searchLabel.setText(renderSearch(model.searchHits))
    renderSearchResults(model.searchHits)
    entityLabel.setText(renderEntity(model.entity))
    feedbackLabel.setText(model.feedback.getOrElse(""))
    feedbackLabel.setVisible(model.feedback.nonEmpty)
    initializeButton.setSensitive(!model.busy)
    saveNoteButton.setSensitive(!model.busy)
    previewButton.setSensitive(!model.busy)
    searchButton.setSensitive(!model.busy)
    entityButton.setSensitive(!model.busy)
    refreshReviewButton.setSensitive(!model.busy)

    if model.noteDraft.isEmpty && noteEntry.getText.nonEmpty then noteEntry.setText("")
    if model.factDraft == FactDraft() && subjectEntry.getText.nonEmpty then
      subjectEntry.setText("")
      propertyEntry.setText("")
      valueEntry.setText("")
      newSubject.setActive(false)
    if model.feedback != lastFeedback then
      model.feedback.foreach(message => toastOverlay.addToast(Toast(message)))
      lastFeedback = model.feedback

  private def build(): Unit =
    val header = HeaderBar()
    val title = Label("Noesis")
    title.addCssClass("title")
    header.setTitleWidget(title)

    val toolbar = ToolbarView()
    toolbar.addTopBar(header)

    val navigation = Box(Orientation.VERTICAL, 6)
    navigation.setSizeRequest(180, -1)
    navigation.setMarginTop(12)
    navigation.setMarginBottom(12)
    navigation.setMarginStart(12)
    navigation.setMarginEnd(12)
    GuiSurface.navigable.foreach: surface =>
      val button = Button.withLabel(surface.title)
      val _ = button.onClicked: () =>
        dispatch(Event.Navigate(surface))
        split.setShowContent(true)
      navigation.append(button)
    stack.setHexpand(true)
    stack.setVexpand(true)
    addPage(GuiSurface.FirstRun, firstRunPage())
    addPage(GuiSurface.Today, todayPage())
    addPage(GuiSurface.CaptureFact, capturePage())
    addPage(GuiSurface.Learn, learnPage())
    addPage(GuiSurface.Search, searchPage())
    addPage(GuiSurface.Entity, entityPage())
    split.setSidebar(NavigationPage(navigation, "Navigation"))
    split.setContent(NavigationPage(stack, "Noesis"))
    val compact = Breakpoint(
      BreakpointCondition.length(BreakpointConditionLengthType.MAX_WIDTH, 700.0, LengthUnit.PX)
    )
    val _ = compact.onApply(() => split.setCollapsed(true))
    val _ = compact.onUnapply(() => split.setCollapsed(false))
    window.addBreakpoint(compact)

    val _ = window.onCloseRequest: () =>
      if workInFlight then
        toastOverlay.addToast(Toast("Wait for the current operation to finish"))
        true
      else false

    val content = Box(Orientation.VERTICAL, 0)
    split.setVexpand(true)
    content.append(split)
    feedbackLabel.setMarginTop(8)
    feedbackLabel.setMarginBottom(8)
    feedbackLabel.setMarginStart(12)
    feedbackLabel.setMarginEnd(12)
    feedbackLabel.addCssClass("caption")
    content.append(feedbackLabel)
    toastOverlay.setChild(content)
    toolbar.setContent(toastOverlay)
    window.setContent(toolbar)

  private def firstRunPage(): gtk.Widget =
    val page = pageBox(
      "Welcome to Noesis",
      s"Create $workspacePath with owner-only persistence and install its vocabularies."
    )
    val _ = initializeButton.onClicked(() => begin(Event.InitializeRequested))
    initializeButton.setHalign(Align.START)
    page.append(initializeButton)
    page

  private def todayPage(): gtk.Widget =
    val page = pageBox("Today", "Due obligations and a quick append-only note.")
    agendaLabel.setSelectable(true)
    page.append(agendaLabel)
    noteEntry.setPlaceholderText("Write a thought for today's note")
    noteEntry.setHexpand(true)
    page.append(noteEntry)
    val _ = saveNoteButton.onClicked: () =>
      dispatch(Event.NoteChanged(noteEntry.getText))
      begin(Event.SaveNoteRequested)
    saveNoteButton.setHalign(Align.START)
    page.append(saveNoteButton)
    scroll(page)

  private def capturePage(): gtk.Widget =
    val page = pageBox("Capture a fact", "Enter identifiers, review the formal assertion, then commit.")
    subjectEntry.setPlaceholderText("Subject, e.g. marco")
    propertyEntry.setPlaceholderText("Property, e.g. crm:worksAt")
    valueEntry.setPlaceholderText("Value, identifier, or literal")
    newSubject.setLabel("Create this subject")
    List(subjectEntry, propertyEntry, valueEntry, newSubject).foreach(page.append)
    val _ = previewButton.onClicked: () =>
      dispatch(Event.FactChanged(factDraft()))
      dispatch(Event.PreviewRequested)
    previewButton.setHalign(Align.START)
    page.append(previewButton)
    previewLabel.setSelectable(true)
    page.append(previewLabel)
    val _ = commitButton.onClicked(() => begin(Event.CommitFactRequested))
    commitButton.addCssClass("suggested-action")
    commitButton.setHalign(Align.START)
    page.append(commitButton)
    val _ = cancelFactButton.onClicked(() => dispatch(Event.CancelFact))
    cancelFactButton.setHalign(Align.START)
    page.append(cancelFactButton)
    scroll(page)

  private def learnPage(): gtk.Widget =
    val page = pageBox("Learn", "The reason for selection is shown with every queued question.")
    reviewLabel.setSelectable(true)
    page.append(reviewLabel)
    answerEntry.setPlaceholderText("Your answer")
    page.append(answerEntry)
    val _ = answerButton.onClicked: () =>
      begin(Event.AnswerRequested(answerEntry.getText))
      answerEntry.setText("")
    answerButton.addCssClass("suggested-action")
    answerButton.setHalign(Align.START)
    page.append(answerButton)
    val _ = refreshReviewButton.onClicked(() => dispatch(Event.ReviewRequested))
    refreshReviewButton.setHalign(Align.START)
    page.append(refreshReviewButton)
    scroll(page)

  private def searchPage(): gtk.Widget =
    val page = pageBox("Search", "Search entities, note blocks, and vocabulary terms locally.")
    searchEntry.setPlaceholderText("Search")
    page.append(searchEntry)
    val _ = searchButton.onClicked: () =>
      dispatch(Event.SearchChanged(searchEntry.getText))
      dispatch(Event.SearchRequested)
    searchButton.setHalign(Align.START)
    page.append(searchButton)
    searchLabel.setSelectable(true)
    page.append(searchLabel)
    page.append(searchResults)
    entityEntry.setPlaceholderText("Entity identifier to open")
    page.append(entityEntry)
    val _ = entityButton.onClicked: () =>
      val value = entityEntry.getText.trim
      if value.nonEmpty then dispatch(Event.EntityRequested(dev.librecybernetics.noesis.app.Workspace.iri(value)))
    entityButton.setHalign(Align.START)
    page.append(entityButton)
    scroll(page)

  private def entityPage(): gtk.Widget =
    val page = pageBox("Entity", "Current states and asserted facts, including belief where available.")
    entityLabel.setSelectable(true)
    page.append(entityLabel)
    val back = Button.withLabel("Back to search")
    val _ = back.onClicked(() => dispatch(Event.Navigate(GuiSurface.Search)))
    back.setHalign(Align.START)
    page.append(back)
    scroll(page)

  private def addPage(surface: GuiSurface, widget: gtk.Widget): Unit =
    val _ = stack.addTitled(widget, surfaceName(surface), surface.title)

  private def pageBox(title: String, subtitle: String): Box =
    val page = Box(Orientation.VERTICAL, 12)
    page.setMarginTop(24)
    page.setMarginBottom(24)
    page.setMarginStart(24)
    page.setMarginEnd(24)
    val heading = Label(title)
    heading.setXalign(0.0f)
    heading.addCssClass("title-1")
    page.append(heading)
    val explanation = Label(subtitle)
    explanation.setXalign(0.0f)
    explanation.setWrap(true)
    explanation.addCssClass("dim-label")
    page.append(explanation)
    page

  private def scroll(child: gtk.Widget): ScrolledWindow =
    val scrolled = ScrolledWindow()
    scrolled.setChild(child)
    scrolled.setVexpand(true)
    scrolled

  private def contentLabel(): Label =
    val label = Label("")
    label.setXalign(0.0f)
    label.setYalign(0.0f)
    label.setWrap(true)
    label

  private def factDraft(): FactDraft =
    FactDraft(subjectEntry.getText, propertyEntry.getText, valueEntry.getText, newSubject.getActive)

  private def begin(event: Event): Unit =
    workInFlight = true
    dispatch(event)

  private def surfaceName(surface: GuiSurface): String = surface.id.replace(':', '-')

  private def renderAgenda(state: LoadState[List[dev.librecybernetics.noesis.app.AgendaView]]): String =
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
      case LoadState.Idle                => "Enter a query."
      case LoadState.Loading             => "Searching…"
      case LoadState.Failed(problem)     => problem.render
      case LoadState.Ready(Nil)          => "No matches."
      case LoadState.Ready(hits)         => s"${hits.length} match(es)."

  private def renderHit(hit: SearchHit): String =
    hit match
      case SearchHit.Entity(iri, label) => s"Entity — $label — ${iri.value}"
      case SearchHit.NoteBlock(note, title, _, text) => s"Note — $title — ${note.value}\n  $text"
      case SearchHit.Term(term) =>
        s"Term — ${term.iri.value} — ${term.template.getOrElse(term.role.toString)}"

  private def renderSearchResults(state: LoadState[List[SearchHit]]): Unit =
    searchResultWidgets.foreach(searchResults.remove)
    searchResultWidgets = state match
      case LoadState.Ready(hits) =>
        hits.map:
          case SearchHit.Entity(iri, label) =>
            val button = Button.withLabel(s"Open $label — ${iri.value}")
            val _ = button.onClicked(() => dispatch(Event.EntityRequested(iri)))
            searchResults.append(button)
            button
          case hit =>
            val label = contentLabel()
            label.setText(renderHit(hit))
            searchResults.append(label)
            label
      case _ => Nil

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
