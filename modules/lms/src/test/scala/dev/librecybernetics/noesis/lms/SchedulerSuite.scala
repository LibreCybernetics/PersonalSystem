package dev.librecybernetics.noesis.lms

import java.time.Instant

import munit.FunSuite
import dev.librecybernetics.noesis.logic.AxiomId

/** Scheduling (SPEC §4.3). */
class SchedulerSuite extends FunSuite:

  private val t0 = Instant.parse("2026-07-01T12:00:00Z")
  private def days(n: Long): Instant = t0.plusSeconds(n * 86400)

  private def item(
      name: String,
      belief: Double = Belief.prior,
      stability: Double = 10.0,
      reviewed: Option[Instant] = Some(t0),
      origin: ItemOrigin = ItemOrigin.Captured,
      suspended: Boolean = false,
      priorityBoost: Double = 0.0,
      reviewCount: Int = 1
  ): Item =
    Item(
      id = ItemId.unsafe(name),
      kind = ItemKind.AtomicFact,
      axioms = Set(AxiomId.unsafe(s"ax_$name")),
      belief = belief,
      stability = stability,
      lastReviewed = reviewed,
      reviewCount = reviewCount,
      origin = origin,
      suspended = suspended,
      priorityBoost = priorityBoost
    )

  private def flat(u: Double): Item => Double = _ => u

  /** The belief whose entropy is exactly [[Scheduler.minEntropy]].
    *
    * The floor is a closed boundary, so pinning it needs a belief that lands exactly on it. Entropy
    * has no closed-form inverse, so it is found by bisecting the doubles below b = 0.5, where
    * entropy rises monotonically from 0. The bisection converges on the smallest belief the floor
    * does *not* exclude; the test asserts that it really is the boundary before relying on it.
    */
  private val beliefAtEntropyFloor: Double =
    @annotation.tailrec
    def bisect(low: Double, high: Double): Double =
      val mid = low + (high - low) / 2
      if mid == low || mid == high then high
      else if Belief.entropy(mid) < Scheduler.minEntropy then bisect(mid, high)
      else bisect(low, mid)
    bisect(0.0, 0.5)

  test("the retention target scales with utility, holding important facts near 0.95"):
    assertEqualsDouble(Scheduler.retentionTarget(1.0), 0.95, 0.0001)
    assertEqualsDouble(Scheduler.retentionTarget(0.0), 0.70, 0.0001)
    assert(Scheduler.retentionTarget(0.9) > Scheduler.retentionTarget(0.2))

  test("an item above its retention target is not scheduled"):
    val solid = item("solid", belief = 0.99, stability = 1000.0)
    assertEquals(Scheduler.retentionQueue(List(solid), flat(0.5), t0), Nil)

  test("an item whose belief has decayed below target is scheduled"):
    val fading = item("fading", belief = 0.95, stability = 5.0)
    val queue = Scheduler.retentionQueue(List(fading), flat(0.9), days(20))

    assertEquals(queue.length, 1)
    val first = queue.headOption.getOrElse(fail("expected fading item in retention queue"))
    assertEquals(first.mode, QueueMode.Retention)
    assert(first.reason.contains("below target"), first.reason)

  test("high-utility items are scheduled before low-utility ones at equal belief"):
    val important = item("important", belief = 0.5)
    val marginal = item("marginal", belief = 0.5)
    val utilities: Item => Double =
      i => if i.id == important.id then 0.9 else 0.3

    val queue = Scheduler.retentionQueue(List(marginal, important), utilities, t0).sortBy(-_.weight)
    val first = queue.headOption.getOrElse(fail("expected a retention item"))
    assertEquals(first.item.id, important.id)

  test("an item exactly at its retention target is left alone until it slips below"):
    // The target is a closed boundary: at it the owner still knows the fact, so asking would spend a
    // session slot on nothing.
    val target = Scheduler.retentionTarget(1.0)
    val holding = item("holding", belief = target, reviewed = None)
    val slipped = item("slipped", belief = Math.nextDown(target), reviewed = None)

    assertEquals(Scheduler.retentionQueue(List(holding), flat(1.0), t0), Nil)
    assertEquals(Scheduler.retentionQueue(List(slipped), flat(1.0), t0).length, 1)

  test("items below the suspend threshold are stored but never scheduled"):
    val trivial = item("trivial", belief = 0.1)
    assertEquals(Scheduler.retentionQueue(List(trivial), flat(0.05), t0), Nil)
    assertEquals(Scheduler.elucidationQueue(List(trivial), flat(0.05), t0), Nil)

  test("an item exactly at the suspend threshold is still scheduled by both policies"):
    // Suspension is what stops an item being quizzed at all, so the boundary decides whether a
    // marginal fact is merely deprioritized or dropped from the owner's head entirely.
    val marginal = item("marginal", belief = 0.5, reviewed = None)
    val utility = flat(Scheduler.suspendThreshold)

    assertEquals(Scheduler.retentionQueue(List(marginal), utility, t0).length, 1)
    assertEquals(Scheduler.elucidationQueue(List(marginal), utility, t0).length, 1)

  test("an item exactly at the entropy floor is treated as settled rather than probed"):
    assertEquals(
      Belief.entropy(beliefAtEntropyFloor),
      Scheduler.minEntropy,
      s"$beliefAtEntropyFloor should sit exactly on the elucidation floor"
    )
    val settled = item("settled", belief = beliefAtEntropyFloor, reviewed = None)
    val uncertain = item("uncertain", belief = Math.nextUp(beliefAtEntropyFloor), reviewed = None)

    assertEquals(Scheduler.elucidationQueue(List(settled), flat(0.9), t0), Nil)
    assertEquals(Scheduler.elucidationQueue(List(uncertain), flat(0.9), t0).length, 1)

  test("a mastered item (b = 1) stops being scheduled but keeps its score"):
    val mastered = item("mastered", belief = 1.0, stability = 1000.0)
    assertEquals(Scheduler.retentionQueue(List(mastered), flat(0.9), t0), Nil)
    assertEquals(mastered.belief, 1.0, "the score must be retained")
    assert(mastered.isMastered)

  test("mastery stops scheduling even after the decay curve says the item was forgotten"):
    // Mastery is a judgement about the stored score (SPEC §4.2), so it must win over the decayed
    // reading rather than merely coinciding with a belief that happens to stay above target.
    val mastered = item("mastered", belief = 1.0, stability = 5.0)

    assert(
      Belief.at(mastered, days(20)) < Scheduler.retentionTarget(0.9),
      "the decayed belief should be below target, or this proves nothing"
    )
    assertEquals(Scheduler.retentionQueue(List(mastered), flat(0.9), days(20)), Nil)

  test("a suspended item is excluded from both queues"):
    val paused = item("paused", belief = 0.5, suspended = true)
    assertEquals(Scheduler.retentionQueue(List(paused), flat(0.9), t0), Nil)
    assertEquals(Scheduler.elucidationQueue(List(paused), flat(0.9), t0), Nil)

  test("elucidation prefers maximum uncertainty over confident or hopeless items"):
    val uncertain = item("uncertain", belief = 0.5, stability = 10000.0)
    val confident = item("confident", belief = 0.97, stability = 10000.0)
    val unknown = item("unknown", belief = 0.03, stability = 10000.0)

    val queue = Scheduler
      .elucidationQueue(List(confident, unknown, uncertain), flat(0.8), t0)
      .sortBy(-_.weight)

    val first = queue.headOption.getOrElse(fail("expected an elucidation item"))
    assertEquals(first.item.id, uncertain.id, queue.map(e => e.item.id.value -> e.weight).toString)
    assert(first.reason.contains("uncertain"), first.reason)

  test("a certain item yields no elucidation value at all"):
    val certain = item("certain", belief = 1.0, stability = 10000.0)
    assertEquals(Scheduler.elucidationQueue(List(certain), flat(0.9), t0), Nil)

  test("a state-change item outranks an equivalent unchanged item"):
    val changed = item("changed", belief = 0.5, stability = 10000.0, origin = ItemOrigin.StateChange)
    val ordinary = item("ordinary", belief = 0.5, stability = 10000.0)

    val queue = Scheduler.elucidationQueue(List(ordinary, changed), flat(0.8), t0).sortBy(-_.weight)
    val first = queue.headOption.getOrElse(fail("expected a state-change item"))
    assertEquals(first.item.id, changed.id)

  test("a name-change priority boost puts an item at the front of the mixed queue"):
    // SPEC §7.2: name and pronoun supersessions create the highest-priority change items.
    val rename = item(
      "rename",
      belief = 0.5,
      stability = 10000.0,
      origin = ItemOrigin.StateChange,
      priorityBoost = 1.0
    )
    val urgent = item("urgent", belief = 0.1, stability = 10000.0)

    val queue = Scheduler.queue(List(urgent, rename), flat(0.9), t0, QueueMode.Mixed, limit = 5)
    val first = queue.headOption.getOrElse(fail("expected a mixed-queue item"))
    assertEquals(first.item.id, rename.id, queue.map(e => e.item.id.value -> e.weight).toString)

  test("a never-reviewed item is probed before an equally uncertain one already practiced"):
    val novel = item("novel", belief = 0.5, stability = 10000.0, reviewCount = 0)
    val practiced = item("practiced", belief = 0.5, stability = 10000.0, reviewCount = 1)

    val weights = Scheduler
      .elucidationQueue(List(practiced, novel), flat(0.8), t0)
      .map(e => e.item.id.value -> e.weight)
      .toMap

    assert(
      weights.getOrElse("novel", 0.0) > weights.getOrElse("practiced", 0.0),
      weights.toString
    )

  test("a historical item is ranked below a current one"):
    val historical = item("hist", belief = 0.5, stability = 10000.0, origin = ItemOrigin.Historical)
    val current = item("cur", belief = 0.5, stability = 10000.0)

    val queue = Scheduler.elucidationQueue(List(historical, current), flat(0.8), t0).sortBy(-_.weight)
    val first = queue.headOption.getOrElse(fail("expected a current elucidation item"))
    assertEquals(first.item.id, current.id)

  test("the mixed queue does not schedule the same item twice"):
    val fading = item("fading", belief = 0.5, stability = 5.0)
    val queue = Scheduler.queue(List(fading), flat(0.9), days(10), QueueMode.Mixed, limit = 10)

    assertEquals(queue.map(_.item.id).distinct.length, queue.length)

  test("the queue respects its limit"):
    val items = (1 to 50).map(i => item(s"i$i", belief = 0.5, stability = 1.0)).toList
    assertEquals(Scheduler.queue(items, flat(0.9), days(30), QueueMode.Mixed, limit = 7).length, 7)

  test("exploration reserves slots for low-utility items so wrong scores stay discoverable"):
    // SPEC §4.3 and §12.10: without this, an unimportant-scored fact is never quizzed and its
    // errors stay hidden forever.
    val important = (1 to 30).map(i => item(s"hi$i", belief = 0.4, stability = 1.0)).toList
    val ignored = (1 to 5).map(i => item(s"lo$i", belief = 0.4, stability = 1.0)).toList
    val utilities: Item => Double = i => if i.id.value.startsWith("lo") then 0.02 else 0.9

    val queue = Scheduler.queue(important ++ ignored, utilities, days(10), QueueMode.Mixed, limit = 20)
    val explored = queue.filter(_.reason.contains("exploration"))

    assertEquals(queue.length, 20)
    assert(explored.nonEmpty, "no exploration slots were allocated")
    assert(explored.forall(_.utility < Scheduler.suspendThreshold))

  test("exploration takes the least-believed eligible items, and only eligible ones"):
    // Which items fill the reserved slots is the whole point: they must be the low-utility ones the
    // owner is least likely to remember, so a wrong utility score surfaces as a wrong answer.
    val fading = (1 to 25).map(i => item(s"hi$i", belief = 0.4, stability = 1.0)).toList
    val ignored = (1 to 5).map(i => item(s"lo$i", belief = 0.1 * i, stability = 10000.0)).toList
    val paused = item("loPaused", belief = 0.01, stability = 10000.0, suspended = true)
    // Exactly at the threshold, so it is not low-utility; ranked out of a full session by weight.
    val borderline = item("borderline", belief = 0.0, stability = 10000.0)

    val utilities: Item => Double =
      i =>
        if i.id.value == "borderline" then Scheduler.suspendThreshold
        else if i.id.value.startsWith("lo") then 0.02
        else 0.9

    val queue =
      Scheduler.queue(fading ++ ignored ++ List(paused, borderline), utilities, days(10), limit = 20)
    val explored = queue.filter(_.reason.contains("exploration")).map(_.item.id.value)

    assertEquals(queue.length, 20)
    assertEquals(explored, List("lo1", "lo2"), queue.map(_.item.id.value).toString)

  test("a small session spends no slots on exploration"):
    val items = (1 to 5).map(i => item(s"i$i", belief = 0.4, stability = 1.0)).toList
    val low = List(item("lo", belief = 0.4, stability = 1.0))
    val utilities: Item => Double = i => if i.id.value == "lo" then 0.02 else 0.9

    val queue = Scheduler.queue(items ++ low, utilities, days(10), QueueMode.Mixed, limit = 5)
    assert(queue.forall(!_.reason.contains("exploration")))

  test("the session budget splits across modules in proportion to utility mass"):
    val crmItems = (1 to 3).map(i => item(s"crm$i")).toList
    val vfItems = (1 to 3).map(i => item(s"vf$i")).toList
    val moduleOf: Item => String = i => if i.id.value.startsWith("crm") then "crm" else "vf"
    val utilities: Item => Double = i => if moduleOf(i) == "crm" then 0.9 else 0.1

    val budget = Scheduler.budget(crmItems ++ vfItems, utilities, moduleOf, total = 20)

    assert(budget.byModule("crm") > budget.byModule("vf"))
    assertEquals(budget.byModule.values.sum, 20)

  test("a budget over items with no utility mass degrades gracefully"):
    val budget = Scheduler.budget(Nil, flat(0.0), _ => "crm", total = 10)
    assertEquals(budget, SessionBudget(10, Map.empty))

  test("a non-positive utility mass allocates nothing rather than dividing by it"):
    // Real items with a degenerate utility function are the dangerous case: the empty list never
    // reaches the division, but a module whose every item scores zero does.
    val items = List(item("crm1"), item("crm2"))

    assertEquals(
      Scheduler.budget(items, flat(0.0), _ => "crm", total = 10),
      SessionBudget(10, Map.empty),
      "zero mass must not allocate a NaN share"
    )
    assertEquals(
      Scheduler.budget(items, flat(-0.5), _ => "crm", total = 10),
      SessionBudget(10, Map.empty),
      "negative mass must not allocate a share either"
    )
