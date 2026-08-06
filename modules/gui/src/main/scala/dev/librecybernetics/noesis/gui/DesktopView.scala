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

/** A thin GTK renderer: widgets emit Events, while all state transitions remain in [[Update]]. */
final class DesktopView(
    application: org.gnome.adw.Application,
    workspacePath: String,
    dispatch: Event => Unit
) extends GuiView:
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

  private[gui] def snapshot: DesktopSnapshot =
    DesktopSnapshot(
      Option(stack.getVisibleChildName).getOrElse(""),
      agendaLabel.getText,
      previewLabel.getText,
      reviewLabel.getText,
      searchLabel.getText,
      entityLabel.getText,
      feedbackLabel.getText,
      commitButton.getSensitive,
      answerButton.getSensitive
    )

  def render(model: DesktopPresentation): Unit =
    workInFlight = model.busy
    stack.setVisibleChildName(surfaceName(model.surface))
    agendaLabel.setText(model.agenda)
    previewLabel.setText(model.preview)
    commitButton.setSensitive(model.commitEnabled)
    cancelFactButton.setSensitive(model.cancelEnabled)
    reviewLabel.setText(model.review)
    answerButton.setSensitive(model.answerEnabled)
    searchLabel.setText(model.search)
    renderSearchResults(model.searchRows)
    entityLabel.setText(model.entity)
    feedbackLabel.setText(model.feedback.getOrElse(""))
    feedbackLabel.setVisible(model.feedback.nonEmpty)
    initializeButton.setSensitive(!model.busy)
    saveNoteButton.setSensitive(!model.busy)
    previewButton.setSensitive(!model.busy)
    searchButton.setSensitive(!model.busy)
    entityButton.setSensitive(!model.busy)
    refreshReviewButton.setSensitive(!model.busy)

    if model.clearNoteDraft && noteEntry.getText.nonEmpty then noteEntry.setText("")
    if model.clearFactDraft && subjectEntry.getText.nonEmpty then
      subjectEntry.setText("")
      propertyEntry.setText("")
      valueEntry.setText("")
      newSubject.setActive(false)
    if model.feedback != lastFeedback then
      model.feedback.foreach(message => toastOverlay.addToast(Toast(message)))
      lastFeedback = model.feedback

  private def build(): Unit =
    identify(initializeButton, GuiControl.Initialize.id)
    identify(noteEntry, GuiControl.Note.id)
    identify(saveNoteButton, GuiControl.SaveNote.id)
    identify(subjectEntry, GuiControl.FactSubject.id)
    identify(propertyEntry, GuiControl.FactProperty.id)
    identify(valueEntry, GuiControl.FactValue.id)
    identify(newSubject, GuiControl.FactNewSubject.id)
    identify(previewButton, GuiControl.FactPreview.id)
    identify(commitButton, GuiControl.FactCommit.id)
    identify(cancelFactButton, GuiControl.FactCancel.id)
    identify(answerEntry, GuiControl.LearnAnswer.id)
    identify(answerButton, GuiControl.LearnSubmit.id)
    identify(refreshReviewButton, GuiControl.LearnRefresh.id)
    identify(searchEntry, GuiControl.SearchQuery.id)
    identify(searchButton, GuiControl.SearchSubmit.id)
    identify(entityEntry, GuiControl.SearchEntity.id)
    identify(entityButton, GuiControl.SearchOpenEntity.id)
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
      identify(button, GuiControl.navigate(surface))
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
    identify(back, GuiControl.EntityBack.id)
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

  /** GTK widget names are stable automation/accessibility handles, not translated labels. */
  private def identify(widget: gtk.Widget, id: String): Unit = widget.setName(id)

  private def factDraft(): FactDraft =
    FactDraft(subjectEntry.getText, propertyEntry.getText, valueEntry.getText, newSubject.getActive)

  private def begin(event: Event): Unit =
    workInFlight = true
    dispatch(event)

  private def surfaceName(surface: GuiSurface): String = surface.id.replace(':', '-')

  private def renderSearchResults(rows: List[SearchRow]): Unit =
    searchResultWidgets.foreach(searchResults.remove)
    searchResultWidgets = rows.map:
      case SearchRow.Entity(index, iri, text) =>
        val button = Button.withLabel(text)
        identify(button, GuiControl.searchResult(index))
        val _ = button.onClicked(() => dispatch(Event.EntityRequested(iri)))
        searchResults.append(button)
        button
      case SearchRow.Text(index, text) =>
        val label = contentLabel()
        identify(label, GuiControl.searchResult(index))
        label.setText(text)
        searchResults.append(label)
        label
