package dev.librecybernetics.noesis.gui

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.io.file.Path
import org.gnome.adw.Application

/** GNOME entry point. GTK owns the main thread; Cats Effect owns the serial reactive event loop. */
object Main:
  def main(args: Array[String]): Unit =
    launch(args, () => Application("dev.librecybernetics.Noesis"))

  /** Keeps application construction explicit so a lifecycle test owns the GTK value it stops. */
  private[gui] def launch(args: Array[String], createApplication: () => Application): Unit =
    if args.toList == List("--smoke") then DesktopSmoke.main(Array.empty[String])
    else DesktopArguments.parse(args.toList, "noesis-gui") match
      case Left(problem) => System.err.println(problem.render)
      case Right(root) => run(root, createApplication())

  /** Runs one application instance so lifecycle tests never consult a stale GLib default. */
  private[gui] def run(root: Path, application: Application): Unit =
    application.setDefault()
    var controller: Option[ReactiveController] = None
    var closeRuntime: IO[Unit] = IO.unit
    val _ = application.onActivate: () =>
      controller match
        case Some(active) => active.present()
        case None =>
          val window = DesktopViewHandle(dispatch => DesktopView(application, root.toString, dispatch))
          val allocated = DesktopRuntime
            .resource(
              root,
              window,
              GtkScheduler
            )
            .allocated
            .unsafeRunSync()
          val active = allocated._1
          controller = Some(active)
          closeRuntime = allocated._2
          active.present()
    try
      val _ = application.run(Array.empty[String])
    finally
      closeRuntime.unsafeRunSync()
