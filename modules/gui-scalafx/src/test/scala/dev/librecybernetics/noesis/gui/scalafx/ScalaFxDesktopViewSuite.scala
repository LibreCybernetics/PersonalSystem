package dev.librecybernetics.noesis.gui.scalafx

import java.util.concurrent.atomic.AtomicReference

import javafx.event.{ActionEvent, Event as JfxEvent}
import munit.FunSuite
import scalafx.stage.Stage

import dev.librecybernetics.noesis.gui.*
import dev.librecybernetics.noesis.logic.Iri

/** Every shared presentation field reaches the real ScalaFX scene graph under Xvfb. */
class ScalaFxDesktopViewSuite extends FunSuite:
  test("the ScalaFX tree preserves surfaces, controls, accessibility, and dispatched actions"):
    val events = AtomicReference(List.empty[Event])
    val pair = FxTestRuntime.onFx:
      val stage = Stage()
      val view = ScalaFxDesktopView(stage, "/tmp/noesis-scalafx-test", event =>
        val _ = events.updateAndGet(existing => event :: existing)
      )
      stage -> view
    val (stage, view) = pair
    try
      val presentation = DesktopPresentation(
        surface = GuiSurface.Learn,
        agenda = "Nothing due.",
        preview = "Fact preview",
        review = "Name?\nWhy now: belief is low",
        search = "1 match(es).",
        searchRows = List(SearchRow.Entity(0, Iri("noesis:e/marco"), "Open Marco — noesis:e/marco")),
        entity = "Marco\nnoesis:e/marco",
        feedback = Some("Ready"),
        busy = false,
        commitEnabled = true,
        cancelEnabled = true,
        answerEnabled = true,
        clearNoteDraft = true,
        clearFactDraft = true
      )
      FxTestRuntime.onFx:
        view.render(presentation)
        view.present()

      val snapshot = FxTestRuntime.onFx(view.snapshot)
      assertEquals(snapshot.surface, "gui-learn")
      assertEquals(snapshot.review, "Name?\nWhy now: belief is low")
      assertEquals(snapshot.feedback, "Ready")
      assert(snapshot.commitEnabled)
      assert(snapshot.answerEnabled)

      GuiControl.values.foreach: control =>
        val node = FxTestRuntime.onFx(view.node(control.id)).getOrElse(fail(s"missing ${control.id}"))
        assertEquals(node.getId, control.id)
        assert(Option(node.getAccessibleText).exists(_.nonEmpty), s"missing accessible text for ${control.id}")
      GuiSurface.navigable.foreach: surface =>
        assert(FxTestRuntime.onFx(view.node(GuiControl.navigate(surface))).nonEmpty)
      assert(FxTestRuntime.onFx(view.node(GuiControl.searchResult(0))).nonEmpty)

      val today = FxTestRuntime
        .onFx(view.node(GuiControl.navigate(GuiSurface.Today)))
        .getOrElse(fail("missing Today navigation"))
      FxTestRuntime.onFx(JfxEvent.fireEvent(today, ActionEvent()))
      assertEquals(events.get().headOption, Some(Event.Navigate(GuiSurface.Today)))
    finally FxTestRuntime.onFx(stage.close())

  test("busy presentation consumes close and exposes the actionable announcement"):
    val pair = FxTestRuntime.onFx:
      val stage = Stage()
      val view = ScalaFxDesktopView(stage, "/tmp/noesis-scalafx-test", _ => ())
      view.render(DesktopPresentation.from(Model(busy = true)))
      view.present()
      stage -> view
    val (stage, view) = pair
    try
      FxTestRuntime.onFx(stage.fireEvent(javafx.stage.WindowEvent(stage.delegate, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)))
      assertEquals(FxTestRuntime.onFx(stage.showing.value), true)
      assertEquals(FxTestRuntime.onFx(view.snapshot.feedback), "Wait for the current operation to finish")
    finally
      FxTestRuntime.onFx:
        view.render(DesktopPresentation.from(Model()))
        stage.close()
