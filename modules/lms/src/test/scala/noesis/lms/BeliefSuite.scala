package noesis.lms

import java.time.Instant

import munit.FunSuite
import noesis.logic.*
import noesis.reasoner.{Graph, Reasoner, Support}

/** Belief and scheduling (SPEC §4.2, §4.3, §4.4).
  *
  * These are the numeric heart of the learning engine, and §12.3 flags the model as provisional — so
  * the tests pin *properties* (monotonicity, decay direction, where entropy peaks) rather than exact
  * constants, which is what should survive an FSRS refit.
  */
class BeliefSuite extends FunSuite:

  private val t0 = Instant.parse("2026-07-01T12:00:00Z")
  private val Person = Iri("crm:Person")
  private val Agent = Iri("crm:Agent")
  private val alice = Iri("noesis:e/alice")
  private def days(n: Long): Instant = t0.plusSeconds(n * 86400)

  private def item(
      belief: Double = Belief.prior,
      stability: Double = Belief.initialStability,
      reviewed: Option[Instant] = None
  ): Item =
    Item(
      id = ItemId.unsafe("it_test"),
      kind = ItemKind.AtomicFact,
      axioms = Set(AxiomId.unsafe("ax_1")),
      belief = belief,
      stability = stability,
      lastReviewed = reviewed
    )

  // ── Decay (SPEC §4.2) ─────────────────────────────────────────────────────

  test("a never-reviewed item sits at its prior rather than decaying"):
    assertEquals(Belief.at(item(), t0), Belief.prior)
    assertEquals(Belief.at(item(), days(100)), Belief.prior)

  test("belief decays exponentially between reviews"):
    val reviewed = item(belief = 1.0, stability = 10.0, reviewed = Some(t0))

    val immediately = Belief.at(reviewed, t0)
    val afterTenDays = Belief.at(reviewed, days(10))
    val afterHundred = Belief.at(reviewed, days(100))

    assertEquals(immediately, 1.0)
    assertEqualsDouble(afterTenDays, math.exp(-1.0), 0.001, "b(S) should be b·e⁻¹")
    assert(afterHundred < afterTenDays)
    assert(afterHundred > 0.0, "decay approaches zero without reaching it")

  test("higher stability means slower decay"):
    val fragile = item(belief = 1.0, stability = 2.0, reviewed = Some(t0))
    val durable = item(belief = 1.0, stability = 60.0, reviewed = Some(t0))

    assert(Belief.at(durable, days(30)) > Belief.at(fragile, days(30)))

  test("reading belief before its own review neither decays nor inflates it"):
    // Elapsed time floors at zero, so a backwards clock — or a point-in-time read — returns the
    // recorded belief instead of running the decay curve in reverse.
    val reviewed = item(belief = 0.5, stability = 10.0, reviewed = Some(days(10)))

    assertEquals(Belief.at(reviewed, days(10)), 0.5, "no time elapsed, no decay")
    assertEquals(Belief.at(reviewed, t0), 0.5, "ten days early is still no decay, not growth")

  test("a stored belief outside [0,1] is clamped on read"):
    // `belief` is decoded from a durable review log (SPEC §12.3), so a corrupt or refitted entry can
    // carry a value the update path would never produce. Reads must still be probabilities.
    val overconfident = item(belief = 1.5, stability = 10.0, reviewed = Some(t0))
    val negative = item(belief = -0.5, stability = 10.0, reviewed = Some(t0))

    assertEquals(Belief.at(overconfident, t0), 1.0)
    assertEquals(Belief.at(negative, t0), 0.0)
    assertEquals(Belief.at(item(belief = 1.5), t0), 1.0, "and on the never-reviewed path too")

  // ── Update (SPEC §4.2) ────────────────────────────────────────────────────

  test("a correct answer raises belief and grows stability"):
    val before = item(belief = 0.4, reviewed = Some(t0))
    val (after, review) = Belief.update(before, grade = 1.0, latencyMs = 2000, 1.0, days(1))

    assert(after.belief > review.beliefBefore, s"${after.belief} should exceed ${review.beliefBefore}")
    assert(after.stability > before.stability)
    assertEquals(after.reviewCount, 1)
    assertEquals(after.lapseCount, 0)

  test("a wrong answer lowers belief and shrinks stability"):
    val before = item(belief = 0.8, stability = 30.0, reviewed = Some(t0))
    val (after, _) = Belief.update(before, grade = 0.0, latencyMs = 9000, 1.0, days(1))

    assert(after.belief < 0.8)
    assert(after.stability < 30.0)
    assertEquals(after.lapseCount, 1)

  test("a grade exactly on the pass mark counts as a success"):
    // 0.6 is the boundary between growing and shrinking stability; which side it falls on decides
    // whether a borderline answer schedules the item sooner or later.
    val before = item(belief = 0.4, stability = 10.0, reviewed = Some(t0))
    val (after, _) = Belief.update(before, grade = 0.6, latencyMs = 2000, 1.0, days(1))

    assert(after.stability > before.stability, s"${after.stability} should exceed ${before.stability}")
    assertEquals(after.lapseCount, 0)

  test("stability never falls below half its initial value, however often the item is failed"):
    val fresh = item(belief = 0.5, stability = Belief.initialStability, reviewed = Some(t0))
    val (after, _) = Belief.update(fresh, grade = 0.0, latencyMs = 2000, 1.0, days(1))

    assertEqualsDouble(after.stability, Belief.initialStability * 0.5, 1e-9)
    assert(after.stability > fresh.stability * 0.45, "the floor, not the shrink factor, applies here")

  test("a non-positive latency is treated as instant rather than as impossibly strong evidence"):
    val before = item(belief = 0.4)
    val instant = Belief.update(before, 1.0, latencyMs = 0, 1.0, t0)._1
    val impossible = Belief.update(before, 1.0, latencyMs = -1, 1.0, t0)._1

    // α is exactly the base rate: b ← 0.4 + 0.35·(1 − 0.4).
    assertEqualsDouble(instant.belief, 0.61, 1e-9)
    assertEquals(impossible.belief, instant.belief, "a negative clock reading cannot beat instant")

  test("the update starts from decayed belief, not the stored value"):
    // Reviewing after a long gap should move belief less far than reviewing immediately, because
    // the starting point has decayed toward zero.
    val stale = item(belief = 0.9, stability = 5.0, reviewed = Some(t0))
    val (afterLongGap, review) = Belief.update(stale, 1.0, 2000, 1.0, days(60))

    assert(review.beliefBefore < 0.2, s"belief should have decayed, got ${review.beliefBefore}")
    assert(afterLongGap.belief < 0.9, "a single review should not fully restore a forgotten item")

  test("faster answers move belief further than slow ones"):
    val before = item(belief = 0.4, reviewed = Some(t0))
    val (quick, _) = Belief.update(before, 1.0, 500, 1.0, days(1))
    val (slow, _) = Belief.update(before, 1.0, 20000, 1.0, days(1))

    assert(quick.belief > slow.belief, s"quick=${quick.belief} slow=${slow.belief}")

  test("a more discriminating question moves belief further"):
    val before = item(belief = 0.4, reviewed = Some(t0))
    val (sharp, _) = Belief.update(before, 1.0, 2000, discrimination = 1.5, days(1))
    val (blunt, _) = Belief.update(before, 1.0, 2000, discrimination = 0.5, days(1))

    assert(sharp.belief > blunt.belief)

  test("belief stays within [0,1] under repeated extremes"):
    val perfect = (1 to 30).foldLeft(item(reviewed = Some(t0))): (current, i) =>
      Belief.update(current, 1.0, 1000, 1.0, days(i.toLong))._1
    val failing = (1 to 30).foldLeft(item(reviewed = Some(t0))): (current, i) =>
      Belief.update(current, 0.0, 1000, 1.0, days(i.toLong))._1

    assert(perfect.belief <= 1.0 && perfect.belief >= 0.0, perfect.belief.toString)
    assert(failing.belief <= 1.0 && failing.belief >= 0.0, failing.belief.toString)
    assert(perfect.stability <= Belief.maxStability)
    assert(failing.stability >= 0.0)

  test("every review is logged with before, after and stability"):
    val before = item(belief = 0.5, reviewed = Some(t0))
    val (_, review) = Belief.update(before, 0.8, 1500, 1.0, days(2))

    assertEquals(review.item, before.id)
    assertEquals(review.grade, 0.8)
    assertEquals(review.latencyMs, 1500L)
    assert(review.stabilityAfter > 0.0)

  // ── Entropy (SPEC §4.3) ───────────────────────────────────────────────────

  test("entropy peaks at b = 0.5 and vanishes at the extremes"):
    assertEqualsDouble(Belief.entropy(0.5), 1.0, 0.0001)
    assertEquals(Belief.entropy(0.0), 0.0)
    assertEquals(Belief.entropy(1.0), 0.0)
    assert(Belief.entropy(0.5) > Belief.entropy(0.8))
    assert(Belief.entropy(0.5) > Belief.entropy(0.2))
    assertEqualsDouble(Belief.entropy(0.3), Belief.entropy(0.7), 0.0001, "entropy is symmetric")

  test("a belief outside [0,1] has no entropy rather than an undefined one"):
    // Guarding both ends keeps log₂ off negative arguments, so a corrupt stored belief degrades to
    // "nothing to learn here" instead of poisoning the elucidation queue with NaN weights.
    assertEquals(Belief.entropy(-0.2), 0.0)
    assertEquals(Belief.entropy(1.5), 0.0)

  // ── Derived belief (SPEC §4.4) ─────────────────────────────────────────────

  private def closureFor(axioms: Axiom*) =
    Reasoner.closure(Graph(axioms.map(a => a -> Set[Support](Support.Asserted(a.id))).toMap))

  test("derived belief uses the product of premise beliefs within a justification"):
    val subclass = Axiom.SubClassOf(Person, Agent)
    val assertion = Axiom.ClassAssertion(alice, Person)
    val closure = closureFor(subclass, assertion)
    val derived = Axiom.ClassAssertion(alice, Agent)

    val beliefs = Map(subclass.id -> 0.8, assertion.id -> 0.5)
    val result = DerivedBelief.of(
      derived,
      closure,
      beliefs.get,
      DerivedBelief.Config(inferenceDifficulty = 0.0)
    )

    assertEquals(result.map(b => math.round(b * 1000) / 1000.0), Some(0.4))

  test("the min t-norm is the pessimistic alternative to the product"):
    val subclass = Axiom.SubClassOf(Person, Agent)
    val assertion = Axiom.ClassAssertion(alice, Person)
    val closure = closureFor(subclass, assertion)
    val beliefs = Map(subclass.id -> 0.8, assertion.id -> 0.5)

    val result = DerivedBelief.of(
      Axiom.ClassAssertion(alice, Agent),
      closure,
      beliefs.get,
      DerivedBelief.Config(tnorm = DerivedBelief.Tnorm.Min, inferenceDifficulty = 0.0)
    )
    assertEquals(result, Some(0.5))

  test("fluent-backed premises contribute to derived belief"):
    val fluent = FluentId.unsafe("fl_current_person")
    val subclass = Axiom.SubClassOf(Person, Agent)
    val assertion = Axiom.ClassAssertion(alice, Person)
    val graph = Graph(
      Map(
        subclass -> Set[Support](Support.Asserted(subclass.id)),
        assertion -> Set[Support](Support.FromFluent(fluent))
      )
    )
    val closure = Reasoner.closure(graph)
    val result = DerivedBelief.ofSupports(
      Axiom.ClassAssertion(alice, Agent),
      closure,
      {
        case Support.FromFluent(id) if id == fluent => Some(0.65)
        case _                                      => None
      },
      DerivedBelief.Config(inferenceDifficulty = 0.0)
    )

    assertEquals(result, Some(0.65))

  test("noisy-OR across two derivations exceeds either path alone"):
    val marco = Iri("noesis:e/marco")
    val friendOf = Iri("crm:friendOf")
    val knows = Iri("crm:knows")

    val subProperty = Axiom.SubPropertyOf(friendOf, knows)
    val symmetry = Axiom.SymmetricProperty(knows)
    val viaFriend = Axiom.ObjectAssertion(alice, friendOf, marco)
    val viaSymmetry = Axiom.ObjectAssertion(marco, knows, alice)

    val closure = closureFor(subProperty, symmetry, viaFriend, viaSymmetry)
    val derived = Axiom.ObjectAssertion(alice, knows, marco)
    val beliefs = Map(
      subProperty.id -> 1.0,
      symmetry.id -> 1.0,
      viaFriend.id -> 0.5,
      viaSymmetry.id -> 0.5
    )

    val config = DerivedBelief.Config(inferenceDifficulty = 0.0)
    val combined = DerivedBelief
      .of(derived, closure, beliefs.get, config)
      .getOrElse(fail("expected belief from two tracked derivations"))

    assert(combined > 0.5, s"two independent paths should reinforce each other, got $combined")
    assert(combined <= 1.0)

    // The configured alternative takes the strongest path instead of compounding them, for an owner
    // who does not accept that two derivations are independent evidence.
    val strongest = DerivedBelief.of(derived, closure, beliefs.get, config.copy(noisyOr = false))
    assertEquals(strongest, Some(0.5))
    assert(combined > strongest.getOrElse(1.0), "noisy-OR must exceed the max it replaces")

  test("the inference-difficulty discount lowers belief in longer derivations"):
    val subclass = Axiom.SubClassOf(Person, Agent)
    val assertion = Axiom.ClassAssertion(alice, Person)
    val closure = closureFor(subclass, assertion)
    val derived = Axiom.ClassAssertion(alice, Agent)
    val beliefs = Map(subclass.id -> 1.0, assertion.id -> 1.0)

    val undiscounted =
      DerivedBelief.of(derived, closure, beliefs.get, DerivedBelief.Config(inferenceDifficulty = 0.0))
    val discounted =
      DerivedBelief.of(derived, closure, beliefs.get, DerivedBelief.Config(inferenceDifficulty = 0.2))

    assertEquals(undiscounted, Some(1.0))
    assert(discounted.exists(_ < 1.0), s"expected a discount, got $discounted")

  test("a fact with no tracked premises yields no derived belief rather than a default"):
    val subclass = Axiom.SubClassOf(Person, Agent)
    val assertion = Axiom.ClassAssertion(alice, Person)
    val closure = closureFor(subclass, assertion)

    assertEquals(
      DerivedBelief.of(Axiom.ClassAssertion(alice, Agent), closure, _ => None),
      None,
      "an untracked derivation says nothing about the owner's memory"
    )

  test("an unentailed fact has no derived belief"):
    val closure = closureFor(Axiom.ClassAssertion(alice, Person))
    assertEquals(DerivedBelief.of(Axiom.ClassAssertion(alice, Agent), closure, _ => Some(1.0)), None)

  test("back-propagated credit is attenuated by justification size"):
    val direct = DerivedBelief.backPropagatedCredit(1.0, 1)
    val distant = DerivedBelief.backPropagatedCredit(1.0, 5)

    assert(direct < 1.0, "credit to premises should be attenuated")
    assert(distant < direct, "longer derivations should return less credit")
