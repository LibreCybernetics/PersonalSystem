package dev.librecybernetics.noesis.gui.scalafx

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import javafx.application.Platform
import scalafx.application.JFXApp3

import dev.librecybernetics.noesis.gui.*

/** ScalaFX entry point over the same local-owner runtime as the default GTK client. */
object Main extends JFXApp3:
  private var closeRuntime: IO[Unit] = IO.unit

  def start(): Unit =
    if parameters.raw.toList == List("--smoke") then runSmoke()
    else
      DesktopArguments.parse(parameters.raw.toList, "noesis-gui-scalafx") match
        case Left(problem) =>
          System.err.println(problem.render)
          Platform.exit()
        case Right(root) =>
          val primary = new JFXApp3.PrimaryStage()
          val window = DesktopViewHandle(dispatch => ScalaFxDesktopView(primary, root.toString, dispatch))
          val allocated = DesktopRuntime
            .resource(
              root,
              window,
              JavaFxScheduler
            )
            .allocated
            .unsafeRunSync()
          closeRuntime = allocated._2
          stage = primary
          allocated._1.present()

  override def stopApp(): Unit = closeRuntime.unsafeRunSync()

  private def runSmoke(): Unit =
    val primary = new JFXApp3.PrimaryStage()
    val view = ScalaFxDesktopView(primary, "/tmp/noesis-scalafx-smoke", _ => ())
    view.render(DesktopPresentation.from(Model(surface = GuiSurface.FirstRun)))
    stage = primary
    primary.show()
    println(s"gui:first-run window=${primary.title.value} surfaces=${GuiSurface.values.length}")
    Platform.runLater(() => Platform.exit())
