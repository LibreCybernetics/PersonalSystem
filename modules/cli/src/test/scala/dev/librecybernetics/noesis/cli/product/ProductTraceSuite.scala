package dev.librecybernetics.noesis.cli.product

import java.nio.file.{Files, Path, Paths}

import munit.FunSuite

import dev.librecybernetics.noesis.cli.meta.{CommandSurface, SourceRoot}

/** Where the product documents live.
  *
  * [[SourceRoot]] bakes in the path of the tree that compiled this suite; if that tree has since
  * moved, the working directory sbt runs tests from is the fallback. Failing loudly beats reading
  * an empty document, which would make every rule below pass for the wrong reason.
  */
object Repository:

  private def hasProduct(candidate: Path): Boolean =
    Files.isRegularFile(candidate.resolve("PRODUCT.md"))

  val root: Path =
    List(Paths.get(SourceRoot.path), Paths.get("").toAbsolutePath)
      .find(hasProduct)
      .getOrElse(sys.error(s"no PRODUCT.md under ${SourceRoot.path} or the working directory"))

  def read(name: String): String = Files.readString(root.resolve(name))

/** Traceability between the shipped command surface and `PRODUCT.md`.
  *
  * Product intent cannot be verified mechanically — no check decides whether a journey is worth
  * serving. Its converse can, and it is the drift that actually happens: a command ships and the
  * document claiming to describe who it is for never hears about it.
  *
  * The surface is derived from `Main`'s typed AST by [[CommandSurface]] rather than transcribed,
  * so these rules cannot be satisfied by editing a list.
  */
class ProductTraceSuite extends FunSuite:

  // Baked in when this file is compiled (F1/F2/F6 pass) — see TESTING.md, "the traceability suite can pass against
  // a command surface that no longer exists". Recompile this file after changing `Main`.
  private val surface = CommandSurface.ofModule("dev.librecybernetics.noesis.cli.Main", "main")
  private val product = ProductDocument.parse(Repository.read("PRODUCT.md"))
  private val experience = Repository.read("UX.md")

  private def check(claim: String)(failures: => List[String]): Unit =
    test(claim):
      val found = failures
      assert(found.isEmpty, found.mkString(s"${found.length} failure(s):\n  - ", "\n  - ", ""))

  check("PRODUCT.md defines the journeys and stories the other rules read"):
    val journeys = if product.journeys.isEmpty then List("no journeys are defined") else Nil
    val stories = if product.stories.isEmpty then List("no stories are defined") else Nil
    journeys ++ stories

  check("every subcommand is reachable from the entry point"):
    surface.unreachable.map(name => s"`$name` is declared but never composed into `Main.main`")

  check("every command the CLI ships is exercised by a journey step or acceptance criterion"):
    ProductTrace.uncovered(surface, product).map(command => s"$command appears in neither")

  check("every command PRODUCT.md invokes exists, or is declared as proposed"):
    ProductTrace.unknownInvocations(surface, product, "PRODUCT.md", product.invocations)

  check("every command UX.md invokes exists, or is declared as proposed in PRODUCT.md"):
    ProductTrace.unknownInvocations(
      surface,
      product,
      "UX.md",
      ProductDocument.parseInvocations(experience)
    )

  check("no command is listed as proposed after it has shipped"):
    ProductTrace
      .staleProposals(surface, product)
      .map(command => s"$command ships; move it into a journey and delete the proposal")

  check("every story cites a journey step that exists"):
    ProductTrace.citationsToMissingSteps(product)

  check("every journey is served by at least one story"):
    ProductTrace.unclaimedJourneys(product)

  check("every story states its role, journey, spec, status and acceptance criteria"):
    ProductTrace.malformedStories(product)

  check("every friction and story reference resolves"):
    ProductTrace.danglingReferences(product)

  check("every command's help text follows the conventions in UX.md"):
    ProductTrace.helpViolations(surface)

  check("no two sibling commands share a name"):
    ProductTrace.duplicateSiblings(surface)
