package dev.librecybernetics.noesis.app

import cats.data.NonEmptyList
import cats.effect.std.{SecureRandom, UUIDGen}
import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import dev.librecybernetics.noesis.core.capture.Intent
import dev.librecybernetics.noesis.core.event.Events
import dev.librecybernetics.noesis.journal.{JsonLines, JsonLinesJournal}
import dev.librecybernetics.noesis.core.kb.{CommitRejected, KbConfig, KnowledgeBase}
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.lms.{LearningEngine, Review}
import dev.librecybernetics.noesis.vocab.Modules

/** An opened owner workspace shared by every local presentation (SPEC §2.1, §3.2).
  *
  * Assembly lives below CLI and GUI so replay, module configuration and learning restoration cannot
  * drift by surface. The journal and review log remain the only durable values; this object is a
  * rehydratable process projection.
  */
final class Workspace(
    val root: Path,
    val kb: KnowledgeBase[IO],
    val engine: LearningEngine[IO],
    val reviewLogPath: Path,
    private[app] val uuidGen: UUIDGen[IO]
):
  def recordReview(review: Review): IO[Unit] =
    JsonLines.append(reviewLogPath, List(review))

object Workspace:
  val defaultRoot: Path = Path(sys.props.getOrElse("user.home", ".")) / ".noesis"

  def journalPath(root: Path): Path = root / "journal.jsonl"
  def reviewsPath(root: Path): Path = root / "reviews.jsonl"

  private val modules = Modules.all
  val config: KbConfig = Modules.configure(KbConfig.default, modules)

  def open(root: Path): IO[Workspace] =
    SecureRandom.javaSecuritySecureRandom[IO].flatMap: random =>
      given SecureRandom[IO] = random
      open(root, UUIDGen.fromSecureRandom[IO])

  /** Testable assembly seam; UUID generation is an effect owned by the opened session, not global
    * initialization hidden behind `unsafeRunSync` (DESIGN, Effect boundaries).
    */
  private[app] def open(root: Path, uuidGen: UUIDGen[IO]): IO[Workspace] =
    for
      journal <- JsonLinesJournal.open[IO](journalPath(root))
      kb <- KnowledgeBase[IO](journal, config)
      engine <- LearningEngine[IO](kb, Modules.itemPolicies(modules), config.policies)
      entries <- journal.stream.compile.toList
      _ <- engine.handle(Events.replay(entries))
      reviews <- JsonLines.read[IO, Review](Files[IO], reviewsPath(root))
      _ <- engine.restore(reviews)
    yield Workspace(root, kb, engine, reviewsPath(root), uuidGen)

  /** Installs every module ontology through the ordinary consistency-checked commit path. */
  def install(workspace: Workspace): IO[Either[CommitRejected, List[String]]] =
    for
      state <- workspace.kb.state
      ontology = Modules.ontology(modules).distinct
      missing = ontology.filterNot(axiom => state.axioms.contains(axiom.id))
      result <- NonEmptyList.fromList(missing.map(Intent.Assert(_))) match
        case None => Right(List("ontology already installed; nothing to do")).pure[IO]
        case Some(intents) =>
          workspace.kb.commit(intents).map:
            case Left(rejected) => Left(rejected)
            case Right(commit) =>
              val warnings = commit.profileWarnings.map((axiom, why) =>
                s"  EL profile warning: ${axiom.manchester} — $why"
              )
              Right(
                s"installed ${missing.length} ontology axioms from ${modules.length} modules" ::
                  warnings
              )
    yield result

  /** CLI notation and the shared fallback for explicit new-entity identifiers. */
  def iri(token: String): Iri =
    if token.contains(':') then Iri(token) else Iri(s"noesis:e/$token")
