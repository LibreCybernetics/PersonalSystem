package dev.librecybernetics.noesis.gui

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.io.file.Files
import munit.FunSuite
import org.gnome.gio.Application
import org.gnome.glib.GLib

/** Runs the real application lifecycle under Xvfb and closes it from the GLib event loop. */
class MainLifecycleSuite extends FunSuite:
  test("normal startup acquires and releases the controller resource"):
    Files[IO].tempDirectory.use: root =>
      IO.blocking:
        val _ = GLib.timeoutAdd(0, 50, () =>
          val application = Application.getDefault()
          if application.getApplicationId() == "dev.librecybernetics.Noesis" then
            application.quit()
            false
          else true
        )
        Main.main(Array("--workspace", root.toString))
    .unsafeRunSync()
