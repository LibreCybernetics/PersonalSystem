package noesis.vocab

import java.time.LocalDate
import java.util.Locale

import cats.data.NonEmptyList
import noesis.core.projection.{KbState, Projections}
import noesis.core.verbalize.Naming
import noesis.logic.*

final case class PostalAddressView(
    formatted: String,
    street: Option[String],
    extended: Option[String],
    locality: Option[String],
    region: Option[String],
    postalCode: Option[String],
    countryCode: Option[String]
)

final case class ContactMethodView(
    id: Iri,
    kind: String,
    value: String,
    label: Option[String],
    purpose: Option[String],
    status: String,
    rank: Option[Int],
    address: Option[PostalAddressView]
):
  def normalizedValue: String =
    kind match
      case "email" => Prm.normalizeEmail(value)
      case "phone" | "sms" => Prm.normalizePhone(value)
      case _ => value.trim

final case class EmploymentView(
    id: Iri,
    organization: Iri,
    title: Option[String],
    department: Option[String],
    location: Option[String]
)

final case class InteractionView(
    id: Iri,
    participants: List[Iri],
    occurred: PartialDate,
    channel: String,
    kind: Option[String],
    direction: Option[String],
    summary: Option[String]
)

final case class StructuredNameView(
    family: Option[String],
    givenName: Option[String],
    additional: Option[String],
    prefix: Option[String],
    suffix: Option[String]
)

enum ContactCompleteness:
  case NameOnly, Reachable, Complete

final case class ContactCard(
    contact: Iri,
    displayName: String,
    structuredName: Option[StructuredNameView],
    organization: Boolean,
    birthday: Option[Literal],
    methods: List[ContactMethodView],
    employments: List[EmploymentView],
    recentInteractions: List[InteractionView],
    completeness: ContactCompleteness
)

final case class FollowUpDue(
    plan: Iri,
    contact: Iri,
    lastInteraction: Option[LocalDate],
    due: LocalDate,
    overdue: Boolean
)

final case class ReminderDue(
    reminder: Iri,
    contact: Iri,
    occasion: String,
    due: Literal
)

final case class OccasionDue(
    source: Iri,
    contact: Iri,
    occasion: String,
    due: LocalDate
)

final case class DuplicateCandidate(contact: Iri, reasons: List[String])

/** Read-only PRM projections over journal state (SPEC §7.4).
  *
  * Current cards, normalized comparison keys, timelines and agenda entries are disposable values;
  * none is written back to the journal.
  */
object Prm:
  def contactCard(state: KbState, contact: Iri, interactionLimit: Int = 10): ContactCard =
    val triples = Projections.current(state).triples
    val naming = Naming.from(
      state,
      Naming.defaultNamingProperties,
      List(Naming.Scheme(RelationshipsModule.hasName, RelationshipsModule.nameValue))
    )
    val methods = contactMethods(triples, contact)
    val employments = currentEmployments(triples, contact)
    val interactions = interactionsFor(triples, contact).take(interactionLimit.max(0))
    val hasDetail =
      birthday(triples, contact).nonEmpty || employments.nonEmpty || interactions.nonEmpty
    val completeness =
      if methods.nonEmpty && hasDetail then ContactCompleteness.Complete
      else if methods.nonEmpty then ContactCompleteness.Reachable
      else ContactCompleteness.NameOnly
    ContactCard(
      contact,
      naming.label(contact),
      structuredName(triples, contact),
      triples.contains(
        Triple(contact, Vocab.rdfType, Node.Ref(RelationshipsModule.Organization))
      ),
      birthday(triples, contact),
      methods,
      employments,
      interactions,
      completeness
    )

  def contactMethods(triples: Set[Triple], contact: Iri): List[ContactMethodView] =
    objectSubjects(triples, RelationshipsModule.contactFor, contact)
      .flatMap: method =>
        val status = dataOne(triples, method, RelationshipsModule.contactStatus).getOrElse("active")
        dataOne(triples, method, RelationshipsModule.contactValue).map: value =>
          val kind = dataOne(triples, method, RelationshipsModule.contactKind).getOrElse("other")
          ContactMethodView(
            method,
            kind,
            value,
            dataOne(triples, method, RelationshipsModule.contactLabel),
            dataOne(triples, method, RelationshipsModule.contactPurpose),
            status,
            dataLiteral(triples, method, RelationshipsModule.preferenceRank)
              .flatMap(_.asDecimal)
              .map(_.toInt),
            Option.when(kind == "postal")(
              PostalAddressView(
                dataOne(triples, method, RelationshipsModule.formattedAddress).getOrElse(value),
                dataOne(triples, method, RelationshipsModule.streetAddress),
                dataOne(triples, method, RelationshipsModule.extendedAddress),
                dataOne(triples, method, RelationshipsModule.locality),
                dataOne(triples, method, RelationshipsModule.region),
                dataOne(triples, method, RelationshipsModule.postalCode),
                dataOne(triples, method, RelationshipsModule.countryCode)
              )
            )
          )
      .filter(_.status == "active")
      .sortBy(method => (method.rank.getOrElse(Int.MaxValue), method.kind, method.value))

  def currentEmployments(triples: Set[Triple], person: Iri): List[EmploymentView] =
    objectSubjects(triples, RelationshipsModule.employmentFor, person)
      .filter(record =>
        dataOne(triples, record, RelationshipsModule.employmentStatus).contains("active")
      )
      .flatMap: record =>
        objectOne(triples, record, RelationshipsModule.employer).map: organization =>
          EmploymentView(
            record,
            organization,
            dataOne(triples, record, RelationshipsModule.jobTitle),
            dataOne(triples, record, RelationshipsModule.department),
            dataOne(triples, record, RelationshipsModule.workLocation)
          )
      .sortBy(_.id.value)

  def interactionsFor(triples: Set[Triple], contact: Iri): List[InteractionView] =
    objectSubjects(triples, RelationshipsModule.participant, contact)
      .flatMap: interaction =>
        dataLiteral(triples, interaction, RelationshipsModule.occurredAt)
          .flatMap(_.asDate)
          .map: occurred =>
            InteractionView(
              interaction,
              objectValues(triples, interaction, RelationshipsModule.participant).sorted,
              occurred,
              dataOne(triples, interaction, RelationshipsModule.interactionChannel)
                .getOrElse("other"),
              dataOne(triples, interaction, RelationshipsModule.interactionKind),
              dataOne(triples, interaction, RelationshipsModule.interactionDirection),
              dataOne(triples, interaction, RelationshipsModule.interactionSummary)
            )
      .sortBy(_.occurred.lowerBound.toEpochDay)
      .reverse

  def dueFollowUps(state: KbState, today: LocalDate): List[FollowUpDue] =
    val triples = Projections.current(state).triples
    instances(triples, RelationshipsModule.FollowUpPlan)
      .filterNot(plan =>
        dataLiteral(triples, plan, RelationshipsModule.paused).flatMap(_.asBoolean).contains(true)
      )
      .flatMap: plan =>
        for
          contact <- objectOne(triples, plan, RelationshipsModule.followUpWith)
          cadence <- dataLiteral(triples, plan, RelationshipsModule.cadenceDays)
            .flatMap(_.asDecimal)
            .map(_.toInt)
        yield
          val channel = dataOne(triples, plan, RelationshipsModule.qualifyingChannel)
          val latest = interactionsFor(triples, contact)
            .filter(interaction => channel.forall(_ == interaction.channel))
            .map(_.occurred.lowerBound)
            .maxOption
          val due = latest.fold(today)(_.plusDays(cadence.toLong))
          FollowUpDue(plan, contact, latest, due, !due.isAfter(today))
      .sortBy(entry => (entry.due.toEpochDay, entry.contact.value))

  def reminders(state: KbState): List[ReminderDue] =
    val triples = Projections.current(state).triples
    instances(triples, RelationshipsModule.Reminder).flatMap: reminder =>
      for
        contact <- objectOne(triples, reminder, RelationshipsModule.reminderAbout)
        due <- dataLiteral(triples, reminder, RelationshipsModule.due)
        occasion <- dataOne(triples, reminder, RelationshipsModule.occasion)
      yield ReminderDue(reminder, contact, occasion, due)

  def remindersDue(state: KbState, today: LocalDate): List[ReminderDue] =
    reminders(state).filter(reminder => fallsBy(reminder.due, today))

  /** Has this due value arrived by `today`?
    *
    * Two readings, because a reminder's due value is either a date or a recurrence: a located date
    * has arrived once it is not in the future, and a recurring day arrives on the day it names, in
    * whatever year today is. The second reading is what the yearless case meant before the two were
    * separate types.
    */
  private[vocab] def fallsBy(due: Literal, today: LocalDate): Boolean =
    due.asDate.exists(!_.lowerBound.isAfter(today)) ||
      due.asAnniversary.exists(_.atYear(today.getYear) == today)

  def occasions(state: KbState, today: LocalDate): List[OccasionDue] =
    val triples = Projections.current(state).triples
    val birthdays = instances(triples, RelationshipsModule.Person).flatMap: person =>
      dataLiteral(triples, person, RelationshipsModule.birthday)
        .flatMap(_.asAnniversary)
        .map(nextOccurrence(_, today))
        .map(date =>
          OccasionDue(
            PrmIds.child(person, "occasion", "birthday"),
            person,
            "birthday",
            date
          )
        )
    val anniversaries = instances(triples, RelationshipsModule.Relationship)
      .filter(relationship =>
        dataOne(triples, relationship, RelationshipsModule.relationshipStatus).contains("active")
      )
      .flatMap: relationship =>
        dataLiteral(triples, relationship, RelationshipsModule.anniversary)
          .flatMap(_.asAnniversary)
          .map(nextOccurrence(_, today))
          .toList
          .flatMap: date =>
            objectValues(
              triples,
              relationship,
              RelationshipsModule.relationshipParticipant
            ).map(contact =>
              OccasionDue(relationship, contact, "relationship anniversary", date)
            )
    (birthdays ++ anniversaries).sortBy(entry => (entry.due.toEpochDay, entry.contact.value))

  def duplicateCandidates(
      state: KbState,
      incomingName: Option[String],
      incomingMethods: List[(String, String)]
  ): List[DuplicateCandidate] =
    val triples = Projections.current(state).triples
    val contacts =
      (instances(triples, RelationshipsModule.Person) ++
        instances(triples, RelationshipsModule.Organization)).distinct
    val naming = Naming.from(
      state,
      Naming.defaultNamingProperties,
      List(Naming.Scheme(RelationshipsModule.hasName, RelationshipsModule.nameValue))
    )
    contacts.flatMap: contact =>
      val nameReasons = incomingName.toList.collect:
        case name if name.equalsIgnoreCase(naming.label(contact)) => s"same name: $name"
      val existing = contactMethods(triples, contact).map(method =>
        method.kind -> method.normalizedValue
      ).toSet
      val methodReasons = incomingMethods.distinct.collect:
        case (kind, value) if existing.contains(kind -> normalize(kind, value)) =>
          s"same normalized $kind: $value"
      val reasons = nameReasons ++ methodReasons
      Option.when(reasons.nonEmpty)(DuplicateCandidate(contact, reasons))
    .sortBy(_.contact.value)

  def importEvidence(
      contact: Iri,
      intents: NonEmptyList[noesis.core.capture.Intent]
  ): (Option[String], List[(String, String)]) =
    val list = intents.toList
    val nameRecords = list.collect:
      case noesis.core.capture.Intent.OpenState(
            `contact`,
            property,
            Node.Ref(name),
            _,
            _
          ) if property == RelationshipsModule.hasName =>
        name
    val name = list.collectFirst:
      case noesis.core.capture.Intent.Assert(
            Axiom.DataAssertion(subject, property, value),
            _
          ) if nameRecords.contains(subject) && property == RelationshipsModule.nameValue =>
        value.text
    val owners = list.collect:
      case noesis.core.capture.Intent.Assert(
            Axiom.ObjectAssertion(method, property, `contact`),
            _
          ) if property == RelationshipsModule.contactFor =>
        method
    val kinds = list.collect:
      case noesis.core.capture.Intent.Assert(
            Axiom.DataAssertion(method, property, value),
            _
          ) if owners.contains(method) && property == RelationshipsModule.contactKind =>
        method -> value.text
    .toMap
    val methods = list.collect:
      case noesis.core.capture.Intent.OpenState(method, property, Node.Lit(value), _, _)
          if owners.contains(method) && property == RelationshipsModule.contactValue =>
        kinds.getOrElse(method, "other") -> value.text
    (name, methods)

  /** A comparison key only; the entered address remains the stored truth. */
  def normalizeEmail(value: String): String =
    value.trim.split("@", 2).toList match
      case local :: domain :: Nil => s"$local@${domain.toLowerCase(Locale.ROOT)}"
      case _                      => value.trim

  /** A conservative phone comparison key, not a replacement stored value. */
  def normalizePhone(value: String): String =
    val trimmed = value.trim
    val digits = trimmed.filter(_.isDigit)
    if trimmed.startsWith("+") then s"+$digits" else digits

  private[vocab] def normalize(kind: String, value: String): String =
    kind match
      case "email" => normalizeEmail(value)
      case "phone" | "sms" => normalizePhone(value)
      case _ => value.trim

  /** The next time a recurring day comes round, today included.
    *
    * Takes a `MonthDay` rather than a date because that is what an occasion *is*: the recurrence,
    * not the year it started in. A birthday reaches this either way — `Literal.asAnniversary`
    * answers for a `gMonthDay` and for a located date alike — and the result is total, where the
    * old signature had to return `Option` for values that were never occasions at all.
    *
    * 29 February resolves to 28 February in a common year, which is `MonthDay.atYear`'s behaviour
    * and the conventional reading of the occasion.
    */
  private[vocab] def nextOccurrence(value: java.time.MonthDay, today: LocalDate): LocalDate =
    val thisYear = value.atYear(today.getYear)
    if thisYear.isBefore(today) then value.atYear(today.getYear + 1) else thisYear

  /** The birthday as stored, rather than as a located date.
    *
    * A birthday is a located date when the year is known and a recurring day when it is not, and
    * both are ordinary answers. Reading it as a `PartialDate` would silently drop every yearless
    * one — which is the common case — so the card carries the literal and each consumer asks it for
    * what it needs.
    */
  private def birthday(triples: Set[Triple], contact: Iri): Option[Literal] =
    dataLiteral(triples, contact, RelationshipsModule.birthday)

  private[vocab] def structuredName(
      triples: Set[Triple],
      contact: Iri
  ): Option[StructuredNameView] =
    objectOne(triples, contact, RelationshipsModule.hasName).flatMap: name =>
      val result = StructuredNameView(
        dataOne(triples, name, RelationshipsModule.familyName),
        dataOne(triples, name, RelationshipsModule.givenName),
        dataOne(triples, name, RelationshipsModule.additionalName),
        dataOne(triples, name, RelationshipsModule.honorificPrefix),
        dataOne(triples, name, RelationshipsModule.honorificSuffix)
      )
      Option.when(
        List(
          result.family,
          result.givenName,
          result.additional,
          result.prefix,
          result.suffix
        ).exists(
          _.nonEmpty
        )
      )(result)

  private[vocab] def instances(triples: Set[Triple], cls: Iri): List[Iri] =
    triples.collect:
      case Triple(subject, property, Node.Ref(found))
          if property == Vocab.rdfType && found == cls =>
        subject
    .toList
    .distinct
    .sorted

  private[vocab] def objectSubjects(triples: Set[Triple], property: Iri, obj: Iri): List[Iri] =
    triples.collect:
      case Triple(subject, found, Node.Ref(value)) if found == property && value == obj => subject
    .toList
    .distinct
    .sorted

  private[vocab] def objectValues(triples: Set[Triple], subject: Iri, property: Iri): List[Iri] =
    triples.collect:
      case Triple(found, p, Node.Ref(value)) if found == subject && p == property => value
    .toList
    .distinct

  private def objectOne(triples: Set[Triple], subject: Iri, property: Iri): Option[Iri] =
    objectValues(triples, subject, property).sortBy(_.value).headOption

  private def dataLiteral(triples: Set[Triple], subject: Iri, property: Iri): Option[Literal] =
    triples.collect:
      case Triple(found, p, Node.Lit(value)) if found == subject && p == property => value
    .toList
    .sortBy(value => (value.lexical, value.datatype.value, value.language))
    .headOption

  private def dataOne(triples: Set[Triple], subject: Iri, property: Iri): Option[String] =
    dataLiteral(triples, subject, property).map(_.text)
