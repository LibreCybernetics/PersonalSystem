package noesis.vocab

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import cats.data.NonEmptyList
import noesis.core.capture.Intent
import noesis.logic.*

enum ContactKind(val value: String):
  case Email extends ContactKind("email")
  case Phone extends ContactKind("phone")
  case Sms extends ContactKind("sms")
  case WhatsApp extends ContactKind("whatsapp")
  case Signal extends ContactKind("signal")
  case Telegram extends ContactKind("telegram")
  case Matrix extends ContactKind("matrix")
  case Website extends ContactKind("website")
  case Social extends ContactKind("social")
  case Other extends ContactKind("other")

object ContactKind:
  def parse(raw: String): Either[String, ContactKind] =
    values.find(_.value == raw.toLowerCase(java.util.Locale.ROOT))
      .toRight(s"unknown contact kind: $raw")

enum ContactEntityKind:
  case Person, Organization

final case class ContactInput(
    id: Iri,
    displayName: String,
    kind: ContactEntityKind = ContactEntityKind.Person,
    nameKind: String = "chosen",
    familyName: Option[String] = None,
    givenName: Option[String] = None,
    additionalName: Option[String] = None,
    honorificPrefix: Option[String] = None,
    honorificSuffix: Option[String] = None
)

final case class ContactMethodInput(
    id: Iri,
    contact: Iri,
    kind: ContactKind,
    value: String,
    label: Option[String] = None,
    purpose: Option[String] = None,
    rank: Option[Int] = None
)

final case class PostalAddressInput(
    id: Iri,
    contact: Iri,
    formatted: String,
    street: Option[String] = None,
    extended: Option[String] = None,
    locality: Option[String] = None,
    region: Option[String] = None,
    postalCode: Option[String] = None,
    countryCode: Option[String] = None,
    label: Option[String] = None,
    purpose: Option[String] = None
)

final case class EmploymentInput(
    id: Iri,
    person: Iri,
    organization: Iri,
    title: Option[String] = None,
    department: Option[String] = None,
    location: Option[String] = None
)

final case class InteractionInput(
    id: Iri,
    participants: List[Iri],
    occurred: PartialDate,
    channel: String,
    kind: Option[String] = None,
    direction: Option[String] = None,
    summary: Option[String] = None,
    sensitivity: Sensitivity = Sensitivity.Personal
)

final case class RelationshipInput(
    id: Iri,
    participants: List[Iri],
    kind: String,
    description: Option[String] = None,
    anniversary: Option[Literal] = None
)

final case class NoteInput(
    id: Iri,
    contact: Iri,
    body: String,
    kind: String = "general",
    recordedAt: Option[java.time.Instant] = None,
    sensitivity: Sensitivity = Sensitivity.Personal
)

final case class PreferenceInput(
    id: Iri,
    contact: Iri,
    polarity: String,
    text: String,
    context: Option[String] = None,
    sensitivity: Sensitivity = Sensitivity.Personal
)

final case class FollowUpInput(
    id: Iri,
    contact: Iri,
    cadenceDays: Int,
    channel: Option[String] = None
)

final case class ReminderInput(
    id: Iri,
    contact: Iri,
    due: Literal,
    occasion: String,
    recurrence: Option[String] = None
)

final case class CompanionAnimalInput(id: Iri, name: String, companions: List[Iri])

final case class CircleInput(id: Iri, name: String, members: List[Iri])

final case class GiftInput(
    id: Iri,
    description: String,
    to: Option[Iri] = None,
    from: Option[Iri] = None,
    status: String = "idea",
    occasion: Option[String] = None
)

/** Stable identifiers for records imported or captured more than once.
  *
  * The hash is over owner-controlled source values, not a normalized value persisted as truth.
  * Repeating an import is consequently idempotent while the entered spelling remains untouched.
  */
object PrmIds:
  def record(kind: String, seed: String): Iri =
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(seed.getBytes(StandardCharsets.UTF_8))
      .take(10)
      .map("%02x".format(_))
      .mkString
    Iri(s"noesis:e/prm-$kind-$digest")

  def child(owner: Iri, kind: String, seed: String): Iri =
    record(kind, s"${owner.value}\u0000$seed")

/** Structured PRM input translated into atomic core intents (SPEC §7.3). */
object PrmCapture:
  private val ownerConfirmed = AxiomAnnotations.ownerConfirmed
  private val sensitive =
    ownerConfirmed.copy(sensitivity = Some(Sensitivity.Sensitive), recallUtility = Some(0.0))

  def contact(input: ContactInput): Either[List[String], NonEmptyList[Intent]] =
    val problems = validateRequired("display name", input.displayName)
    if problems.nonEmpty then Left(problems)
    else
      val name = PrmIds.child(input.id, "name", s"${input.nameKind}\u0000${input.displayName}")
      val base = NonEmptyList.of(
        Intent.Assert(
          Axiom.ClassAssertion(
            input.id,
            input.kind match
              case ContactEntityKind.Person       => RelationshipsModule.Person
              case ContactEntityKind.Organization => RelationshipsModule.Organization
          )
        ),
        Intent.Assert(Axiom.ClassAssertion(name, RelationshipsModule.Name)),
        Intent.Assert(
          Axiom.DataAssertion(name, RelationshipsModule.nameValue, Literal.string(input.displayName))
        ),
        Intent.Assert(
          Axiom.DataAssertion(name, RelationshipsModule.nameKind, Literal.string(input.nameKind))
        ),
        Intent.OpenState(input.id, RelationshipsModule.hasName, Node.Ref(name))
      )
      val components = List(
        RelationshipsModule.familyName -> input.familyName,
        RelationshipsModule.givenName -> input.givenName,
        RelationshipsModule.additionalName -> input.additionalName,
        RelationshipsModule.honorificPrefix -> input.honorificPrefix,
        RelationshipsModule.honorificSuffix -> input.honorificSuffix
      ).flatMap: (property, value) =>
        data(name, property, value)
      Right(base.concat(components))

  def method(input: ContactMethodInput): Either[List[String], NonEmptyList[Intent]] =
    val problems = List.concat(
      validateRequired("contact value", input.value),
      input.rank.flatMap(rank => Option.when(rank < 0)("preference rank must not be negative")).toList,
      Option
        .when(input.kind == ContactKind.Email && !looksLikeEmail(input.value))(
          "email address must contain one non-edge @"
        )
        .toList
    )
    if problems.nonEmpty then Left(problems)
    else
      val cls = input.kind match
        case ContactKind.Email => RelationshipsModule.EmailAddress
        case ContactKind.Phone | ContactKind.Sms =>
          RelationshipsModule.TelephoneNumber
        case ContactKind.WhatsApp | ContactKind.Signal | ContactKind.Telegram |
            ContactKind.Matrix | ContactKind.Social =>
          RelationshipsModule.OnlineAccount
        case ContactKind.Website | ContactKind.Other => RelationshipsModule.ContactMethod

      val base = List(
        Intent.Assert(Axiom.ClassAssertion(input.id, cls)),
        Intent.Assert(
          Axiom.ObjectAssertion(input.id, RelationshipsModule.contactFor, input.contact)
        ),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.contactKind,
            Literal.string(input.kind.value)
          )
        ),
        Intent.OpenState(
          input.id,
          RelationshipsModule.contactValue,
          Node.Lit(Literal.string(input.value))
        ),
        Intent.OpenState(
          input.id,
          RelationshipsModule.contactStatus,
          Node.Lit(Literal.string("active"))
        )
      )
      val optional =
        data(input.id, RelationshipsModule.contactLabel, input.label) ++
          data(input.id, RelationshipsModule.contactPurpose, input.purpose) ++
          input.rank.toList.map(rank =>
            Intent.OpenState(
              input.id,
              RelationshipsModule.preferenceRank,
              Node.Lit(Literal.integer(BigInt(rank)))
            )
          )
      Right(NonEmptyList.fromListUnsafe(base ++ optional))

  def alternativeName(
      entity: Iri,
      value: String,
      kind: String = "nickname"
  ): Either[List[String], NonEmptyList[Intent]] =
    val problems = validateRequired("alternative name", value)
    if problems.nonEmpty then Left(problems)
    else
      val name = PrmIds.child(entity, "name", s"$kind\u0000$value")
      Right(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(name, RelationshipsModule.Name)),
          Intent.Assert(
            Axiom.DataAssertion(name, RelationshipsModule.nameValue, Literal.string(value))
          ),
          Intent.Assert(
            Axiom.DataAssertion(name, RelationshipsModule.nameKind, Literal.string(kind))
          ),
          Intent.Assert(
            Axiom.ObjectAssertion(entity, RelationshipsModule.hasAlternativeName, name)
          )
        )
      )

  def address(input: PostalAddressInput): Either[List[String], NonEmptyList[Intent]] =
    val problems = List.concat(
      validateRequired("formatted address", input.formatted),
      input.countryCode
        .flatMap(code =>
          Option.when(!code.matches("[A-Za-z]{2}"))("country code must be two ASCII letters")
        )
        .toList
    )
    if problems.nonEmpty then Left(problems)
    else
      val base = List(
        Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.PostalAddress)),
        Intent.Assert(
          Axiom.ObjectAssertion(input.id, RelationshipsModule.contactFor, input.contact)
        ),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.contactKind,
            Literal.string("postal")
          )
        ),
        Intent.OpenState(
          input.id,
          RelationshipsModule.contactValue,
          Node.Lit(Literal.string(input.formatted)),
          annotations = sensitive
        ),
        Intent.OpenState(
          input.id,
          RelationshipsModule.contactStatus,
          Node.Lit(Literal.string("active"))
        ),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.formattedAddress,
            Literal.string(input.formatted)
          ),
          sensitive
        )
      )
      val fields = List(
        RelationshipsModule.streetAddress -> input.street,
        RelationshipsModule.extendedAddress -> input.extended,
        RelationshipsModule.locality -> input.locality,
        RelationshipsModule.region -> input.region,
        RelationshipsModule.postalCode -> input.postalCode,
        RelationshipsModule.countryCode -> input.countryCode.map(_.toUpperCase(java.util.Locale.ROOT))
      ).flatMap: (property, value) =>
        value.toList.map(text =>
          Intent.Assert(Axiom.DataAssertion(input.id, property, Literal.string(text)), sensitive)
        )
      val optional =
        data(input.id, RelationshipsModule.contactLabel, input.label) ++
          data(input.id, RelationshipsModule.contactPurpose, input.purpose)
      Right(NonEmptyList.fromListUnsafe(base ++ fields ++ optional))

  def employment(input: EmploymentInput): NonEmptyList[Intent] =
    val base = List(
      Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.Employment)),
      Intent.Assert(
        Axiom.ObjectAssertion(input.id, RelationshipsModule.employmentFor, input.person)
      ),
      Intent.Assert(Axiom.ObjectAssertion(input.id, RelationshipsModule.employer, input.organization)),
      Intent.OpenState(
        input.id,
        RelationshipsModule.employmentStatus,
        Node.Lit(Literal.string("active"))
      )
    )
    val optional =
      data(input.id, RelationshipsModule.jobTitle, input.title) ++
        data(input.id, RelationshipsModule.department, input.department) ++
        data(input.id, RelationshipsModule.workLocation, input.location)
    NonEmptyList.fromListUnsafe(base ++ optional)

  def interaction(input: InteractionInput): Either[List[String], NonEmptyList[Intent]] =
    val participants = input.participants.distinct
    val problems =
      Option.when(participants.isEmpty)("an interaction needs at least one participant").toList ++
        validateRequired("interaction channel", input.channel)
    if problems.nonEmpty then Left(problems)
    else
      val base = List(
        Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.Interaction)),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.occurredAt,
            Literal.date(input.occurred)
          )
        ),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.interactionChannel,
            Literal.string(input.channel)
          )
        )
      )
      val people = participants.map(person =>
        Intent.Assert(Axiom.ObjectAssertion(input.id, RelationshipsModule.participant, person))
      )
      val optional =
        data(input.id, RelationshipsModule.interactionKind, input.kind) ++
          data(input.id, RelationshipsModule.interactionDirection, input.direction) ++
          input.summary.toList.map(summary =>
            Intent.Assert(
              Axiom.DataAssertion(
                input.id,
                RelationshipsModule.interactionSummary,
                Literal.string(summary)
              ),
              ownerConfirmed.copy(sensitivity = Some(input.sensitivity))
            )
          )
      Right(NonEmptyList.fromListUnsafe(base ++ people ++ optional))

  def relationship(input: RelationshipInput): Either[List[String], NonEmptyList[Intent]] =
    val participants = input.participants.distinct
    val problems =
      Option.when(participants.size < 2)("a relationship needs at least two participants").toList ++
        validateRequired("relationship kind", input.kind)
    if problems.nonEmpty then Left(problems)
    else
      val base = List(
        Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.Relationship)),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.relationshipKind,
            Literal.string(input.kind)
          )
        ),
        Intent.OpenState(
          input.id,
          RelationshipsModule.relationshipStatus,
          Node.Lit(Literal.string("active"))
        )
      )
      val people = participants.map(person =>
        Intent.Assert(
          Axiom.ObjectAssertion(input.id, RelationshipsModule.relationshipParticipant, person)
        )
      )
      val optional =
        data(input.id, RelationshipsModule.relationshipDescription, input.description) ++
          input.anniversary.toList.map(value =>
            Intent.Assert(
              Axiom.DataAssertion(input.id, RelationshipsModule.anniversary, value)
            )
          )
      Right(NonEmptyList.fromListUnsafe(base ++ people ++ optional))

  def note(input: NoteInput): Either[List[String], NonEmptyList[Intent]] =
    val problems = validateRequired("note body", input.body)
    if problems.nonEmpty then Left(problems)
    else
      Right(NonEmptyList.of(
        Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.ContactNote)),
        Intent.Assert(Axiom.ObjectAssertion(input.id, RelationshipsModule.about, input.contact)),
        Intent.Assert(
          Axiom.DataAssertion(input.id, RelationshipsModule.noteBody, Literal.string(input.body)),
          ownerConfirmed.copy(sensitivity = Some(input.sensitivity))
        ),
        Intent.Assert(
          Axiom.DataAssertion(input.id, RelationshipsModule.noteKind, Literal.string(input.kind))
        )
      ).concat(
        input.recordedAt.toList.map(at =>
          Intent.Assert(
            Axiom.DataAssertion(input.id, RelationshipsModule.recordedAt, Literal.instant(at))
          )
        )
      ))

  def preference(input: PreferenceInput): Either[List[String], NonEmptyList[Intent]] =
    val allowed = Set("likes", "dislikes", "allergy", "topic-to-avoid")
    val problems =
      Option.when(!allowed.contains(input.polarity))(
        s"preference polarity must be one of ${allowed.toList.sorted.mkString(", ")}"
      ).toList ++ validateRequired("preference text", input.text)
    if problems.nonEmpty then Left(problems)
    else
      val annotations =
        ownerConfirmed.copy(
          sensitivity = Some(
            if input.polarity == "allergy" then Sensitivity.Sensitive else input.sensitivity
          )
        )
      val base = NonEmptyList.of(
        Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.Preference)),
        Intent.Assert(Axiom.ObjectAssertion(input.id, RelationshipsModule.about, input.contact)),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.preferencePolarity,
            Literal.string(input.polarity)
          ),
          annotations
        ),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.preferenceText,
            Literal.string(input.text)
          ),
          annotations
        )
      )
      Right(
        base.concat(
          data(input.id, RelationshipsModule.preferenceContext, input.context, annotations)
        )
      )

  def followUp(input: FollowUpInput): Either[List[String], NonEmptyList[Intent]] =
    if input.cadenceDays <= 0 then Left(List("follow-up cadence must be positive"))
    else
      val base = List(
        Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.FollowUpPlan)),
        Intent.Assert(
          Axiom.ObjectAssertion(input.id, RelationshipsModule.followUpWith, input.contact)
        ),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.cadenceDays,
            Literal.integer(BigInt(input.cadenceDays))
          )
        ),
        Intent.OpenState(
          input.id,
          RelationshipsModule.paused,
          Node.Lit(Literal.boolean(false))
        )
      )
      val optional = data(input.id, RelationshipsModule.qualifyingChannel, input.channel)
      Right(NonEmptyList.fromListUnsafe(base ++ optional))

  def reminder(input: ReminderInput): Either[List[String], NonEmptyList[Intent]] =
    val problems = validateRequired("reminder occasion", input.occasion)
    if problems.nonEmpty then Left(problems)
    else
      Right(NonEmptyList.of(
        Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.Reminder)),
        Intent.Assert(
          Axiom.ObjectAssertion(input.id, RelationshipsModule.reminderAbout, input.contact)
        ),
        Intent.Assert(
          Axiom.DataAssertion(input.id, RelationshipsModule.due, input.due)
        ),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.occasion,
            Literal.string(input.occasion)
          )
        )
      ).concat(data(input.id, RelationshipsModule.recurrence, input.recurrence)))

  def companionAnimal(
      input: CompanionAnimalInput
  ): Either[List[String], NonEmptyList[Intent]] =
    val problems =
      validateRequired("companion animal name", input.name) ++
        Option.when(input.companions.distinct.isEmpty)(
          "a companion animal needs at least one associated contact"
        ).toList
    if problems.nonEmpty then Left(problems)
    else
      val name = PrmIds.child(input.id, "name", s"chosen\u0000${input.name}")
      val base = List(
        Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.CompanionAnimal)),
        Intent.Assert(Axiom.ClassAssertion(name, RelationshipsModule.Name)),
        Intent.Assert(
          Axiom.DataAssertion(name, RelationshipsModule.nameValue, Literal.string(input.name))
        ),
        Intent.Assert(
          Axiom.DataAssertion(name, RelationshipsModule.nameKind, Literal.string("chosen"))
        ),
        Intent.OpenState(input.id, RelationshipsModule.hasName, Node.Ref(name))
      )
      val companions = input.companions.distinct.map(contact =>
        Intent.Assert(
          Axiom.ObjectAssertion(input.id, RelationshipsModule.companionOf, contact)
        )
      )
      Right(NonEmptyList.fromListUnsafe(base ++ companions))

  def circle(input: CircleInput): Either[List[String], NonEmptyList[Intent]] =
    val problems = validateRequired("circle name", input.name)
    if problems.nonEmpty then Left(problems)
    else
      val base = List(
        Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.Circle)),
        Intent.Assert(Axiom.DataAssertion(input.id, Vocab.label, Literal.string(input.name)))
      )
      val members = input.members.distinct.map(contact =>
        Intent.Assert(Axiom.ObjectAssertion(input.id, RelationshipsModule.member, contact))
      )
      Right(NonEmptyList.fromListUnsafe(base ++ members))

  def gift(input: GiftInput): Either[List[String], NonEmptyList[Intent]] =
    val allowed = Set("idea", "planned", "given", "received")
    val problems =
      validateRequired("gift description", input.description) ++
        Option.when(input.to.isEmpty && input.from.isEmpty)(
          "a gift needs a recipient or giver"
        ).toList ++
        Option.when(!allowed.contains(input.status))(
          s"gift status must be one of ${allowed.toList.sorted.mkString(", ")}"
        ).toList
    if problems.nonEmpty then Left(problems)
    else
      val base = List(
        Intent.Assert(Axiom.ClassAssertion(input.id, RelationshipsModule.Gift)),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.giftDescription,
            Literal.string(input.description)
          )
        ),
        Intent.Assert(
          Axiom.DataAssertion(
            input.id,
            RelationshipsModule.giftStatus,
            Literal.string(input.status)
          )
        )
      )
      val people =
        input.to.toList.map(contact =>
          Intent.Assert(Axiom.ObjectAssertion(input.id, RelationshipsModule.giftTo, contact))
        ) ++ input.from.toList.map(contact =>
          Intent.Assert(Axiom.ObjectAssertion(input.id, RelationshipsModule.giftFrom, contact))
        )
      val occasion = data(input.id, RelationshipsModule.giftOccasion, input.occasion)
      Right(NonEmptyList.fromListUnsafe(base ++ people ++ occasion))

  def retire(record: Iri): NonEmptyList[Intent] =
    NonEmptyList.one(
      Intent.Supersede(
        record,
        RelationshipsModule.contactStatus,
        Node.Lit(Literal.string("retired"))
      )
    )

  private def data(
      subject: Iri,
      property: Iri,
      value: Option[String],
      annotations: AxiomAnnotations = ownerConfirmed
  ): List[Intent] =
    value.toList.map(text =>
      Intent.Assert(Axiom.DataAssertion(subject, property, Literal.string(text)), annotations)
    )

  private def validateRequired(label: String, value: String): List[String] =
    Option.when(value.trim.isEmpty)(s"$label must not be blank").toList

  private def looksLikeEmail(value: String): Boolean =
    val at = value.indexOf('@')
    at > 0 && at == value.lastIndexOf('@') && at < value.length - 1
