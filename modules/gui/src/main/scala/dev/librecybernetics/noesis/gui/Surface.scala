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

