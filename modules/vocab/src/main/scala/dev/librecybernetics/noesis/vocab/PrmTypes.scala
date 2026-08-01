package dev.librecybernetics.noesis.vocab

import dev.librecybernetics.noesis.logic.Iri

/** Text whose required-field check has already succeeded.
  *
  * Capture operators consume this refinement instead of accepting a `String` and remembering to
  * reject blanks themselves. The parser belongs at an input boundary; the representation stays
  * hidden so later code cannot discard the proof accidentally (SPEC §7.4).
  */
opaque type NonBlank = String

object NonBlank:
  def parse(label: String, raw: String): Either[String, NonBlank] =
    Either.cond(raw.trim.nonEmpty, raw, s"$label must not be blank")

  extension (value: NonBlank) def text: String = value

/** A follow-up cadence that is known to advance time (SPEC §7.4).
  *
  * The generic axiom validator still checks persisted literals because raw assertions are another
  * input boundary. Structured capture, however, has no reason to represent zero or negative days.
  */
opaque type PositiveDays = Int

object PositiveDays:
  def from(value: Int): Either[String, PositiveDays] =
    Either.cond(value > 0, value, "follow-up cadence must be positive")

  def parse(raw: String): Either[String, PositiveDays] =
    raw.toIntOption
      .toRight(s"follow-up cadence must be an integer: $raw")
      .flatMap(from)

  extension (value: PositiveDays) def count: Int = value

/** The closed preference vocabulary stored by `crm:preferencePolarity` (SPEC §7.4). */
enum PreferencePolarity(val value: String):
  case Likes extends PreferencePolarity("likes")
  case Dislikes extends PreferencePolarity("dislikes")
  case Allergy extends PreferencePolarity("allergy")
  case TopicToAvoid extends PreferencePolarity("topic-to-avoid")

object PreferencePolarity:
  def parse(raw: String): Either[String, PreferencePolarity] =
    values.find(_.value == raw).toRight:
      s"preference polarity must be one of ${values.map(_.value).sorted.mkString(", ")}"

/** The closed gift lifecycle stored by `crm:giftStatus` (SPEC §7.4). */
enum GiftStatus(val value: String):
  case Idea extends GiftStatus("idea")
  case Planned extends GiftStatus("planned")
  case Given extends GiftStatus("given")
  case Received extends GiftStatus("received")

object GiftStatus:
  def parse(raw: String): Either[String, GiftStatus] =
    values.find(_.value == raw).toRight:
      s"gift status must be one of ${values.map(_.value).sorted.mkString(", ")}"

/** The legal party configurations for a gift record (SPEC §7.4).
  *
  * Two independent options admitted the meaningless `(None, None)` state. This sum type preserves
  * the useful recipient-only, giver-only and two-party cases without a runtime emptiness check.
  */
enum GiftParties:
  case To(recipient: Iri)
  case From(giver: Iri)
  case Between(recipient: Iri, giver: Iri)

  def recipients: List[Iri] = this match
    case To(recipient) => List(recipient)
    case From(_) => Nil
    case Between(recipient, _) => List(recipient)

  def givers: List[Iri] = this match
    case To(_) => Nil
    case From(giver) => List(giver)
    case Between(_, giver) => List(giver)

object GiftParties:
  def parse(to: Option[Iri], from: Option[Iri]): Either[String, GiftParties] =
    (to, from) match
      case (Some(recipient), None) => Right(To(recipient))
      case (None, Some(giver)) => Right(From(giver))
      case (Some(recipient), Some(giver)) => Right(Between(recipient, giver))
      case (None, None) => Left("a gift needs a recipient or giver")
