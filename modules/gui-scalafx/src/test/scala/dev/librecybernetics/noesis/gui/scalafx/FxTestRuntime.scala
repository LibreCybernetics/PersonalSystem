package dev.librecybernetics.noesis.gui.scalafx

import java.util.concurrent.{CountDownLatch, FutureTask, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import javafx.application.Platform

/** Starts JavaFX once per forked test JVM and provides a total application-thread boundary. */
private[scalafx] object FxTestRuntime:
  private val started = AtomicBoolean(false)
  private val ready = CountDownLatch(1)

  def ensureStarted(): Unit =
    if started.compareAndSet(false, true) then
      Platform.startup: () =>
        // Suites create and close several independent stages. The production default exits when
        // the last one closes; the shared test runtime must remain alive for the next case.
        Platform.setImplicitExit(false)
        ready.countDown()
    assert(ready.await(10L, TimeUnit.SECONDS), "JavaFX platform did not start")

  def onFx[A](action: => A): A =
    ensureStarted()
    if Platform.isFxApplicationThread then action
    else
      val task = FutureTask[A](() => action)
      Platform.runLater(task)
      task.get(10L, TimeUnit.SECONDS)
