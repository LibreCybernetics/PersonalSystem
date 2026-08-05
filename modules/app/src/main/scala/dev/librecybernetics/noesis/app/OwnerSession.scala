package dev.librecybernetics.noesis.app

import java.time.LocalDate
import scala.concurrent.duration.*

import cats.Eq
import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.effect.std.{SecureRandom, UUIDGen}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Files

import dev.librecybernetics.noesis.core.capture.Intent
import dev.librecybernetics.noesis.core.kb.{CommitRejected, CommitResult}
import dev.librecybernetics.noesis.core.policy.PolicyCascade
import dev.librecybernetics.noesis.core.projection.{AxiomRecord, KbState}
import dev.librecybernetics.noesis.core.verbalize.Naming
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.lms.*
import dev.librecybernetics.noesis.vocab.*

/** A failure rendered by any owner surface in the what/why/next order of UX §4. */
final case class OwnerProblem(what: String, why: String, next: String):
  def render: String = s"$what\n$why\n$next"

enum EntityChoice:
  case Existing(iri: Iri)
  case New(iri: Iri, label: String)

final case class FactInput(
    subject: EntityChoice,
    property: Iri,
    value: String,
    annotations: AxiomAnnotations = AxiomAnnotations.ownerConfirmed
)

/** The exact formal value the owner reviews; only this module can construct one. */
final class CommitPreview private[noesis] (
    val axioms: NonEmptyList[Axiom],
    val annotations: AxiomAnnotations,
    val verbalization: String,
    val sensitivity: Sensitivity,
    val utility: Double,
    val confidence: Double
):
  def axiom: Axiom = axioms.head

final case class CommitOutcome(result: CommitResult, draftedItems: List[Item], messages: List[String] = Nil)

final case class FactView(id: AxiomId, verbalization: String, manchester: String, belief: Option[Double])
final case class EntityView(label: String, iri: Iri, facts: List[FactView], states: List[String])
final case class AgendaView(
    due: LocalDate,
    summary: String,
    subject: Iri,
    subjectLabel: String,
    overdue: Boolean
)
final case class ReviewPrompt(entry: QueueEntry, question: Option[Question])

enum SearchHit:
  case Entity(iri: Iri, label: String)
  case NoteBlock(note: Iri, title: String, block: Iri, text: String)
  case Term(term: Vocabulary.Term)

final case class SessionPosition(journalSequence: Long, reviews: Int)

object SessionPosition:
  given Eq[SessionPosition] = Eq.fromUniversalEquals

/** Presentation-neutral owner use cases (SPEC §2.1).
  *
  * Each operation opens from the durable logs. That intentionally retains the CLI's cold-replay
  * semantics in a long-lived desktop process: a specialist CLI command made while the window is
  * open is visible to the next effect without a second cache-invalidation protocol.
  */
final class OwnerSession private (val root: fs2.io.file.Path, uuidGen: UUIDGen[IO]):
  private val modules = Modules.all
  private val vocabulary = Vocabulary.of(modules)

  private def workspace: IO[Workspace] = Workspace.open(root, uuidGen)

  def position: IO[SessionPosition] =
    for
      opened <- workspace
      state <- opened.kb.state
      reviews <- opened.engine.reviewLog
    yield SessionPosition(state.seq, reviews.length)

  def changes: Stream[IO, SessionPosition] =
    (Stream.emit(()) ++ Stream.awakeEvery[IO](2.seconds).void)
      .evalMap(_ => position)
      .changes

  def initialized: IO[Boolean] =
    Files[IO].exists(Workspace.journalPath(root)).flatMap:
      case false => false.pure[IO]
      case true =>
        workspace.flatMap(_.kb.state).map: state =>
          Modules.ontology(modules).forall(axiom => state.axioms.contains(axiom.id))

  def initialize: IO[Either[OwnerProblem, List[String]]] =
    workspace.flatMap(Workspace.install).map(_.leftMap(problem))

  def vocabularySearch(query: String): List[Vocabulary.Term] = Vocabulary.search(vocabulary, query)

  def prepareFact(input: FactInput): IO[Either[OwnerProblem, CommitPreview]] =
    workspace.flatMap: opened =>
      for
        closure <- opened.kb.closure
        verbalizer <- opened.kb.verbalizer
      yield
        val subject = input.subject match
          case EntityChoice.Existing(iri) => iri
          case EntityChoice.New(iri, _)   => iri
        val property = input.property
        val axiom = Assertions.build(closure, subject, property, input.value)

        val assertions = input.subject match
          case EntityChoice.New(_, label) if property != Vocab.label && label.trim.nonEmpty =>
            NonEmptyList.of(axiom, Axiom.DataAssertion(subject, Vocab.label, Literal.string(label.trim)))
          case _ => NonEmptyList.one(axiom)
        val record = AxiomRecord(
          axiom.id,
          axiom,
          input.annotations,
          AxiomStatus.Active,
          assertedAt = 0L
        )
        Right(
          new CommitPreview(
            assertions,
            input.annotations,
            assertions.toList.map(verbalizer.verbalize).mkString("\n"),
            PolicyCascade.sensitivity(record, Workspace.config.policies),
            PolicyCascade.recallUtility(record, Workspace.config.policies),
            input.annotations.truthConfidence.getOrElse(1.0)
          )
        )

  def commit(preview: CommitPreview): IO[Either[OwnerProblem, CommitOutcome]] =
    workspace.flatMap: opened =>
      opened.kb
        .commit(preview.axioms.map(Intent.Assert(_, preview.annotations)))
        .flatMap:
          case Left(rejected) => Left(problem(rejected)).pure[IO]
          case Right(result) =>
            opened.engine.handle(result.events).map(items => Right(CommitOutcome(result, items)))
        .uncancelable

  /** Appends to today's page; Save note is the one confirmation for the owner's written intention. */
  def appendToday(text: String, on: LocalDate): IO[Either[OwnerProblem, CommitOutcome]] =
    val paragraphs = NotesCapture.paragraphs(text)
    if paragraphs.isEmpty then
      Left(OwnerProblem("note not saved", "the note is blank", "write something, then save again"))
        .pure[IO]
    else
      workspace.flatMap: opened =>
        for
          state <- opened.kb.state
          ids <- paragraphs.traverse(_ => opened.uuidGen.randomUUID.map(NoteIds.block))
          note = NoteIds.daily(on)
          outline = Outline.of(state, note)
          planned = NotesCapture.appendAll(outline, ids, text)
          answer <- planned match
            case Left(reason) =>
              Left(OwnerProblem("note not saved", reason.render, "correct the note and try again"))
                .pure[IO]
            case Right(blockIntents) =>
              val naming = Naming.from(
                state,
                Workspace.config.namingProperties,
                Workspace.config.namingSchemes
              )
              val resolved = ids.zip(paragraphs).map: (block, paragraph) =>
                val resolutions = NoteLinks.resolve(naming, NoteLinks.parse(paragraph))
                val questions = NoteLinks.unanswered(resolutions).map:
                  case NoteLinks.Resolution.Unresolved(link) =>
                    s"[[${link.name}]] matches nothing; add the entity, then link it"
                  case NoteLinks.Resolution.Ambiguous(link, candidates) =>
                    s"[[${link.name}]] matches ${candidates.length}: ${candidates.map(_.display).mkString(", ")}"
                  case NoteLinks.Resolution.Resolved(link, _) => s"[[${link.name}]]"
                (NoteLinks.mentions(block, resolutions), questions)
              val all = NotesCapture.daily(on).toList ++ blockIntents ++ resolved.flatMap(_._1)
              val messages = resolved.flatMap(_._2)
              NonEmptyList.fromList(all) match
                case None =>
                  Left(OwnerProblem("note not saved", "nothing was planned", "write something first"))
                    .pure[IO]
                case Some(intents) =>
                  opened.kb.commit(intents).flatMap:
                    case Left(rejected) => Left(problem(rejected)).pure[IO]
                    case Right(result) =>
                      opened.engine
                        .handle(result.events)
                        .map(items => Right(CommitOutcome(result, items, messages)))
        yield answer
      .uncancelable

  def agenda(on: LocalDate): IO[List[AgendaView]] =
    workspace.flatMap: opened =>
      for
        state <- opened.kb.state
        verbalizer <- opened.kb.verbalizer
      yield Modules
        .agendaProducers(modules)
        .flatMap(_.entries(state, on))
        .sortBy(_.due)
        .map(entry =>
          AgendaView(
            entry.due,
            entry.summary,
            entry.subject,
            verbalizer.label(entry.subject),
            entry.overdue
          )
        )

  def search(query: String): IO[List[SearchHit]] =
    val needle = query.trim.toLowerCase(java.util.Locale.ROOT)
    if needle.isEmpty then List.empty[SearchHit].pure[IO]
    else
      workspace.flatMap: opened =>
        for
          state <- opened.kb.state
          verbalizer <- opened.kb.verbalizer
        yield
          val entities = state.entities.toList.flatMap: iri =>
            val label = verbalizer.label(iri)
            Option.when(label.toLowerCase(java.util.Locale.ROOT).contains(needle))(
              SearchHit.Entity(iri, label)
            )
          val notes = noteOutlines(state).flatMap: note =>
            note.blocks.flatMap: block =>
              Option.when(block.text.toLowerCase(java.util.Locale.ROOT).contains(needle))(
                SearchHit.NoteBlock(
                  note.id,
                  note.title.getOrElse("(untitled)"),
                  block.id,
                  block.text
                )
              )
          val terms = vocabularySearch(query).map(SearchHit.Term.apply)
          (entities.sortBy(_.toString) ++ notes ++ terms).take(100)

  def entity(iri: Iri): IO[EntityView] =
    workspace.flatMap: opened =>
      for
        state <- opened.kb.state
        verbalizer <- opened.kb.verbalizer
        (records, fluents) = state.about(iri)
        beliefs <- opened.engine.beliefsFor(records.map(_.id).toSet)
      yield EntityView(
        verbalizer.label(iri),
        iri,
        records.sortBy(_.assertedAt).map(record =>
          FactView(
            record.id,
            verbalizer.verbalize(record.axiom),
            record.axiom.manchester,
            beliefs.get(record.id)
          )
        ),
        fluents.toList.sortBy(_.id.value).map(verbalizer.verbalize)
      )

  def queue(mode: QueueMode = QueueMode.Mixed, limit: Int = 20): IO[List[QueueEntry]] =
    workspace.flatMap(_.engine.queue(mode, limit))

  def question(entry: QueueEntry): IO[ReviewPrompt] =
    workspace.flatMap(_.engine.nextQuestion(entry)).map(ReviewPrompt(entry, _))

  def answer(question: Question, response: String, latencyMs: Long): IO[Either[OwnerProblem, ReviewOutcome]] =
    workspace.flatMap: opened =>
      opened.engine.answer(question, response, latencyMs).flatMap:
        case None =>
          Left(
            OwnerProblem(
              "review not recorded",
              "this question needs a rubric judge and no model is configured",
              "record an owner grade through the CLI, or skip this item"
            )
          ).pure[IO]
        case Some(outcome) =>
          opened.recordReview(outcome.review).as(Right(outcome))
    .uncancelable

  private def noteOutlines(state: KbState): List[Outline.Note] =
    state.activeAxioms.map(_.axiom).collect:
      case Axiom.ClassAssertion(note, cls)
          if cls == NotesModule.Daily || cls == NotesModule.Permanent ||
            cls == NotesModule.Literature =>
        note
    .toList.distinct.map(Outline.of(state, _))

  private def problem(rejected: CommitRejected): OwnerProblem =
    OwnerProblem(
      "commit rejected",
      rejected.render.stripPrefix("commit rejected — "),
      "change the proposed fact, or cancel it"
    )

object OwnerSession:
  def open(root: fs2.io.file.Path): Resource[IO, OwnerSession] =
    Resource.eval(SecureRandom.javaSecuritySecureRandom[IO]).map: random =>
      given SecureRandom[IO] = random
      OwnerSession(root, UUIDGen.fromSecureRandom[IO])

  private[app] def open(root: fs2.io.file.Path, uuidGen: UUIDGen[IO]): Resource[IO, OwnerSession] =
    Resource.pure(OwnerSession(root, uuidGen))
