package noesis.cli

import cats.data.NonEmptyList
import cats.effect.std.{SecureRandom, UUIDGen}
import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import noesis.core.capture.Intent
import noesis.core.event.Events
import noesis.journal.{JsonLines, JsonLinesJournal}
import noesis.core.kb.{KbConfig, KnowledgeBase}
import noesis.logic.*
import noesis.lms.{LearningEngine, Review}
import noesis.vocab.Modules

/** An opened workspace: the journal, the knowledge base, and a rehydrated learning engine.
  *
  * Assembling this on every invocation is the point of the architecture, not a cost: everything
  * except the journal and the review log is a projection (SPEC §3.2), so a cold start reads two
  * append-only files and recomputes the rest. There is no cache to invalidate across processes and no
  * state that can be stale on disk.
  */
final class Workspace(
    val root: Path,
    val kb: KnowledgeBase[IO],
    val engine: LearningEngine[IO],
    val reviewLogPath: Path
):
  /** Persists a review so the next invocation sees it. */
  def recordReview(review: Review): IO[Unit] =
    JsonLines.append(Files[IO], reviewLogPath, List(review))

object Workspace:
  val defaultRoot: Path = Path(sys.props.getOrElse("user.home", ".")) / ".noesis"

  given SecureRandom[IO] = SecureRandom.javaSecuritySecureRandom[IO].unsafeRunSync()(using
    cats.effect.unsafe.implicits.global
  )
  given UUIDGen[IO] = UUIDGen.fromSecureRandom[IO]

  def journalPath(root: Path): Path = root / "journal.jsonl"
  def reviewsPath(root: Path): Path = root / "reviews.jsonl"

  private val modules = Modules.all
  val config: KbConfig = Modules.configure(KbConfig.default, modules)

  /** Opens (creating if needed) the workspace at `root`. */
  def open(root: Path): IO[Workspace] =
    for
      journal <- JsonLinesJournal.open[IO](journalPath(root))
      kb <- KnowledgeBase[IO](journal, config)
      engine <- LearningEngine[IO](kb, Modules.itemPolicies(modules), config.policies)

      // Rebuild learning items by replaying the journal's events, then fold in the review log.
      entries <- journal.stream.compile.toList
      _ <- engine.handle(Events.replay(entries))
      reviews <- JsonLines.read[IO, Review](Files[IO], reviewsPath(root))
      _ <- engine.restore(reviews)
    yield new Workspace(root, kb, engine, reviewsPath(root))

  /** Installs every module's ontology into a fresh workspace (SPEC §5.1). */
  def install(workspace: Workspace): IO[List[String]] =
    for
      state <- workspace.kb.state
      ontology = Modules.ontology(modules).distinct
      // Only assert what is missing, so `init` is idempotent.
      missing = ontology.filterNot(axiom => state.axioms.contains(axiom.id))
      result <- NonEmptyList.fromList(missing.map(Intent.Assert(_))) match
        case None => List("ontology already installed; nothing to do").pure[IO]
        case Some(intents) =>
          workspace.kb.commit(intents).map {
            case Left(rejected) => List(rejected.render)
            case Right(commit) =>
              val warnings = commit.profileWarnings.map((axiom, why) =>
                s"  EL profile warning: ${axiom.manchester} — $why"
              )
              s"installed ${missing.length} ontology axioms from ${modules.length} modules" ::
                warnings
          }
    yield result

  /** Resolves a user-supplied token to an IRI, accepting module-prefixed and entity forms.
    *
    * Bare words become entity IRIs under the `noesis:e/` namespace, so `noesis assert alice ...`
    * works without the owner typing UUIDs — the opaque-IRI rule (§3.1) is about renames not breaking
    * references, not about the CLI being unusable.
    */
  def iri(token: String): Iri =
    if token.contains(':') then Iri(token) else Iri(s"noesis:e/$token")
