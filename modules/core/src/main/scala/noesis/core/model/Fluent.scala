package noesis.core.model

import java.time.LocalDate

import cats.Order
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import io.circe.derivation.{ConfiguredCodec, ConfiguredEnumCodec}
import io.circe.{Decoder, Encoder}

opaque type FluentId = String

object FluentId:
  def fresh[F[_]: UUIDGen: cats.Functor]: F[FluentId] =
    UUIDGen[F].randomUUID.map(uuid => s"fl_$uuid")

  def unsafe(value: String): FluentId = value

  extension (id: FluentId) def value: String = id

  given Order[FluentId] = Order.by(_.value)
  given Ordering[FluentId] = Order[FluentId].toOrdering
  given Encoder[FluentId] = Encoder.encodeString.contramap(_.value)
  given Decoder[FluentId] = Decoder.decodeString.map(unsafe)
  given io.circe.KeyEncoder[FluentId] = io.circe.KeyEncoder.encodeKeyString.contramap(_.value)
  given io.circe.KeyDecoder[FluentId] = io.circe.KeyDecoder.decodeKeyString.map(unsafe)

/** Why a fluent stopped holding (SPEC §3.6). */
enum EndReason derives ConfiguredEnumCodec:
  /** The state genuinely ended and nothing replaced it. */
  case Ended

  /** A new state took its place — a job change, a rename. Pairs with `supersededBy`. */
  case Superseded

  /** It never held as recorded; this is a data fix, not a change in the world. */
  case Corrected

object EndReason:
  def parse(s: String): Either[String, EndReason] =
    values.find(_.toString.equalsIgnoreCase(s)).toRight(s"unknown end reason: $s")

/** One continuous state of a time-varying property (SPEC §3.6).
  *
  * The point of fluents is that "A started at Acme in January, left in July" is *one* state with
  * two boundaries, not two or four separate facts. Ongoing fluents materialize into the current
  * graph as plain triples, so ordinary reasoning about "now" needs no temporal machinery.
  */
final case class Fluent(
    id: FluentId,
    statedSubject: Iri,
    statedProperty: Iri,
    statedValue: Node,
    /** Absent means the start is unknown, not that it started at the epoch. */
    validFrom: Option[PartialDate] = None,
    /** Absent means ongoing — this is what makes the fluent materialize into `graph:current`. */
    validTo: Option[PartialDate] = None,
    endReason: Option[EndReason] = None,
    supersededBy: Option[FluentId] = None,
    annotations: AxiomAnnotations = AxiomAnnotations.empty
) derives ConfiguredCodec:

  /** Is this state still holding?
    *
    * An `endReason` closes a fluent even when `validTo` is unknown. The spec reads an absent
    * `validTo` as "ongoing", but that is about a state nobody has ended — a supersession whose
    * boundary date the owner did not supply has definitely ended, and treating it as ongoing would
    * put two simultaneous current employers in the current graph.
    */
  def isOngoing: Boolean = validTo.isEmpty && endReason.isEmpty

  /** The plain assertion this fluent stands for, as projected into the current graph. */
  def assertion: Axiom = statedValue match
    case Node.Ref(iri) => Axiom.ObjectAssertion(statedSubject, statedProperty, iri)
    case Node.Lit(lit) => Axiom.DataAssertion(statedSubject, statedProperty, lit)

  def triple: Triple = Triple(statedSubject, statedProperty, statedValue)

  /** Did this state hold on `date`?
    *
    * An absent `validFrom` means the start is unknown, not that the state never started, so it does
    * not disqualify the fluent. An unplaceable *end*, by contrast, does: if the state is known to
    * have ended but not when, no date can be claimed to fall inside it.
    */
  def heldOn(date: LocalDate): Boolean =
    val startedBy = validFrom.flatMap(_.lowerBound).forall(!_.isAfter(date))
    val notYetEnded = validTo.flatMap(_.lowerBound) match
      case Some(end) => end.isAfter(date)
      case None      => endReason.isEmpty
    startedBy && notYetEnded

  /** Matches the subject/property/value a boundary edit refers to (SPEC §3.6 "stopped …"). */
  def matches(subject: Iri, property: Iri, value: Option[Node]): Boolean =
    statedSubject == subject && statedProperty == property && value.forall(_ == statedValue)

  def describe: String =
    val range = (validFrom.map(_.render), validTo.map(_.render)) match
      case (Some(f), Some(t)) => s"$f → $t"
      case (Some(f), None)    => s"open since $f"
      case (None, Some(t))    => s"until $t"
      case (None, None)       => "ongoing, start unknown"
    s"${triple.render} [$range]"

object Fluent:
  given Order[Fluent] = Order.by(_.id)
  given Ordering[Fluent] = Order[Fluent].toOrdering
