package dev.librecybernetics.noesis.cli.meta

import java.nio.file.{Files, Path}

import scala.quoted.*

/** The repository root, resolved from the compiling source file's own location.
  *
  * A check that reads `PRODUCT.md` has to find it. Every other file-reading suite in this
  * repository uses classpath resources or temporary directories, so there is no working-directory
  * convention to lean on, and inventing one would make the check pass or fail depending on how the
  * runner was invoked. The macro-expansion position knows where the source lives; walking up from
  * there to the directory holding `build.sbt` is exact.
  *
  * The result is an absolute path baked in at compile time, so callers should treat it as a
  * starting point and fall back if the tree has moved since — see `Repository` in the test sources.
  */
object SourceRoot:

  inline def path: String = ${ pathImpl }

  private def pathImpl(using Quotes): Expr[String] =
    import quotes.reflect.*

    def upward(from: Path): Option[Path] =
      if Files.isRegularFile(from.resolve("build.sbt")) then Some(from)
      else Option(from.getParent).flatMap(upward)

    val expansion = Position.ofMacroExpansion.sourceFile.getJPath
      .getOrElse(report.errorAndAbort("this macro needs a source file to locate the repository"))

    val root = Option(expansion.toAbsolutePath.getParent)
      .flatMap(upward)
      .getOrElse(report.errorAndAbort(s"no build.sbt above $expansion"))

    Expr(root.toString)
