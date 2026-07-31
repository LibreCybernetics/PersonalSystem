package noesis.cli.meta

import munit.FunSuite

/** The derivation itself: that the tree read out of `Main`'s typed AST is the tree decline builds.
  *
  * These are claims about the *deriving*, not about the product; `ProductTraceSuite` owns the
  * traceability rules that consume the result.
  */
class CommandSurfaceSuite extends FunSuite:

  private val surface = CommandSurface.ofModule("noesis.cli.Main", "main")

  test("the surface is derived, and every declared subcommand is composed into the entry point") {
    assert(surface.commands.nonEmpty, "no commands were derived")
    assertEquals(surface.unreachable, Nil)
  }

  test("nesting is read from composition, at every depth") {
    assert(surface.leaves.contains(List("init")))
    assert(surface.leaves.contains(List("contact", "method-retire")))
    assert(surface.leaves.contains(List("archive", "restore")))
  }

  test("a container command is a path but never a leaf") {
    assert(surface.paths.contains(List("contact")))
    assert(!surface.leaves.contains(List("contact")))
    assert(surface.paths.contains(List("archive")))
    assert(!surface.leaves.contains(List("archive")))
  }

  /** The hazard any textual scan of `Main.scala` has to work around: `archiveCreate`'s help text
    * mentions "archive-directory", and its parent is named `archive`. Reading references by symbol
    * rather than by name makes the mistake unrepresentable, so this pins that it stays that way.
    */
  test("a command's own help text cannot be mistaken for structure") {
    assert(!surface.paths.exists(path => path.count(_ == "archive") > 1))
    assertEquals(surface.paths.count(_.startsWith(List("archive"))), 4)
  }

  test("decline's own help text is carried, so UX conventions can be checked against it") {
    val init = surface.nodes.filter(_.name == "init")
    assertEquals(init.map(_.help), List("create the workspace and install module ontologies"))
  }

  test("no command is derived twice") {
    assertEquals(surface.leaves.distinct.length, surface.leaves.length)
  }
