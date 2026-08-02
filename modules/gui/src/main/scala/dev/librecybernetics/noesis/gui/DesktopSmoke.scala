package dev.librecybernetics.noesis.gui

import org.gnome.adw.Application

/** Xvfb smoke entry point: constructs and renders the shipped widget tree without touching a log. */
object DesktopSmoke:
  def main(args: Array[String]): Unit =
    val application = Application("dev.librecybernetics.Noesis.Smoke")
    val _ = application.onActivate: () =>
      val view = DesktopView(application, "/tmp/noesis-smoke", _ => ())
      view.render(Model(surface = GuiSurface.FirstRun))
      println(s"gui:first-run window=${view.window.getTitle} surfaces=${GuiSurface.values.length}")
      application.quit()
    val _ = application.run(Array.empty[String])
