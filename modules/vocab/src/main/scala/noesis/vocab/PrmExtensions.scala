package noesis.vocab

import java.time.LocalDate

import noesis.core.module.*
import noesis.logic.*

object VCardImporter extends DocumentImporter:
  val formats = Set("vcard", "vcf")

  def parse(document: String): Either[List[String], List[ImportBatch]] =
    VCard.importIntents(document).map(_.map((record, intents) => ImportBatch(record, intents)))

object FoafImporter extends DocumentImporter:
  val formats = Set("foaf", "rdf")

  def parse(document: String): Either[List[String], List[ImportBatch]] =
    Foaf.importIntents(document).map(_.map((record, intents) => ImportBatch(record, intents)))

object VCardExporter extends DocumentExporter:
  val formats = Set("vcard", "vcf")

  def render(
      context: ExportContext,
      entity: Iri,
      _options: ExportOptions
  ): Either[List[String], String] =
    if !context.state.entities.contains(entity) then Left(List(s"no such contact: ${entity.display}"))
    else
      disclosedCard(context, entity).map(card => VCard.write(card, context.naming.label))

object FoafExporter extends DocumentExporter:
  val formats = Set("foaf", "rdf")

  def render(
      context: ExportContext,
      entity: Iri,
      options: ExportOptions
  ): Either[List[String], String] =
    if !context.state.entities.contains(entity) then Left(List(s"no such contact: ${entity.display}"))
    else
      val people = context.closure.view
        .objectBySubjectProperty
        .getOrElse((entity, RelationshipsModule.knows), Nil)
        .map(_._1)
        .filter(person =>
          context.closure.contains(Axiom.ClassAssertion(person, RelationshipsModule.Person)) &&
            context.permits(Axiom.ObjectAssertion(entity, RelationshipsModule.knows, person))
        )
      disclosedCard(context, entity).map: card =>
        Foaf.write(
          card,
          people,
          FoafExportOptions(options.includeContactData, options.includeSocialGraph)
        )

private def disclosedCard(
    context: ExportContext,
    entity: Iri
): Either[List[String], ContactCard] =
  val card = Prm.contactCard(context.state, entity)
  val disclosedMethods = card.methods.filter: method =>
    context.permits(
      Axiom.DataAssertion(
        method.id,
        RelationshipsModule.contactValue,
        Literal.string(method.value)
      )
    )
  val disclosedEmployments = card.employments.filter: employment =>
    context.permits(
      Axiom.ObjectAssertion(
        employment.id,
        RelationshipsModule.employer,
        employment.organization
      )
    )
  val disclosedBirthday = card.birthday.filter: date =>
    context.permits(
      Axiom.DataAssertion(entity, RelationshipsModule.birthday, Literal.date(date))
    )
  Right(
    card.copy(
      birthday = disclosedBirthday,
      methods = disclosedMethods,
      employments = disclosedEmployments
    )
  )

object PrmAgenda extends AgendaProducer:
  def entries(state: noesis.core.projection.KbState, today: LocalDate): List[AgendaEntry] =
    val followUps = Prm.dueFollowUps(state, today).map: entry =>
      AgendaEntry(
        entry.plan,
        entry.contact,
        entry.due,
        "follow-up",
        "follow up",
        entry.overdue
      )
    val reminders = Prm.reminders(state).flatMap: reminder =>
      agendaDate(reminder.due, today).map: date =>
        AgendaEntry(
          reminder.reminder,
          reminder.contact,
          date,
          "reminder",
          reminder.occasion,
          !date.isAfter(today)
        )
    val occasions = Prm.occasions(state, today).map: occasion =>
      AgendaEntry(
        occasion.source,
        occasion.contact,
        occasion.due,
        "occasion",
        occasion.occasion,
        occasion.due == today
      )
    (followUps ++ reminders ++ occasions)
      .sortBy(entry => (entry.due.toEpochDay, entry.subject.value))

  private def agendaDate(value: PartialDate, today: LocalDate): Option[LocalDate] =
    value.lowerBound.orElse:
      (value.month, value.day) match
        case (Some(month), Some(day)) =>
          val thisYear = LocalDate.of(today.getYear, month, day)
          Some(if thisYear.isBefore(today) then thisYear.plusYears(1) else thisYear)
        case _ => None
