package dev.librecybernetics.noesis.gui

/** Owner-visible desktop surfaces, kept as data so PRODUCT.md traceability cannot pass vacuously. */
enum GuiSurface(val id: String, val title: String):
  case FirstRun extends GuiSurface("gui:first-run", "Welcome")
  case Today extends GuiSurface("gui:today", "Today")
  case CaptureFact extends GuiSurface("gui:capture-fact", "Capture")
  case CaptureNote extends GuiSurface("gui:capture-note", "Today")
  case Search extends GuiSurface("gui:search", "Search")
  case Entity extends GuiSurface("gui:entity", "Entity")
  case Learn extends GuiSurface("gui:learn", "Learn")

object GuiSurface:
  val navigable: List[GuiSurface] = List(Today, CaptureFact, Learn, Search)

/** Stable automation and accessibility handles shared by every desktop renderer (UX §10.1). */
enum GuiControl(val id: String):
  case Initialize extends GuiControl("gui:first-run:start")
  case Note extends GuiControl("gui:today:note")
  case SaveNote extends GuiControl("gui:today:save-note")
  case FactSubject extends GuiControl("gui:capture-fact:subject")
  case FactProperty extends GuiControl("gui:capture-fact:property")
  case FactValue extends GuiControl("gui:capture-fact:value")
  case FactNewSubject extends GuiControl("gui:capture-fact:new-subject")
  case FactPreview extends GuiControl("gui:capture-fact:preview")
  case FactCommit extends GuiControl("gui:capture-fact:commit")
  case FactCancel extends GuiControl("gui:capture-fact:cancel")
  case LearnAnswer extends GuiControl("gui:learn:answer")
  case LearnSubmit extends GuiControl("gui:learn:submit")
  case LearnRefresh extends GuiControl("gui:learn:refresh")
  case SearchQuery extends GuiControl("gui:search:query")
  case SearchSubmit extends GuiControl("gui:search:submit")
  case SearchEntity extends GuiControl("gui:search:entity")
  case SearchOpenEntity extends GuiControl("gui:search:open-entity")
  case EntityBack extends GuiControl("gui:entity:back")

object GuiControl:
  def navigate(surface: GuiSurface): String = s"gui:navigate:${surface.id.stripPrefix("gui:")}"
  def searchResult(index: Int): String = s"gui:search:result:$index"
