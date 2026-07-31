package noesis.lms

import java.time.Instant

/** Which selection policy produced a queue entry (SPEC §4.3). */
enum QueueMode:
  /** Spaced repetition: review when predicted belief falls below the utility-scaled target. */
  case Retention

  /** Maximum-information probing where belief is most uncertain. */
  case Elucidation
  case Mixed

/** One scheduled review, with the reason it was chosen.
  *
  * The reason is not decoration: SPEC §12.10 warns that utility-based scheduling hides its own
  * mistakes, so a queue the owner can interrogate ("why am I being asked this?") is part of the
  * mitigation.
  */
final case class QueueEntry(
    item: Item,
    mode: QueueMode,
    weight: Double,
    /** Decay-adjusted belief at scheduling time. */
    belief: Double,
    utility: Double,
    reason: String
)

/** Session budget allocation across modules, by utility mass (SPEC §4.3). */
final case class SessionBudget(total: Int, byModule: Map[String, Int])

/** The two selection policies and their composition (SPEC §4.3). */
object Scheduler:

  /** Retention target: high-utility facts are held near 0.95, marginal ones allowed to fade. */
  def retentionTarget(utility: Double): Double = 0.70 + 0.25 * utility

  /** Below this utility an item is stored-but-suspended: in the KB, not in your head. */
  val suspendThreshold: Double = 0.15

  /** At or below this entropy an item is effectively settled: a question would tell us nothing. */
  val minEntropy: Double = 0.01

  /** Fraction of each session spent sampling low-utility items so mis-scored utility stays
    * discoverable (SPEC §4.3, §12.10).
    */
  val explorationFraction: Double = 0.1

  /** Items whose predicted belief has fallen below their retention target. */
  def retentionQueue(
      items: List[Item],
      utilityOf: Item => Double,
      now: Instant
  ): List[QueueEntry] =
    items.filter(_.isActive).flatMap { item =>
      val utility = utilityOf(item)
      val belief = Belief.at(item, now)
      val target = retentionTarget(utility)

      // b = 1 means mastered: scheduling stops but the score is kept (SPEC §4.2).
      if item.isMastered then None
      else if utility < suspendThreshold then None
      else if belief >= target then None
      else
        val urgency = target - belief
        Some(
          QueueEntry(
            item,
            QueueMode.Retention,
            weight = urgency * (0.5 + utility) + item.priorityBoost,
            belief = belief,
            utility = utility,
            reason = f"belief $belief%.2f below target $target%.2f"
          )
        )
    }

  /** Items where a single question is most informative: `w = H(b) · u · recencyBoost` (SPEC §4.3). */
  def elucidationQueue(
      items: List[Item],
      utilityOf: Item => Double,
      now: Instant
  ): List[QueueEntry] =
    items.filter(_.isActive).flatMap { item =>
      val utility = utilityOf(item)
      val belief = Belief.at(item, now)
      val entropy = Belief.entropy(belief)

      if utility < suspendThreshold || entropy <= minEntropy then None
      else
        val weight = entropy * utility * recencyBoost(item) + item.priorityBoost
        Some(
          QueueEntry(
            item,
            QueueMode.Elucidation,
            weight = weight,
            belief = belief,
            utility = utility,
            reason = f"belief $belief%.2f is uncertain (entropy $entropy%.2f)"
          )
        )
    }

  /** Never-reviewed and recently-changed items are worth probing sooner. */
  private def recencyBoost(item: Item): Double =
    val fromOrigin = item.origin match
      case ItemOrigin.StateChange => 1.5
      case ItemOrigin.Historical  => 0.6
      case ItemOrigin.Captured    => 1.0
    val fromNovelty = if item.reviewCount == 0 then 1.3 else 1.0
    fromOrigin * fromNovelty

  /** The composed session queue.
    *
    * Change items enter with elevated priority (SPEC §3.6) — carried by `priorityBoost` rather than
    * by a separate queue, so a change item still competes on belief and utility instead of jumping
    * ahead of something the owner is actively forgetting.
    */
  def queue(
      items: List[Item],
      utilityOf: Item => Double,
      now: Instant,
      mode: QueueMode = QueueMode.Mixed,
      limit: Int = 20
  ): List[QueueEntry] =
    val entries = mode match
      case QueueMode.Retention   => retentionQueue(items, utilityOf, now)
      case QueueMode.Elucidation => elucidationQueue(items, utilityOf, now)
      case QueueMode.Mixed =>
        val retention = retentionQueue(items, utilityOf, now)
        val scheduled = retention.map(_.item.id).toSet
        retention ++ elucidationQueue(items, utilityOf, now).filterNot(e => scheduled(e.item.id))

    val ranked = entries.sortBy(entry => (-entry.weight, entry.item.id.value)).take(limit)
    withExploration(ranked, items, utilityOf, now, limit)

  /** Reserves a slice of the session for low-utility items.
    *
    * Without this, a fact scored unimportant is never quizzed, so an error in it is never found and
    * the low score never gets challenged — the feedback loop §12.10 calls out.
    */
  private def withExploration(
      ranked: List[QueueEntry],
      all: List[Item],
      utilityOf: Item => Double,
      now: Instant,
      limit: Int
  ): List[QueueEntry] =
    // Truncation is what keeps a short session free of exploration: a tenth of anything under ten
    // slots is none, so no separate floor is needed.
    val slots = (limit * explorationFraction).toInt.max(0)
    val chosen = ranked.map(_.item.id).toSet
    val explorable = all
      .filter(item => item.isActive && !chosen(item.id) && utilityOf(item) < suspendThreshold)
      .sortBy(item => (Belief.at(item, now), item.id.value))
      .take(slots)
      .map: item =>
        QueueEntry(
          item,
          QueueMode.Elucidation,
          weight = 0.0,
          belief = Belief.at(item, now),
          utility = utilityOf(item),
          reason = "exploration sample: checking a low-utility score is right"
        )
    ranked.dropRight(explorable.length) ++ explorable

  /** Allocates a session budget across modules in proportion to their utility mass (SPEC §4.3). */
  def budget(
      items: List[Item],
      utilityOf: Item => Double,
      moduleOf: Item => String,
      total: Int
  ): SessionBudget =
    val mass = items
      .filter(_.isActive)
      .groupMapReduce(moduleOf)(utilityOf)(_ + _)
    val totalMass = mass.values.sum

    if totalMass <= 0 then SessionBudget(total, Map.empty)
    else
      val allocated = mass.view.mapValues(m => math.round(total * m / totalMass).toInt).toMap
      SessionBudget(total, allocated)
