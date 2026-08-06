package dev.librecybernetics.noesis.gui

import cats.effect.IO
import org.gnome.glib.GLib

/** Places shared desktop rendering on GLib's owning main context (SPEC §2.1). */
private[gui] object GtkScheduler extends UiScheduler:
  def apply(action: () => Unit): IO[Unit] = IO.delay(GLib.idleAddOnce(() => action())).void
