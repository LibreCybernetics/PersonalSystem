package noesis.lms

import munit.FunSuite
import noesis.logic.AxiomId

/** Items, item identity, and answer grading (SPEC §4.1).
  *
  * Identity and staleness are the two places where a silent collision does real damage: two
  * unrelated facts sharing an item id would merge their belief scores, and a question that fails to
  * notice its source changed keeps quizzing a value the KB has already superseded.
  */
class ItemSuite extends FunSuite:

  private def itemWith(belief: Double, reviewCount: Int = 0): Item =
    Item(
      id = ItemId.unsafe("it_test"),
      kind = ItemKind.AtomicFact,
      axioms = Set(AxiomId.unsafe("ax_1")),
      belief = belief,
      reviewCount = reviewCount
    )

  // ── Identity (SPEC §4.1) ──────────────────────────────────────────────────

  test("an item id is stable under axiom order and carries its kind"):
    val forward = ItemId.of(ItemKind.AtomicFact, Set(AxiomId.unsafe("ax_1"), AxiomId.unsafe("ax_2")))
    val reversed = ItemId.of(ItemKind.AtomicFact, Set(AxiomId.unsafe("ax_2"), AxiomId.unsafe("ax_1")))

    assertEquals(forward, reversed, "a set has no order, so neither may the id derived from it")
    assert(forward.value.startsWith("it_atomicfact_"), forward.value)
    assertNotEquals(
      forward,
      ItemId.of(ItemKind.Concept, Set(AxiomId.unsafe("ax_1"), AxiomId.unsafe("ax_2"))),
      "the same axioms quizzed as a concept are a different item"
    )

  test("an item id separates its axioms, so different groupings cannot collide"):
    // Without a separator {a, bc} and {ab, c} hash the same string, silently merging the belief
    // scores of two unrelated composite items.
    assertNotEquals(
      ItemId.of(ItemKind.Composite, Set(AxiomId.unsafe("a"), AxiomId.unsafe("bc"))),
      ItemId.of(ItemKind.Composite, Set(AxiomId.unsafe("ab"), AxiomId.unsafe("c")))
    )

  test("a source hash separates its axioms for the same reason"):
    assertNotEquals(
      Question.hashOf(Set(AxiomId.unsafe("a"), AxiomId.unsafe("bc"))),
      Question.hashOf(Set(AxiomId.unsafe("ab"), AxiomId.unsafe("c")))
    )

  test("a question goes stale exactly when the axioms it was built from change"):
    val axioms = Set(AxiomId.unsafe("ax_1"))
    val question = Question(
      id = "q1",
      item = ItemId.unsafe("it_1"),
      format = QuestionFormat.ShortAnswer,
      prompt = "whose birthday is 05-12?",
      answer = AnswerSpec.Exact("Lía"),
      sourceHash = Question.hashOf(axioms)
    )

    assert(!question.isStale(Question.hashOf(axioms)), "an unchanged source is not stale")
    assert(question.isStale(Question.hashOf(axioms + AxiomId.unsafe("ax_2"))))

  // ── Classification (SPEC §4.2) ────────────────────────────────────────────

  test("mastery and known-unknown are decided at their closed boundaries"):
    assert(itemWith(belief = 1.0).isMastered, "b = 1 is mastered")
    assert(!itemWith(belief = 0.999).isMastered)
    assert(itemWith(belief = 0.0, reviewCount = 0).isKnownUnknown, "b = 0 unreviewed is a gap")
    assert(!itemWith(belief = 0.001, reviewCount = 0).isKnownUnknown)
    assert(
      !itemWith(belief = 0.0, reviewCount = 1).isKnownUnknown,
      "a zero the owner has been quizzed on is a lapse, not an unexplored gap"
    )

  test("a stored belief outside [0,1] still classifies fail-safe"):
    // Item state is rebuilt from a durable review log (SPEC §12.3), so a corrupt or refitted entry
    // can carry a score the update path would never produce. Both classifications must hold rather
    // than falling through to "schedule it forever".
    assert(itemWith(belief = 1.5).isMastered)
    assert(itemWith(belief = -0.5, reviewCount = 0).isKnownUnknown)

  test("suspension is what stops scheduling, independently of belief"):
    assert(itemWith(belief = 0.5).isActive)
    assert(!itemWith(belief = 0.5).copy(suspended = true).isActive)

  // ── Grading (SPEC §4.1, §6) ───────────────────────────────────────────────

  test("an exact answer is graded on normalized text, not on presentation"):
    val spec = AnswerSpec.Exact("Molina Labs")

    assertEquals(spec.grade("Molina Labs"), Some(1.0))
    assertEquals(spec.grade("  molina labs "), Some(1.0), "case and padding are not the skill")
    assertEquals(spec.grade("Acme"), Some(0.0))

  test("any lexicalization of a concept counts, so synonyms are not marked wrong"):
    val spec = AnswerSpec.AnyOf(Set("friend", "amigo"))

    assertEquals(spec.grade("Amigo"), Some(1.0))
    assertEquals(spec.grade("friend"), Some(1.0))
    assertEquals(spec.grade("enemy"), Some(0.0))

  test("a rubric declines to grade rather than guessing into the review log"):
    // SPEC §12.3 depends on the review log being real evidence; the MVP has no judge, so a rubric
    // answer produces no grade at all.
    assertEquals(AnswerSpec.Rubric("explain why the chain applies").grade("because"), None)
