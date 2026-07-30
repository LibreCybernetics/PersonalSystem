package noesis.logic

import java.time.Instant

import cats.Order
import io.circe.derivation.{ConfiguredCodec, ConfiguredEnumCodec}
import io.circe.syntax.*
import io.circe.{Codec, Decoder, DecodingFailure, Encoder, Json}

/** What may cross the system boundary (SPEC §3.3.1).
  *
  * Since there are no co-users, this governs egress only: remote LLM providers, MCP agents, exports
  * and sync. The ordering is a total order of increasing restriction, which is what makes the
  * min/max derivation rule in `noesis.core.policy.Disclosure` well-defined.
  */
enum Sensitivity(val rank: Int) derives ConfiguredEnumCodec:
  /** Findable on the public internet. */
  case Public extends Sensitivity(0)

  /** Learnable only inside a specific org or from specific people; carries a `knowledgeScope`. */
  case Internal extends Sensitivity(1)

  /** Non-sensitive personal information about oneself or others. */
  case Personal extends Sensitivity(2)

  /** Health, finances, legal, identity history, protected attributes. Never leaves the device. */
  case Sensitive extends Sensitivity(3)

object Sensitivity:
  given Order[Sensitivity] = Order.by(_.rank)
  given Ordering[Sensitivity] = Order[Sensitivity].toOrdering

  /** The most restrictive of the two — how sensitivity combines *within* one justification. */
  def max(a: Sensitivity, b: Sensitivity): Sensitivity = Order[Sensitivity].max(a, b)

  /** The least restrictive of the two — how sensitivity combines *across* justifications. */
  def min(a: Sensitivity, b: Sensitivity): Sensitivity = Order[Sensitivity].min(a, b)

  def parse(s: String): Either[String, Sensitivity] =
    values.find(_.toString.equalsIgnoreCase(s)).toRight(s"unknown sensitivity: $s")

/** Lifecycle of an asserted axiom (SPEC §3.3). */
enum AxiomStatus derives ConfiguredEnumCodec:
  case Active

  /** Withdrawn. Retained in the journal; excluded from every projection. */
  case Retracted

  /** Known to conflict with something else; excluded from reasoning rather than chosen between. */
  case Disputed

/** Where a fact came from (SPEC §3.3, §10 auditability). */
final case class Provenance(
    captureSession: Option[String] = None,
    sourceSpan: Option[String] = None,
    reference: Option[Iri] = None,
    locator: Option[String] = None,
    /** The external agent that proposed this, if any — MCP proposals are badged (SPEC §9). */
    proposedBy: Option[String] = None,
    at: Option[Instant] = None
) derives ConfiguredCodec

object Provenance:
  val empty: Provenance = Provenance()

  /** Provenance for something the owner asserted directly. */
  def owner(session: Option[String] = None): Provenance = Provenance(captureSession = session)

/** Explicit per-axiom annotation values.
  *
  * Every field is optional on purpose: `None` means "no owner override, let the cascade decide"
  * (SPEC §3.3). Storing a resolved value here would freeze it and defeat class policies and module
  * defaults, so only genuine overrides are recorded.
  */
final case class AxiomAnnotations(
    truthConfidence: Option[Double] = None,
    sensitivity: Option[Sensitivity] = None,
    knowledgeScope: Set[Iri] = Set.empty,
    recallUtility: Option[Double] = None,
    provenance: Provenance = Provenance.empty
) derives ConfiguredCodec:

  def withSensitivity(s: Sensitivity, scope: Set[Iri] = Set.empty): AxiomAnnotations =
    copy(sensitivity = Some(s), knowledgeScope = if scope.nonEmpty then scope else knowledgeScope)

  def withUtility(u: Double): AxiomAnnotations = copy(recallUtility = Some(u.max(0.0).min(1.0)))

object AxiomAnnotations:
  val empty: AxiomAnnotations = AxiomAnnotations()

  /** Owner-confirmed facts default to `truthConfidence` 1.0 (SPEC §3.3). */
  val ownerConfirmed: AxiomAnnotations = AxiomAnnotations(truthConfidence = Some(1.0))

/** One field of an annotation patch: leave it, clear the override, or set a value.
  *
  * Three explicit states rather than `Option[Option[A]]`. The nested-option encoding collapses
  * `Some(None)` to JSON `null`, which is indistinguishable from "absent" on the way back in — so
  * clearing an override would silently become a no-op on replay. Since a journal that cannot be
  * replayed faithfully violates SPEC §4, the distinction has to survive serialization.
  */
enum Patch[+A]:
  case Leave
  case Clear
  case SetTo(value: A)

  def applyTo[B >: A](current: Option[B]): Option[B] = this match
    case Leave        => current
    case Clear        => None
    case SetTo(value) => Some(value)

  def isLeave: Boolean = this == Leave

object Patch:
  def of[A](value: A): Patch[A] = SetTo(value)

  def fromOption[A](value: Option[A]): Patch[A] = value.fold[Patch[A]](Clear)(SetTo(_))

  given [A: Encoder]: Encoder[Patch[A]] = Encoder.instance:
    case Leave        => Json.obj("op" -> Json.fromString("leave"))
    case Clear        => Json.obj("op" -> Json.fromString("clear"))
    case SetTo(value) => Json.obj("op" -> Json.fromString("set"), "value" -> value.asJson)

  given [A: Decoder]: Decoder[Patch[A]] = Decoder.instance: cursor =>
    cursor.get[String]("op").flatMap:
      case "leave" => Right(Leave)
      case "clear" => Right(Clear)
      case "set"   => cursor.get[A]("value").map(SetTo(_))
      case other   => Left(DecodingFailure(s"unknown patch op: $other", cursor.history))

/** A partial update to an axiom's annotations, as journaled by an `Annotate` operation. */
final case class AnnotationPatch(
    truthConfidence: Patch[Double] = Patch.Leave,
    sensitivity: Patch[Sensitivity] = Patch.Leave,
    knowledgeScope: Patch[Set[Iri]] = Patch.Leave,
    recallUtility: Patch[Double] = Patch.Leave
) derives ConfiguredCodec:

  def applyTo(a: AxiomAnnotations): AxiomAnnotations =
    AxiomAnnotations(
      truthConfidence = truthConfidence.applyTo(a.truthConfidence),
      sensitivity = sensitivity.applyTo(a.sensitivity),
      knowledgeScope = knowledgeScope.applyTo(Some(a.knowledgeScope)).getOrElse(Set.empty),
      recallUtility = recallUtility.applyTo(a.recallUtility),
      provenance = a.provenance
    )

  def isEmpty: Boolean =
    truthConfidence.isLeave && sensitivity.isLeave && knowledgeScope.isLeave &&
      recallUtility.isLeave

/** Annotation values after the cascade has run — what callers actually act on. */
final case class EffectiveAnnotations(
    truthConfidence: Double,
    sensitivity: Sensitivity,
    knowledgeScope: Set[Iri],
    recallUtility: Double
) derives Codec.AsObject
