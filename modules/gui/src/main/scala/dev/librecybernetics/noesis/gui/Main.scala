package dev.librecybernetics.noesis.gui

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import fs2.io.file.Path
import org.gnome.adw.Application

import dev.librecybernetics.noesis.app.{OwnerProblem, OwnerSession, Workspace}

/** GNOME entry point. GTK owns the main thread; Cats Effect owns the serial reactive event loop. */
object Main:
  def main(args: Array[String]): Unit =
    if args.toList == List("--smoke") then DesktopSmoke.main(Array.empty[String])
    else Arguments.parse(args.toList) match
      case Left(problem) => System.err.println(problem.render)
      case Right(root) =>
        val (dispatcher, closeDispatcher) = Dispatcher.sequential[IO].allocated.unsafeRunSync()
        val (session, closeSession) = OwnerSession.open(root).allocated.unsafeRunSync()
        val application = Application("dev.librecybernetics.Noesis")
        var controller: Option[ReactiveController] = None
        var closeController: IO[Unit] = IO.unit
        val _ = application.onActivate: () =>
          controller match
            case Some(active) => active.present()
            case None =>
              val active = ReactiveController
                .create(
                  session,
                  dispatcher,
                  dispatch => DesktopView(application, root.toString, dispatch)
              )
                .unsafeRunSync()
              controller = Some(active)
              val allocated = active.start.allocated.unsafeRunSync()
              closeController = allocated._2
              active.present()
        try
          val _ = application.run(Array.empty[String])
        finally
          closeController.unsafeRunSync()
          closeSession.unsafeRunSync()
          closeDispatcher.unsafeRunSync()

private object Arguments:
  def parse(args: List[String]): Either[OwnerProblem, Path] =
    args match
      case Nil => Right(Workspace.defaultRoot)
      case "--workspace" :: value :: Nil if value.trim.nonEmpty => Right(Path(value))
      case _ =>
        Left(
          OwnerProblem(
            "Noesis did not start",
            "the desktop accepts only --workspace PATH",
            "run noesis-gui --workspace /path/to/workspace"
          )
        )
