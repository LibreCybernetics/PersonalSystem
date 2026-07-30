package noesis.lms

import java.time.Instant

import munit.FunSuite
import noesis.core.model.AxiomId

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

  test("items below the suspend threshold are stored but never scheduled"):
    val trivial = item("trivial", belief = 0.1)
    assertEquals(Scheduler.retentionQueue(List(trivial), flat(0.05), t0), Nil)
    assertEquals(Scheduler.elucidationQueue(List(trivial), flat(0.05), t0), Nil)

  test("a mastered item (b = 1) stops being scheduled but keeps its score"):
    val mastered = item("mastered", belief = 1.0, stability = 1000.0)
    assertEquals(Scheduler.retentionQueue(List(mastered), flat(0.9), t0), Nil)
    assertEquals(mastered.belief, 1.0, "the score must be retained")
    assert(mastered.isMastered)

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
