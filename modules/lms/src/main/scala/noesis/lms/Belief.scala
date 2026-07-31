package noesis.lms

import java.time.{Duration, Instant}

import noesis.logic.*
import noesis.reasoner.{Closure, Support}

/** The belief model (SPEC §4.2).
  *
  * `b ∈ [0,1]` is the estimated probability the owner would, right now, correctly recall or apply
  * the fact. It is deliberately *not* `truthConfidence`: one is about the owner's memory, the other
  * about the world, and §4.2 is explicit that they are never mixed.
  */
object Belief:
  /** Where a freshly drafted item starts: maximum uncertainty, which is also where elucidation
    * questions are most informative (SPEC §4.3).
    */
  val prior: Double = 0.5

  /** Initial stability in days. */
  val initialStability: Double = 1.0

  val maxStability: Double = 365.0 * 5

  /** Base learning rate for the update `b ← b + α·(g − b)`. */
  val baseAlpha: Double = 0.35

  /** Decay-adjusted belief at `now`: `b(t) = b_last · e^(−Δt/S)` (SPEC §4.2).
    *
    * Every read goes through here. An item never reviewed has no decay anchor, so it stays at its
    * prior rather than decaying from a review that never happened.
    */
  def at(item: Item, now: Instant): Double =
    item.lastReviewed match
      case None => clamp(item.belief)
      case Some(last) =>
        // Elapsed time floors at zero instead of being special-cased: reading a review as of a
        // moment before it happened must not *raise* belief, and e⁰ = 1 leaves it untouched.
        val days = Duration.between(last, now).toSeconds.toDouble.max(0.0) / 86400.0
        clamp(item.belief * math.exp(-days / item.stability.max(0.01)))

  /** Applies a review outcome (SPEC §4.2).
    *
    * α is modulated by latency and question discrimination: a fast correct answer is stronger
    * evidence than a slow one, and a question that discriminates well moves belief further.
    */
  def update(
      item: Item,
      grade: Double,
      latencyMs: Long,
      discrimination: Double,
      now: Instant
  ): (Item, Review) =
    val decayed = at(item, now)
    val alpha = clamp(baseAlpha * discrimination * latencyFactor(latencyMs))
    val updated = clamp(decayed + alpha * (clamp(grade) - decayed))

    // Stability grows on success and shrinks on failure. Scaling growth by the *surprise*
    // (1 - decayed) is what makes a correct answer on a nearly-forgotten item worth more than one
    // on an item that was already solid.
    val success = grade >= 0.6
    val stability =
      if success then math.min(maxStability, item.stability * (1.4 + 0.6 * (1.0 - decayed)))
      else math.max(initialStability * 0.5, item.stability * 0.45)

    val next = item.copy(
      belief = updated,
      stability = stability,
      lastReviewed = Some(now),
      reviewCount = item.reviewCount + 1,
      lapseCount = item.lapseCount + (if success then 0 else 1)
    )

    (next, Review(item.id, None, grade, latencyMs, now, decayed, updated, stability))

  /** Faster answers count for more, saturating so a 50 ms answer is not 100× a 5 s one. */
  private def latencyFactor(latencyMs: Long): Double =
    if latencyMs <= 0 then 1.0
    else
      val seconds = latencyMs.toDouble / 1000.0
      0.7 + 0.6 * math.exp(-seconds / 8.0)

  /** Shannon entropy of a Bernoulli belief, peaking at `b = 0.5` (SPEC §4.3 elucidation).
    *
    * This is the whole justification for the elucidation queue: one question at maximum entropy
    * yields maximal information about what the owner actually knows.
    */
  def entropy(b: Double): Double =
    if b <= 0.0 || b >= 1.0 then 0.0
    else -(b * log2(b) + (1 - b) * log2(1 - b))

  private def log2(x: Double): Double = math.log(x) / math.log(2)

  def clamp(d: Double): Double = d.max(0.0).min(1.0)

/** Belief in facts the owner never asserted but could derive (SPEC §4.4).
  *
  * Combination rules: a t-norm *within* a justification (you need all its premises), the dual
  * *across* justifications (you need only one path). Optionally discounted by justification size,
  * because knowing the premises does not guarantee having connected them.
  */
object DerivedBelief:

  /** How premises combine inside one justification. */
  enum Tnorm:
    case Product
    case Min

  final case class Config(
      tnorm: Tnorm = Tnorm.Product,
      /** Use noisy-OR across justifications; otherwise take the max. */
      noisyOr: Boolean = true,
      /** Per-extra-premise penalty for not having made the connection. 0 disables the discount. */
      inferenceDifficulty: Double = 0.08
  )

  object Config:
    val default: Config = Config()

  /** Belief in `axiom`, or `None` when nothing justifies it or no premise carries belief.
    *
    * `beliefOf` maps a premise axiom to the owner's belief in it; premises with no learning item
    * (schema axioms, typically) are treated as known, since the owner is not being quizzed on them.
    */
  def of(
      axiom: Axiom,
      closure: Closure,
      beliefOf: AxiomId => Option[Double],
      config: Config = Config.default
  ): Option[Double] =
    ofSupports(
      axiom,
      closure,
      {
        case Support.Asserted(id) => beliefOf(id)
        case Support.FromFluent(_) => None
      },
      config
    )

  /** Derived belief with a resolver for every journal-backed support kind.
    *
    * Fluent materializations are premises too. Ignoring them made conclusions based on current
    * names, employment and pronouns look untracked even when their projected assertion had an item
    * (SPEC §3.6, §4.4).
    */
  def ofSupports(
      axiom: Axiom,
      closure: Closure,
      beliefOf: Support => Option[Double],
      config: Config = Config.default
  ): Option[Double] =
    // An unentailed axiom has no justifications at all, so it needs no branch of its own: the
    // empty-path case below already answers "this says nothing about the owner's memory".
    val perPath = closure.justificationsFor(axiom).toList.flatMap: justification =>
      val premiseBeliefs = justification.premises.toList.map(beliefOf)
      val known = premiseBeliefs.flatten
      // Nothing in this path is tracked: it tells us nothing about the owner's memory.
      Option.when(known.nonEmpty):
        val combined = config.tnorm match
          case Tnorm.Product => known.product
          case Tnorm.Min     => known.minOption.getOrElse(1.0)
        val discount = 1.0 - config.inferenceDifficulty * (justification.size - 1).max(0)
        Belief.clamp(combined * discount.max(0.0))

    if perPath.isEmpty then None
    else if config.noisyOr then Some(Belief.clamp(1.0 - perPath.map(1.0 - _).product))
    else perPath.maxOption

  /** Attenuated credit to propagate back to premise items after reviewing a derived fact (§4.4). */
  def backPropagatedCredit(grade: Double, justificationSize: Int): Double =
    grade * (1.0 / (1 + justificationSize).toDouble.max(1.0))
