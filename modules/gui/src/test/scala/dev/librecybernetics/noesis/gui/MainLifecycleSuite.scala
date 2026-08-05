package dev.librecybernetics.noesis.gui

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.io.file.Files
import munit.FunSuite
import org.gnome.adw.Application
import org.gnome.glib.GLib

/** Runs the real application lifecycle under Xvfb and closes it from the GLib event loop. */
class MainLifecycleSuite extends FunSuite:
  test("normal startup acquires and releases the controller resource"):
    Files[IO].tempDirectory.use: root =>
      IO.blocking:
        val application = Application("dev.librecybernetics.Noesis")
        var idleRan = false
        UiScheduler.glib(() => idleRan = true).unsafeRunSync()
        val _ = GLib.idleAddOnce(() =>
          application.activate()
          application.quit()
        )
        Main.launch(Array("--workspace", root.toString), () => application)
        assert(idleRan, "the live GLib scheduler did not execute its queued action")
    .unsafeRunSync()

  test("invalid startup arguments use the owner failure rubric"):
    val bytes = new ByteArrayOutputStream()
    val replacement = new PrintStream(bytes, true, StandardCharsets.UTF_8)
    val original = System.err
    try
      System.setErr(replacement)
      Main.main(Array("--unknown"))
    finally
      System.setErr(original)
      replacement.close()
    assertEquals(
      bytes.toString(StandardCharsets.UTF_8),
      s"Noesis did not start\nthe desktop accepts only --workspace PATH\n" +
        s"run noesis-gui --workspace /path/to/workspace${System.lineSeparator()}"
    )

  test("the public entry point owns the production application it starts"):
    Files[IO].tempDirectory.use: root =>
      IO.blocking:
        var applicationId: Option[String] = None
        val _ = GLib.idleAddOnce(() =>
          val application = org.gnome.gio.Application.getDefault()
          applicationId = Some(application.getApplicationId())
          application.quit()
        )
        Main.main(Array("--workspace", root.toString))
        assertEquals(applicationId, Some("dev.librecybernetics.Noesis"))
    .unsafeRunSync()
