package dev.librecybernetics.noesis.gui

import java.time.{Instant, LocalDate}
import scala.concurrent.duration.*

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite

import dev.librecybernetics.noesis.app.*
import dev.librecybernetics.noesis.core.kb.CommitResult
import dev.librecybernetics.noesis.journal.Commit
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.lms.*

/** Deterministic interpreter tests: owner actions and time are data supplied by the test. */
class EffectsSuite extends CatsEffectSuite:
  private val day = LocalDate.of(2026, 8, 5)
  private val subject = Iri("noesis:e/marco")
  private val axiom = Axiom.DataAssertion(subject, Vocab.label, Literal.string("Marco"))
  private val preview = new CommitPreview(
    NonEmptyList.one(axiom),
    AxiomAnnotations.ownerConfirmed,
    "Marco is named Marco",
    Sensitivity.Public,
    0.5,
    1.0
  )
  private val commitResult = CommitResult(Commit(Nil), Nil, Nil)
  private val outcome = CommitOutcome(commitResult, Nil)
  private val item = Item(ItemId.unsafe("item-1"), ItemKind.AtomicFact, Set(axiom.id), prompt = "name")
  private val entry = QueueEntry(item, QueueMode.Retention, 1.0, 0.2, 1.0, "due")
  private val sampleQuestion = Question(
    "question-1",
    item.id,
    QuestionFormat.ShortAnswer,
    "Name?",
    AnswerSpec.Exact("Marco"),
    sourceHash = Question.hashOf(item.axioms)
  )
  private val review = Review(item.id, Some(sampleQuestion.id), 1.0, 250L, Instant.EPOCH, 0.2, 0.8, 2.0)
  private val reviewOutcome = ReviewOutcome(item, review, Nil)
  private val ownerProblem = OwnerProblem("failed", "because", "retry")

  private class StubActions extends OwnerActions:
    def initialized = IO.pure(false)
    def initialize = IO.pure(Right(Nil))
    def agenda(on: LocalDate) = IO.pure(Nil)
    def appendToday(text: String, on: LocalDate) = IO.pure(Right(outcome))
    def prepareFact(input: FactInput) = IO.pure(Right(preview))
    def commit(value: CommitPreview) = IO.pure(Right(outcome))
    def search(query: String) = IO.pure(Nil)
    def entity(iri: Iri) = IO.pure(EntityView("Marco", iri, Nil, Nil))
    def queue(limit: Int) = IO.pure(Nil)
    def question(value: QueueEntry) = IO.pure(ReviewPrompt(value, Some(sampleQuestion)))
    def answer(value: Question, response: String, latencyMs: Long) = IO.pure(Right(reviewOutcome))
    def changes = Stream.empty

  private def clock(times: List[FiniteDuration] = List(5.seconds)): GuiClock = new GuiClock:
    private var remaining = times
    def today = IO.pure(day)
    def monotonic = IO:
      val next = remaining.headOption.getOrElse(remaining.lastOption.getOrElse(Duration.Zero))
      remaining = remaining.drop(1)
      next

  test("initialization effects preserve owner failures and translate thrown failures"):
    val initialized = new StubActions:
      override def initialized = IO.pure(true)
      override def initialize = IO.raiseError(new IllegalStateException("read only"))
    val effects = Effects(initialized, clock())
    for
      checked <- effects.run(Effect.CheckInitialization)
      failed <- effects.run(Effect.Initialize)
    yield
      assertEquals(checked, Event.InitializationKnown(true))
      failed match
        case Event.Initialized(Left(problem)) =>
          assertEquals(problem.what, "workspace not initialized")
          assertEquals(problem.why, "read only")
        case other => fail(s"unexpected event: $other")

  test("agenda and note effects receive the deterministic date"):
    for
      seen <- Ref.of[IO, List[LocalDate]](Nil)
      actions = new StubActions:
        override def agenda(on: LocalDate) = seen.update(on :: _) *> IO.pure(Nil)
        override def appendToday(text: String, on: LocalDate) =
          seen.update(on :: _) *> IO.pure(Left(ownerProblem))
      effects = Effects(actions, clock())
      agenda <- effects.run(Effect.LoadAgenda)
      note <- effects.run(Effect.SaveNote("thought"))
      dates <- seen.get
    yield
      assertEquals(agenda, Event.AgendaLoaded(Right(Nil)))
      assertEquals(note, Event.NoteSaved(Left(ownerProblem)))
      assertEquals(dates, List(day, day))

  test("a thrown note failure becomes an actionable event"):
    val actions = new StubActions:
      override def appendToday(text: String, on: LocalDate) =
        IO.raiseError(new IllegalStateException("disk full"))
    Effects(actions, clock()).run(Effect.SaveNote("thought")).map:
      case Event.NoteSaved(Left(problem)) => assertEquals(problem.why, "disk full")
      case other                          => fail(s"unexpected event: $other")

  test("fact validation refuses each blank field before invoking the owner boundary"):
    val effects = Effects(StubActions(), clock())
    val cases = List(
      FactDraft("", "crm:worksAt", "acme") -> "subject",
      FactDraft("marco", "", "acme") -> "property",
      FactDraft("marco", "crm:worksAt", "") -> "value"
    )
    cases.traverse_ : entry =>
      val (draft, missing) = entry
      effects.run(Effect.PrepareFact(draft)).map:
        case Event.FactPrepared(Left(problem)) => assert(problem.why.contains(missing))
        case other                             => fail(s"unexpected event: $other")

  test("valid fact preparation preserves existing and new entity choices"):
    for
      seen <- Ref.of[IO, List[FactInput]](Nil)
      actions = new StubActions:
        override def prepareFact(input: FactInput) = seen.update(input :: _) *> IO.pure(Right(preview))
      effects = Effects(actions, clock())
      existing <- effects.run(Effect.PrepareFact(FactDraft("marco", "crm:worksAt", "acme")))
      fresh <- effects.run(Effect.PrepareFact(FactDraft("lía", "rdf:type", "crm:Person", true)))
      inputs <- seen.get
    yield
      assertEquals(existing, Event.FactPrepared(Right(preview)))
      assertEquals(fresh, Event.FactPrepared(Right(preview)))
      assert(inputs.exists(_.subject == EntityChoice.Existing(Workspace.iri("marco"))))
      assert(inputs.exists(_.subject == EntityChoice.New(Workspace.iri("lía"), "lía")))

  test("commit, search, and entity exceptions become actionable events"):
    val broken = new StubActions:
      override def commit(value: CommitPreview) = IO.raiseError(new RuntimeException("commit boom"))
      override def search(query: String) = IO.raiseError(new RuntimeException("search boom"))
      override def entity(iri: Iri) = IO.raiseError(new RuntimeException())
    val effects = Effects(broken, clock())
    for
      committed <- effects.run(Effect.CommitFact(preview))
      searched <- effects.run(Effect.Search("marco"))
      loaded <- effects.run(Effect.LoadEntity(subject))
    yield
      committed match
        case Event.FactCommitted(Left(problem)) => assertEquals(problem.why, "commit boom")
        case other                              => fail(s"unexpected event: $other")
      searched match
        case Event.SearchLoaded(Left(problem)) => assertEquals(problem.why, "search boom")
        case other                             => fail(s"unexpected event: $other")
      loaded match
        case Event.EntityLoaded(Left(problem)) => assertEquals(problem.why, "RuntimeException")
        case other                             => fail(s"unexpected event: $other")

  test("successful owner effects preserve their typed results"):
    val effects = Effects(StubActions(), clock())
    for
      initialized <- effects.run(Effect.Initialize)
      note <- effects.run(Effect.SaveNote("thought"))
      committed <- effects.run(Effect.CommitFact(preview))
      searched <- effects.run(Effect.Search("marco"))
      entity <- effects.run(Effect.LoadEntity(subject))
    yield
      assertEquals(initialized, Event.Initialized(Right(Nil)))
      assertEquals(note, Event.NoteSaved(Right(outcome)))
      assertEquals(committed, Event.FactCommitted(Right(outcome)))
      assertEquals(searched, Event.SearchLoaded(Right(Nil)))
      assertEquals(entity, Event.EntityLoaded(Right(EntityView("Marco", subject, Nil, Nil))))

  test("review loading timestamps a prompt and an empty queue"):
    val populated = new StubActions:
      override def queue(limit: Int) = IO.pure(List(entry))
    val effects = Effects(populated, clock(List(7.seconds, 8.seconds)))
    for
      loaded <- effects.run(Effect.LoadReview)
      empty <- Effects(StubActions(), clock(List(8.seconds))).run(Effect.LoadReview)
    yield
      assertEquals(
        loaded,
        Event.ReviewLoaded(Right(Some(ReviewState(ReviewPrompt(entry, Some(sampleQuestion)), 7.seconds))))
      )
      assertEquals(empty, Event.ReviewLoaded(Right(None)))

  test("answer effects reject missing questions and clamp negative latency"):
    for
      latency <- Ref.of[IO, Option[Long]](None)
      actions = new StubActions:
        override def answer(value: Question, response: String, latencyMs: Long) =
          latency.set(Some(latencyMs)) *> IO.pure(Right(reviewOutcome))
      effects = Effects(actions, clock(List(4.seconds)))
      missing <- effects.run(Effect.RecordAnswer(ReviewState(ReviewPrompt(entry, None), 5.seconds), "x"))
      recorded <- effects.run(
        Effect.RecordAnswer(ReviewState(ReviewPrompt(entry, Some(sampleQuestion)), 5.seconds), "Marco")
      )
      measured <- latency.get
    yield
      missing match
        case Event.AnswerRecorded(Left(problem)) => assert(problem.why.contains("no usable"))
        case other                               => fail(s"unexpected event: $other")
      assertEquals(recorded, Event.AnswerRecorded(Right("Correct")))
      assertEquals(measured, Some(0L))

  test("owner and thrown answer failures remain distinct"):
    val refused = new StubActions:
      override def answer(value: Question, response: String, latencyMs: Long) = IO.pure(Left(ownerProblem))
    val crashed = new StubActions:
      override def answer(value: Question, response: String, latencyMs: Long) =
        IO.raiseError(new RuntimeException("answer boom"))
    val state = ReviewState(ReviewPrompt(entry, Some(sampleQuestion)), 1.second)
    for
      owner <- Effects(refused, clock(List(2.seconds))).run(Effect.RecordAnswer(state, "x"))
      thrown <- Effects(crashed, clock(List(2.seconds))).run(Effect.RecordAnswer(state, "x"))
    yield
      assertEquals(owner, Event.AnswerRecorded(Left(ownerProblem)))
      thrown match
        case Event.AnswerRecorded(Left(problem)) => assertEquals(problem.why, "answer boom")
        case other                               => fail(s"unexpected event: $other")
