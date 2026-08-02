package dev.librecybernetics.noesis.gui

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import munit.FunSuite

/** Traceability guard for the finite desktop surface, parallel to CLI `ProductTraceSuite`. */
class GuiProductTraceSuite extends FunSuite:
  private val product =
    val root = locate(Paths.get("").toAbsolutePath)
    Files.readString(root.resolve("PRODUCT.md"), StandardCharsets.UTF_8)

  test("every shipped GUI surface appears in a product journey"):
    GuiSurface.values.foreach(surface => assert(product.contains(surface.id), surface.id))

  test("the desktop journey does not claim its shipped surfaces are proposed"):
    val proposed = "### Proposed GUI surfaces"
    assert(!product.contains(proposed), s"remove the stale `$proposed` block")

  private def locate(from: Path): Path =
    Iterator
      .iterate(from)(_.getParent)
      .takeWhile(path => Option(path).nonEmpty)
      .find(path => Files.isRegularFile(path.resolve("PRODUCT.md")))
      .getOrElse(fail(s"no PRODUCT.md above $from"))

