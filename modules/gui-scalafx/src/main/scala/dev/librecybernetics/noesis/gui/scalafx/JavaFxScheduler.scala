package dev.librecybernetics.noesis.gui.scalafx

import cats.effect.IO
import javafx.application.Platform

import dev.librecybernetics.noesis.gui.UiScheduler

/** Places shared desktop rendering on the JavaFX application thread (SPEC §2.2). */
private[scalafx] object JavaFxScheduler extends UiScheduler:
  def apply(action: () => Unit): IO[Unit] =
    if Platform.isFxApplicationThread then IO(action())
    else IO(Platform.runLater(() => action()))
