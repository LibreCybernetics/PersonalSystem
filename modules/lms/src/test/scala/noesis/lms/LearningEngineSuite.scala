package noesis.lms

import java.time.Instant

import cats.effect.IO
import munit.CatsEffectSuite
import noesis.core.Fixtures
import noesis.core.kb.KnowledgeBase
import noesis.logic.*

/** The Learning Engine's reaction to core events, and its recovery from the review log
  * (SPEC §4.1, §4.2, §4.6, §12.3).
  *
  * The engine reads the Knowledge Core and never writes to it, so every test here asserts on item
  * state — belief lives in the engine, truth lives in the core, and §4.2 is emphatic that the two
  * are never mixed.
  */
class LearningEngineSuite extends CatsEffectSuite:

  private val hasName = Iri("crm:hasName")
  private val pronouns = Iri("crm:pronouns")
  private val phone = Iri("crm:phone")

  private val activating = ItemPolicyBook(default = ItemPolicy.AutoActivate)

  /** A base that already knows Sarah's name, so change prompts read as the owner would see them. */
  private def named: IO[KnowledgeBase[IO]] =
    for
      base <- Fixtures.kb()
      _ <- base.assert(Axiom.DataAssertion(Fixtures.sarah, Vocab.label, Literal.string("Sarah")))
    yield base

  private def engineOn(
      base: IO[KnowledgeBase[IO]] = Fixtures.kb(),
      policies: ItemPolicyBook = activating
  ): IO[LearningEngine[IO]] =
    base.flatMap(LearningEngine[IO](_, policies))

  private def only(items: List[Item]): Item =
    items match
      case one :: Nil => one
      case other      => fail(s"expected exactly one item, got ${other.map(_.id.value)}")

  // ── Drafting (SPEC §4.1) ──────────────────────────────────────────────────

  test("the item policy cascade decides whether a new axiom is drafted, and how"):
    // "Quiz me on birthdays, never on phone numbers" is configuration, not code (SPEC §4.1).
    val policies = ItemPolicyBook(
      byProperty = Map(Fixtures.birthday -> ItemPolicy.AutoActivate, phone -> ItemPolicy.Ignore)
    )
    val birthdayAxiom = Axiom.DataAssertion(Fixtures.lia, Fixtures.birthday, Literal.string("05-12"))
    val phoneAxiom = Axiom.DataAssertion(Fixtures.lia, phone, Literal.string("555-0100"))
    val salaryAxiom = Axiom.DataAssertion(Fixtures.lia, Fixtures.salary, Literal.string("100"))

    for
      engine <- engineOn(policies = policies)
      auto <- engine.onAxiomAdded(birthdayAxiom.id, birthdayAxiom)
      ignored <- engine.onAxiomAdded(phoneAxiom.id, phoneAxiom)
      drafted <- engine.onAxiomAdded(salaryAxiom.id, salaryAxiom)
    yield
      assert(!only(auto).suspended, "an auto-activated item is scheduled straight away")
      assertEquals(ignored, Nil, "an ignored property leaves nothing behind to approve")
      assert(only(drafted).suspended, "the default drafts for review rather than quizzing unasked")

  test("an assertion drafts an atomic-fact item; a schema axiom drafts a concept item"):
    val fact = Axiom.ClassAssertion(Fixtures.lia, Fixtures.Person)
    val schema = Axiom.SubClassOf(Fixtures.Person, Fixtures.Agent)

    for
      engine <- engineOn()
      drafted <- engine.onAxiomAdded(fact.id, fact)
      concept <- engine.onAxiomAdded(schema.id, schema)
    yield
      assertEquals(only(drafted).kind, ItemKind.AtomicFact)
      assertEquals(only(concept).kind, ItemKind.Concept)

  test("a drafted item carries a verbalized prompt, so a queue can be shown without the KB"):
    val axiom = Axiom.ClassAssertion(Fixtures.sarah, Fixtures.Person)
    for
      engine <- engineOn(named)
      drafted <- engine.onAxiomAdded(axiom.id, axiom)
    yield assertEquals(only(drafted).prompt, "Sarah is a person")

  // ── Retraction (SPEC §4.1) ────────────────────────────────────────────────

  test("retracting an axiom retires its items and leaves every other item alone"):
    val retracted = Axiom.ClassAssertion(Fixtures.lia, Fixtures.Person)
    val kept = Axiom.ClassAssertion(Fixtures.marco, Fixtures.Person)

    for
      engine <- engineOn()
      _ <- engine.onAxiomAdded(retracted.id, retracted)
      _ <- engine.onAxiomAdded(kept.id, kept)
      retired <- engine.onAxiomRetracted(retracted.id)
      remaining <- engine.items
    yield
      assertEquals(only(retired).axioms, Set(retracted.id))
      assert(only(retired).suspended, "a retired item must stop being scheduled")
      assertEquals(
        remaining.filter(_.isActive).flatMap(_.axioms.toList),
        List(kept.id),
        "retraction is targeted: unrelated items keep their schedule"
      )

  // ── State change (SPEC §3.6, §7.2) ────────────────────────────────────────

  test("a superseded value is demoted to historical while the change itself is drafted"):
    // The old item is not deleted — it may retain utility (SPEC §3.6) — and the new one is a
    // *change* item, because the entrenched old answer is what will interfere.
    val previous = Axiom.ObjectAssertion(Fixtures.sarah, Fixtures.worksAt, Fixtures.acme)
    val current = Axiom.ObjectAssertion(Fixtures.sarah, Fixtures.worksAt, Fixtures.molina)
    val policies = activating.withProperty(
      Fixtures.worksAt,
      ItemPolicy.DraftForReview
    )

    for
      engine <- engineOn(named, policies)
      _ <- engine.onAxiomAdded(previous.id, previous)
      changed <- engine.onStateChanged(
        Fixtures.sarah,
        Fixtures.worksAt,
        Some(Node.Ref(Fixtures.acme)),
        Some(Node.Ref(Fixtures.molina))
      )
    yield
      val demoted = only(changed.filter(_.axioms.contains(previous.id)))
      val fresh = only(changed.filter(_.axioms.contains(current.id)))

      assertEquals(demoted.origin, ItemOrigin.Historical)
      assertEquals(demoted.priorityBoost, 0.0, "a former value must not keep jumping the queue")
      assertEquals(fresh.origin, ItemOrigin.StateChange)
      assertEquals(fresh.prompt, "Sarah — works at *now*?")
      assert(fresh.suspended, "a review-first property must not become schedulable on change")

  test("a name or pronoun change outranks every other kind of change"):
    // SPEC §7.2: misnaming someone is the failure the system exists to prevent, so the entrenched
    // old answer gets the highest priority the scheduler offers.
    def boostFor(engine: LearningEngine[IO], property: Iri, value: Node): IO[Double] =
      engine
        .onStateChanged(Fixtures.sarah, property, None, Some(value))
        .map(items => only(items).priorityBoost)

    for
      engine <- engineOn(named)
      renamed <- boostFor(engine, hasName, Node.Lit(Literal.string("Sara")))
      repronouned <- boostFor(engine, pronouns, Node.Lit(Literal.string("she/her")))
      moved <- boostFor(engine, Fixtures.worksAt, Node.Ref(Fixtures.molina))
    yield
      assertEquals(renamed, 1.0)
      assertEquals(repronouned, 1.0)
      assertEquals(moved, 0.4, "an ordinary supersession is elevated, but not to the top")

  test("an ignored time-varying property does not create a change item"):
    val policies = ItemPolicyBook(
      default = ItemPolicy.AutoActivate,
      byProperty = Map(phone -> ItemPolicy.Ignore)
    )
    for
      engine <- engineOn(policies = policies)
      changed <- engine.onStateChanged(
        Fixtures.sarah,
        phone,
        None,
        Some(Node.Lit(Literal.string("555-0100")))
      )
      items <- engine.items
    yield
      assertEquals(changed, Nil)
      assertEquals(items, Nil)

  // ── Belief overlay (SPEC §4.6) ────────────────────────────────────────────

  test("the belief overlay reports each axiom against its own item"):
    val quizzed = Axiom.ClassAssertion(Fixtures.lia, Fixtures.Person)
    val untouched = Axiom.ClassAssertion(Fixtures.marco, Fixtures.Person)

    for
      engine <- engineOn()
      drafted <- engine.onAxiomAdded(quizzed.id, quizzed)
      _ <- engine.onAxiomAdded(untouched.id, untouched)
      _ <- engine.review(only(drafted).id, grade = 1.0, latencyMs = 1000)
      overlay <- engine.beliefsFor(Set(untouched.id))
      both <- engine.beliefsFor(Set(quizzed.id, untouched.id))
    yield
      assertEquals(overlay.keySet, Set(untouched.id))
      assertEquals(
        overlay.get(untouched.id),
        Some(Belief.prior),
        "an unreviewed axiom must not inherit the belief of the one that was reviewed"
      )
      assert(
        both.getOrElse(quizzed.id, 0.0) > both.getOrElse(untouched.id, 1.0),
        both.toString
      )

  test("a graded answer records a review and moves belief"):
    val axiom = Axiom.DataAssertion(Fixtures.lia, Fixtures.birthday, Literal.string("05-12"))

    for
      engine <- engineOn()
      drafted <- engine.onAxiomAdded(axiom.id, axiom)
      item = only(drafted)
      question <- engine.nextQuestion(QueueEntry(item, QueueMode.Retention, 1.0, 0.5, 0.9, "test"))
      asked = question.getOrElse(fail("expected a generated question"))
      outcome <- engine.answer(asked, "05-12", latencyMs = 1200)
      log <- engine.reviewLog
    yield
      val result = outcome.getOrElse(fail("a gradeable answer must produce an outcome"))
      assert(result.item.belief > Belief.prior, result.item.belief.toString)
      assertEquals(result.review.grade, 1.0)
      assertEquals(result.review.question, Some(asked.id))
      assertEquals(log.length, 1, "SPEC §12.3: every review is logged from day one")

  // ── Restore (SPEC §4.1, §12.3) ────────────────────────────────────────────

  test("restoring from the review log replays counts exactly, without re-simulating belief"):
    // §12.3 anticipates refitting the belief parameters, so restore folds the *recorded* outcome
    // back in rather than re-running Belief.update, which would drift after a refit.
    val axiom = Axiom.ClassAssertion(Fixtures.lia, Fixtures.Person)
    val t0 = Instant.parse("2026-07-01T12:00:00Z")

    for
      engine <- engineOn()
      drafted <- engine.onAxiomAdded(axiom.id, axiom)
      id = only(drafted).id
      // Deliberately out of order: the fold must sort by time, not trust the log's arrangement.
      reviews = List(
        Review(id, None, grade = 0.7, 1000, t0.plusSeconds(300), 0.6, 0.8, 4.0),
        Review(id, None, grade = 0.5, 1000, t0, 0.5, 0.4, 1.0),
        Review(id, None, grade = 0.6, 1000, t0.plusSeconds(200), 0.3, 0.6, 2.0),
        Review(id, None, grade = 0.5, 1000, t0.plusSeconds(100), 0.4, 0.3, 0.8)
      )
      _ <- engine.restore(reviews)
      restored <- engine.items
      log <- engine.reviewLog
    yield
      val item = only(restored)
      assertEquals(item.reviewCount, 4)
      assertEquals(item.lapseCount, 2, "0.6 is a pass, so only the two 0.5s are lapses")
      assertEquals(item.belief, 0.8, "the latest recorded outcome, not a re-simulated one")
      assertEquals(item.stability, 4.0)
      assertEquals(item.lastReviewed, Some(t0.plusSeconds(300)))
      assertEquals(log.length, 4, "the durable log survives the fold intact")

  test("an item with no recorded reviews is left exactly as drafted"):
    val axiom = Axiom.ClassAssertion(Fixtures.lia, Fixtures.Person)

    for
      engine <- engineOn()
      drafted <- engine.onAxiomAdded(axiom.id, axiom)
      _ <- engine.restore(Nil)
      restored <- engine.items
    yield assertEquals(only(restored), only(drafted))
