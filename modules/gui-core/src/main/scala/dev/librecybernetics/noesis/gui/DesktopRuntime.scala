package dev.librecybernetics.noesis.gui

import cats.effect.{IO, Resource}
import cats.effect.std.Dispatcher
import fs2.io.file.Path

import dev.librecybernetics.noesis.app.OwnerSession

/** Owns the presentation-neutral desktop resources for one window lifetime (SPEC §2.1–§2.2). */
object DesktopRuntime:
  def resource(
      root: Path,
      window: DesktopViewHandle,
      scheduler: UiScheduler
  ): Resource[IO, ReactiveController] =
    for
      dispatcher <- Dispatcher.sequential[IO]
      session <- OwnerSession.open(root)
      actions = OwnerActions.live(session)
      controller <- Resource.eval(
        ReactiveController.create(
          actions,
          Effects(actions, GuiClock.live),
          dispatcher,
          window,
          scheduler
        )
      )
      _ <- controller.start
    yield controller
