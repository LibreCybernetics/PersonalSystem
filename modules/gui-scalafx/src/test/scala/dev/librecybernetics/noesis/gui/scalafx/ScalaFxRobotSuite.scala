package dev.librecybernetics.noesis.gui.scalafx

import java.util.concurrent.{Callable, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicReference

import cats.effect.IO
import fs2.io.file.Files
import munit.CatsEffectSuite
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import scalafx.stage.Stage

import dev.librecybernetics.noesis.app.OwnerSession
import dev.librecybernetics.noesis.gui.*

/** One real robot path proves canonical selectors reach durable behavior, not only a scene snapshot. */
class ScalaFxRobotSuite extends CatsEffectSuite:
  test("TestFX initializes and saves a note through the shared controller"):
    Files[IO].tempDirectory.use: root =>
      val stage = FxTestRuntime.onFx(Stage())
      val viewRef = AtomicReference(Option.empty[ScalaFxDesktopView])
      val window = FxTestRuntime.onFx:
        DesktopViewHandle: dispatch =>
          val view = ScalaFxDesktopView(stage, root.toString, dispatch)
          viewRef.set(Some(view))
          view
      val (controller, release) = DesktopRuntime.resource(root, window, JavaFxScheduler).allocated.unsafeRunSync()
      val view = viewRef.get().getOrElse(fail("ScalaFX view was not constructed"))
      val run = for
        _ <- IO.blocking(FxTestRuntime.onFx(controller.present()))
        _ <- IO.blocking:
          val robot = FxRobot().targetWindow(stage.delegate)
          robot.clickOn(byId(view, GuiControl.Initialize.id))
          waitUntil(
            view.snapshot.surface == "gui-today" &&
              view.node(GuiControl.SaveNote.id).exists(node => !node.isDisabled)
          )
        before <- OwnerSession.open(root).use(_.position)
        _ <- IO.blocking:
          val robot = FxRobot().targetWindow(stage.delegate)
          robot.clickOn(byId(view, GuiControl.Note.id)).write("Met Marco.")
          robot.clickOn(byId(view, GuiControl.SaveNote.id))
          try waitUntil(view.snapshot.feedback.contains("Note saved"))
          catch
            case _: TimeoutException =>
              val evidence = FxTestRuntime.onFx(view.snapshot)
              fail(s"note did not persist: snapshot=$evidence")
        after <- OwnerSession.open(root).use(_.position)
        snapshot <- IO.blocking(FxTestRuntime.onFx(view.snapshot))
      yield
        assertEquals(snapshot.surface, "gui-today")
        assert(snapshot.feedback.contains("Note saved"))
        assert(after.journalSequence > before.journalSequence)
        assertEquals(after.reviews, before.reviews)
      run.guarantee(
        release *> IO.blocking(FxTestRuntime.onFx(stage.close()))
      )

  private def byId(view: ScalaFxDesktopView, id: String): javafx.scene.Node =
    FxTestRuntime.onFx(view.node(id)).getOrElse(fail(s"missing $id"))

  private def waitUntil(condition: => Boolean): Unit =
    WaitForAsyncUtils.waitFor(
      10L,
      TimeUnit.SECONDS,
      new Callable[java.lang.Boolean]:
        def call(): java.lang.Boolean = java.lang.Boolean.valueOf(FxTestRuntime.onFx(condition))
    )
