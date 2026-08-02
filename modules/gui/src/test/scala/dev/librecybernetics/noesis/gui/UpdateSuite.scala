package dev.librecybernetics.noesis.gui

import munit.FunSuite

/** Display-independent interaction transcripts for the GNOME Model-View-Update loop. */
class UpdateSuite extends FunSuite:
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
