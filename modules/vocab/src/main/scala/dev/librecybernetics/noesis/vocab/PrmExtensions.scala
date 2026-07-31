package dev.librecybernetics.noesis.vocab

import java.time.LocalDate

import dev.librecybernetics.noesis.core.module.*
import dev.librecybernetics.noesis.logic.*

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
      options: ExportOptions
  ): Either[List[String], String] =
    if !context.closure.complete then incompleteExport(context)
    else if !context.state.entities.contains(entity) then
      Left(List(s"no such contact: ${entity.display}"))
    else
      disclosedCard(context, entity).map: card =>
        val minimized = if options.includeContactData then card else card.copy(methods = Nil)
        VCard.write(minimized, context.naming.label)

object FoafExporter extends DocumentExporter:
  val formats = Set("foaf", "rdf")

  def render(
      context: ExportContext,
      entity: Iri,
      options: ExportOptions
  ): Either[List[String], String] =
    if !context.closure.complete then incompleteExport(context)
    else if !context.state.entities.contains(entity) then
      Left(List(s"no such contact: ${entity.display}"))
    else
      val people = context.closure.view
        .objectBySubjectProperty
        .getOrElse((entity, RelationshipsModule.knows), Nil)
        .map(_._1)
        .filter(person =>
          context.closure.contains(Axiom.ClassAssertion(person, RelationshipsModule.Person))
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
  Right(card)

private def incompleteExport(context: ExportContext): Left[List[String], Nothing] =
  Left(
    List(
      s"reasoning incomplete (${context.closure.incompleteReasons.toList.sorted.mkString(", ")}); " +
        "refusing to produce a possibly partial contact export"
    )
  )

object PrmAgenda extends AgendaProducer:
  def entries(state: dev.librecybernetics.noesis.core.projection.KbState, today: LocalDate): List[AgendaEntry] =
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

  /** The day an agenda entry lands on: a located date is its own answer, and a recurring day is the
    * next time it comes round. `None` only when the value is neither.
    */
  private def agendaDate(value: Literal, today: LocalDate): Option[LocalDate] =
    value.asDate
      .map(_.lowerBound)
      .orElse(value.asAnniversary.map(Prm.nextOccurrence(_, today)))
