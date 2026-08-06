package dev.librecybernetics.noesis.gui

import java.util.concurrent.atomic.AtomicReference

/** Constructs a toolkit view on its owning thread before the effect runtime binds event delivery.
  *
  * GTK and JavaFX both require scene construction on their application thread, while controller
  * allocation may cross Cats Effect execution contexts. The relay makes that ordering explicit
  * without teaching the shared controller about either toolkit (SPEC §2.1–§2.2).
  */
final class DesktopViewHandle private (
    val view: GuiView,
    target: AtomicReference[Event => Unit]
):
  private[gui] def bind(dispatch: Event => Unit): Unit = target.set(dispatch)

object DesktopViewHandle:
  def apply(factory: (Event => Unit) => GuiView): DesktopViewHandle =
    val target = AtomicReference[Event => Unit](_ => ())
    val relay = (event: Event) => target.get().apply(event)
    new DesktopViewHandle(factory(relay), target)
