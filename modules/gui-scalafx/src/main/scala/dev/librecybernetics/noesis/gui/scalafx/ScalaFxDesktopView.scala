package dev.librecybernetics.noesis.gui.scalafx

import scala.collection.mutable

import scalafx.geometry.{Insets, Pos}
import scalafx.scene.{AccessibleRole, Node}
import scalafx.scene.Scene
import scalafx.scene.control.{Button, CheckBox, Label, ScrollPane, TextField}
import scalafx.scene.layout.{BorderPane, HBox, Priority, StackPane, VBox}
import scalafx.stage.Stage

import dev.librecybernetics.noesis.app.Workspace
import dev.librecybernetics.noesis.gui.*

/** ScalaFX renderer for the shared desktop projection; it owns no application behavior. */
final class ScalaFxDesktopView(
    window: Stage,
    workspacePath: String,
    dispatch: Event => Unit
) extends GuiView:
  private val pages = StackPane()
  private val initializeButton = Button("Start Noesis")
  private val agendaLabel = contentLabel()
  private val noteEntry = TextField()
  private val saveNoteButton = Button("Save note")
  private val subjectEntry = TextField()
  private val propertyEntry = TextField()
  private val valueEntry = TextField()
  private val newSubject = CheckBox("Create this subject")
  private val previewButton = Button("Review fact")
  private val previewLabel = contentLabel()
  private val commitButton = Button("Confirm and commit")
  private val cancelFactButton = Button("Cancel")
  private val reviewLabel = contentLabel()
  private val answerEntry = TextField()
  private val answerButton = Button("Submit answer")
  private val refreshReviewButton = Button("Refresh queue")
  private val searchEntry = TextField()
  private val searchButton = Button("Search")
  private val searchLabel = contentLabel()
  private val searchResults = VBox(6.0)
  private val entityEntry = TextField()
  private val entityButton = Button("Open entity")
  private val entityLabel = contentLabel()
  private val feedbackLabel = contentLabel()
  private val identifiedNodes = mutable.Map.empty[String, javafx.scene.Node]
  private var workInFlight = false
  private var visibleSurface = GuiSurface.FirstRun

  build()

  def present(): Unit =
    window.show()
    window.toFront()

  private[scalafx] def snapshot: DesktopSnapshot =
    DesktopSnapshot(
      visibleSurface.id.replace(':', '-'),
      agendaLabel.text.value,
      previewLabel.text.value,
      reviewLabel.text.value,
      searchLabel.text.value,
      entityLabel.text.value,
      feedbackLabel.text.value,
      !commitButton.disable.value,
      !answerButton.disable.value
    )

  private[scalafx] def node(id: String): Option[javafx.scene.Node] = identifiedNodes.get(id)

  def render(model: DesktopPresentation): Unit =
    workInFlight = model.busy
    visibleSurface = model.surface
    pageNodes.foreach: entry =>
      val (surface, node) = entry
      val selected = surface == model.surface
      node.visible = selected
      node.managed = selected
    agendaLabel.text = model.agenda
    previewLabel.text = model.preview
    commitButton.disable = !model.commitEnabled
    cancelFactButton.disable = !model.cancelEnabled
    reviewLabel.text = model.review
    answerButton.disable = !model.answerEnabled
    searchLabel.text = model.search
    renderSearchResults(model.searchRows)
    entityLabel.text = model.entity
    feedbackLabel.text = model.feedback.getOrElse("")
    feedbackLabel.visible = model.feedback.nonEmpty
    feedbackLabel.managed = model.feedback.nonEmpty
    List(
      initializeButton,
      saveNoteButton,
      previewButton,
      searchButton,
      entityButton,
      refreshReviewButton
    ).foreach(_.disable = model.busy)
    if model.clearNoteDraft && noteEntry.text.value.nonEmpty then noteEntry.text = ""
    if model.clearFactDraft && subjectEntry.text.value.nonEmpty then
      subjectEntry.text = ""
      propertyEntry.text = ""
      valueEntry.text = ""
      newSubject.selected = false

  private lazy val pageNodes: List[(GuiSurface, Node)] = List(
    GuiSurface.FirstRun -> firstRunPage(),
    GuiSurface.Today -> todayPage(),
    GuiSurface.CaptureFact -> capturePage(),
    GuiSurface.Learn -> learnPage(),
    GuiSurface.Search -> searchPage(),
    GuiSurface.Entity -> entityPage()
  )

  private def build(): Unit =
    identify(initializeButton, GuiControl.Initialize, "Start Noesis")
    identify(noteEntry, GuiControl.Note, "Note text")
    identify(saveNoteButton, GuiControl.SaveNote, "Save note")
    identify(subjectEntry, GuiControl.FactSubject, "Fact subject")
    identify(propertyEntry, GuiControl.FactProperty, "Fact property")
    identify(valueEntry, GuiControl.FactValue, "Fact value")
    identify(newSubject, GuiControl.FactNewSubject, "Create this subject")
    identify(previewButton, GuiControl.FactPreview, "Review fact")
    identify(commitButton, GuiControl.FactCommit, "Confirm and commit")
    identify(cancelFactButton, GuiControl.FactCancel, "Cancel fact")
    identify(answerEntry, GuiControl.LearnAnswer, "Review answer")
    identify(answerButton, GuiControl.LearnSubmit, "Submit answer")
    identify(refreshReviewButton, GuiControl.LearnRefresh, "Refresh learning queue")
    identify(searchEntry, GuiControl.SearchQuery, "Search query")
    identify(searchButton, GuiControl.SearchSubmit, "Search")
    identify(entityEntry, GuiControl.SearchEntity, "Entity identifier")
    identify(entityButton, GuiControl.SearchOpenEntity, "Open entity")

    pageNodes.foreach: entry =>
      val (surface, node) = entry
      node.id = surface.id
      node.visible = surface == GuiSurface.FirstRun
      node.managed = surface == GuiSurface.FirstRun
      pages.children.add(node)

    val navigation = VBox(8.0)
    navigation.padding = Insets(16.0)
    navigation.minWidth = 180.0
    GuiSurface.navigable.foreach: surface =>
      val button = Button(surface.title)
      identify(button, GuiControl.navigate(surface), surface.title)
      button.maxWidth = Double.MaxValue
      button.onAction = _ => dispatch(Event.Navigate(surface))
      navigation.children.add(button)

    val title = Label("Noesis")
    title.style = "-fx-font-size: 20px; -fx-font-weight: bold;"
    val header = HBox(12.0, title)
    header.alignment = Pos.CenterLeft
    header.padding = Insets(12.0, 16.0, 12.0, 16.0)
    val root = new BorderPane:
      top = header
      left = navigation
      center = pages
      bottom = feedbackLabel
    BorderPane.setMargin(feedbackLabel, Insets(8.0, 16.0, 12.0, 16.0))

    window.title = "Noesis"
    window.width = 960.0
    window.height = 680.0
    window.scene = Scene(root)
    window.onCloseRequest = event =>
      if workInFlight then
        feedbackLabel.text = "Wait for the current operation to finish"
        feedbackLabel.visible = true
        feedbackLabel.managed = true
        event.consume()

  private def firstRunPage(): Node =
    val page = pageBox(
      "Welcome to Noesis",
      s"Create $workspacePath with owner-only persistence and install its vocabularies."
    )
    initializeButton.onAction = _ => begin(Event.InitializeRequested)
    page.children.add(initializeButton)
    page

  private def todayPage(): Node =
    val page = pageBox("Today", "Due obligations and a quick append-only note.")
    val _ = page.children.addAll(agendaLabel, noteEntry)
    noteEntry.promptText = "Write a thought for today's note"
    saveNoteButton.onAction = _ =>
      dispatch(Event.NoteChanged(noteEntry.text.value))
      begin(Event.SaveNoteRequested)
    page.children.add(saveNoteButton)
    scroll(page)

  private def capturePage(): Node =
    val page = pageBox("Capture a fact", "Enter identifiers, review the formal assertion, then commit.")
    subjectEntry.promptText = "Subject, e.g. marco"
    propertyEntry.promptText = "Property, e.g. crm:worksAt"
    valueEntry.promptText = "Value, identifier, or literal"
    val _ = page.children.addAll(subjectEntry, propertyEntry, valueEntry, newSubject)
    previewButton.onAction = _ =>
      dispatch(Event.FactChanged(factDraft()))
      dispatch(Event.PreviewRequested)
    val _ = page.children.addAll(previewButton, previewLabel)
    commitButton.onAction = _ => begin(Event.CommitFactRequested)
    cancelFactButton.onAction = _ => dispatch(Event.CancelFact)
    val _ = page.children.addAll(commitButton, cancelFactButton)
    scroll(page)

  private def learnPage(): Node =
    val page = pageBox("Learn", "The reason for selection is shown with every queued question.")
    answerEntry.promptText = "Your answer"
    answerButton.onAction = _ =>
      begin(Event.AnswerRequested(answerEntry.text.value))
      answerEntry.text = ""
    refreshReviewButton.onAction = _ => dispatch(Event.ReviewRequested)
    val _ = page.children.addAll(reviewLabel, answerEntry, answerButton, refreshReviewButton)
    scroll(page)

  private def searchPage(): Node =
    val page = pageBox("Search", "Search entities, note blocks, and vocabulary terms locally.")
    searchEntry.promptText = "Search"
    searchButton.onAction = _ =>
      dispatch(Event.SearchChanged(searchEntry.text.value))
      dispatch(Event.SearchRequested)
    entityEntry.promptText = "Entity identifier to open"
    entityButton.onAction = _ =>
      val value = entityEntry.text.value.trim
      if value.nonEmpty then dispatch(Event.EntityRequested(Workspace.iri(value)))
    val _ =
      page.children.addAll(searchEntry, searchButton, searchLabel, searchResults, entityEntry, entityButton)
    scroll(page)

  private def entityPage(): Node =
    val page = pageBox("Entity", "Current states and asserted facts, including belief where available.")
    val back = Button("Back to search")
    identify(back, GuiControl.EntityBack, "Back to search")
    back.onAction = _ => dispatch(Event.Navigate(GuiSurface.Search))
    val _ = page.children.addAll(entityLabel, back)
    scroll(page)

  private def pageBox(title: String, subtitle: String): VBox =
    val heading = Label(title)
    heading.style = "-fx-font-size: 24px; -fx-font-weight: bold;"
    val explanation = Label(subtitle)
    explanation.wrapText = true
    val page = VBox(12.0, heading, explanation)
    page.padding = Insets(24.0)
    page.fillWidth = true
    page

  private def scroll(child: Node): ScrollPane =
    val pane = new ScrollPane:
      content = child
      fitToWidth = true
    VBox.setVgrow(pane, Priority.Always)
    pane

  private def contentLabel(): Label =
    val label = Label("")
    label.wrapText = true
    label.accessibleRole = AccessibleRole.Text
    label

  private def identify(node: Node, control: GuiControl, name: String): Unit =
    identify(node, control.id, name)

  private def identify(node: Node, id: String, name: String): Unit =
    node.id = id
    node.accessibleText = name
    identifiedNodes.update(id, node.delegate)

  private def factDraft(): FactDraft =
    FactDraft(
      subjectEntry.text.value,
      propertyEntry.text.value,
      valueEntry.text.value,
      newSubject.selected.value
    )

  private def begin(event: Event): Unit =
    workInFlight = true
    dispatch(event)

  private def renderSearchResults(rows: List[SearchRow]): Unit =
    searchResults.children.clear()
    rows.foreach:
      case SearchRow.Entity(index, iri, text) =>
        val button = Button(text)
        identify(button, GuiControl.searchResult(index), text)
        button.onAction = _ => dispatch(Event.EntityRequested(iri))
        searchResults.children.add(button)
      case SearchRow.Text(index, text) =>
        val label = contentLabel()
        identify(label, GuiControl.searchResult(index), text)
        label.text = text
        searchResults.children.add(label)
