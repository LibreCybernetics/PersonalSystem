package noesis.lms

import java.time.Instant
import java.util.Locale

import io.circe.derivation.{ConfiguredCodec, ConfiguredEnumCodec}
import noesis.logic.*
import noesis.logic.given

opaque type ItemId = String

object ItemId:
  def of(kind: ItemKind, axioms: Set[AxiomId]): ItemId =
    val key = axioms.toList.sorted.map(_.value).mkString(",")
    s"it_${kind.toString.toLowerCase(Locale.ROOT)}_${Integer.toHexString(key.hashCode)}"

  def unsafe(value: String): ItemId = value

  extension (id: ItemId) def value: String = id

  given cats.Order[ItemId] = cats.Order.by(_.value)
  given Ordering[ItemId] = cats.Order[ItemId].toOrdering
  given io.circe.Encoder[ItemId] = io.circe.Encoder.encodeString.contramap(_.value)
  given io.circe.Decoder[ItemId] = io.circe.Decoder.decodeString.map(unsafe)

/** What kind of knowledge an item represents (SPEC §4.1). */
enum ItemKind derives ConfiguredEnumCodec:
  /** One assertion, quizzed bidirectionally as cloze or Q&A. */
  case AtomicFact

  /** A small cluster of axioms that only makes sense together. */
  case Composite

  /** A TBox-level idea, assessed by generated questions rather than recall of one value. */
  case Concept

  /** Module-defined, with custom generators and graders. */
  case Skill

/** What the policy cascade decides should happen when a matching axiom is added (SPEC §4.1). */
enum ItemPolicy derives ConfiguredEnumCodec:
  /** Create an active item immediately — `crm:birthday` always. */
  case AutoActivate

  /** Create a suspended item for the owner to approve. */
  case DraftForReview

  /** Create nothing — phone numbers, unless starred. */
  case Ignore

/** Why an item is scheduled the way it is. Distinguishing these is what lets change items jump the
  * queue (SPEC §3.6) without special-casing the scheduler.
  */
enum ItemOrigin derives ConfiguredEnumCodec:
  case Captured

  /** Created because a fluent's value changed: the entrenched old answer will interfere, so the
    * change itself must be drilled ("Where does A work *now*?").
    */
  case StateChange

  /** A fact that is no longer current but retains utility (SPEC §3.6). */
  case Historical

/** A learning item: the unit of scheduling and belief (SPEC §4.1, §4.2).
  *
  * Belief and stability are stored as of `lastReviewed`; every read decays them to the present
  * (§4.2), so a stored `belief` is never used directly. That is deliberate — caching a decayed value
  * would make the number depend on when it was last written rather than on when it is read.
  */
final case class Item(
    id: ItemId,
    kind: ItemKind,
    axioms: Set[AxiomId],
    /** Belief at `lastReviewed`, not now. Use [[Belief.at]] to read it. */
    belief: Double = Belief.prior,
    /** Memory stability in days; grows on success, shrinks on failure (FSRS-style). */
    stability: Double = Belief.initialStability,
    lastReviewed: Option[Instant] = None,
    reviewCount: Int = 0,
    lapseCount: Int = 0,
    origin: ItemOrigin = ItemOrigin.Captured,
    /** Suspended items keep their score but are never scheduled (SPEC §4.2, §4.3). */
    suspended: Boolean = false,
    /** Owner-forced priority, used by name/pronoun change items (SPEC §7.2). */
    priorityBoost: Double = 0.0,
    /** A short human-readable rendering, so queues can be shown without the whole KB. */
    prompt: String = ""
) derives ConfiguredCodec:

  def isActive: Boolean = !suspended

  /** `b = 1` suspends scheduling but keeps the score; `b = 0` is a known-unknown (SPEC §4.2). */
  def isMastered: Boolean = belief >= 1.0
  def isKnownUnknown: Boolean = belief <= 0.0 && reviewCount == 0

/** A stored question about an item (SPEC §4.1). */
enum QuestionFormat derives ConfiguredEnumCodec:
  case Cloze
  case MultipleChoice
  case ShortAnswer
  case Case
  case Translate

/** How an answer is judged. */
enum AnswerSpec derives ConfiguredCodec:
  case Exact(value: String)

  /** Any member of the set counts — production graded against a concept's full lexicalization set,
    * so synonyms are not marked wrong (SPEC §6).
    */
  case AnyOf(values: Set[String])

  /** Judged against a rubric; the reference axioms are always displayed (SPEC §4.1). */
  case Rubric(criteria: String)

  def grade(response: String): Option[Double] =
    def norm(s: String) = s.trim.toLowerCase(Locale.ROOT)
    this match
      case Exact(value)   => Some(if norm(response) == norm(value) then 1.0 else 0.0)
      case AnyOf(values)  => Some(if values.map(norm).contains(norm(response)) then 1.0 else 0.0)
      // A rubric needs a judge (LLM or owner); the MVP has neither, so it declines to guess.
      case Rubric(_) => None

/** A question, stored rather than generated on the fly (SPEC §4.1).
  *
  * `sourceHash` is what makes staleness detectable: when an axiom the question was built from
  * changes, the hash no longer matches and the question is marked for regeneration instead of
  * quietly asking about a fact that has since changed.
  */
final case class Question(
    id: String,
    item: ItemId,
    format: QuestionFormat,
    prompt: String,
    answer: AnswerSpec,
    distractors: List[String] = Nil,
    sourceHash: String,
    generator: String = "template",
    /** Light IRT stats: how well this question discriminates, used to modulate α (SPEC §4.2). */
    discrimination: Double = 1.0,
    asked: Int = 0,
    correct: Int = 0
) derives ConfiguredCodec:

  def isStale(currentHash: String): Boolean = sourceHash != currentHash

object Question:
  /** A content hash over the axioms a question was built from. */
  def hashOf(axioms: Set[AxiomId]): String =
    Integer.toHexString(axioms.toList.sorted.map(_.value).mkString(",").hashCode)

/** A recorded review outcome — the raw data SPEC §12.3 insists be logged from day one so belief
  * parameters can be refit per owner later.
  */
final case class Review(
    item: ItemId,
    question: Option[String],
    grade: Double,
    latencyMs: Long,
    at: Instant,
    beliefBefore: Double,
    beliefAfter: Double,
    stabilityAfter: Double
) derives ConfiguredCodec
