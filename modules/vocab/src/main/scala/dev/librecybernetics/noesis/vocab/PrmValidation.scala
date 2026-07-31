package dev.librecybernetics.noesis.vocab

import dev.librecybernetics.noesis.core.kb.StateValidator
import dev.librecybernetics.noesis.core.projection.KbState
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.reasoner.{Closure, ClosureView}

/** Record-shape validation for the PRM vocabulary (SPEC §3.5.4, §7).
  *
  * These checks run over the pre-commit closure, so inferred subclass membership counts and a
  * multi-assertion capture is judged as one atomic record.
  */
object PrmValidation extends StateValidator:
  val name = "crm records"

  def validate(_state: KbState, closure: Closure): List[String] =
    val view = closure.view
    validateNames(view) ++
      validateContactMethods(view) ++
      validateEmployments(view) ++
      validateRelationships(view) ++
      validateInteractions(view) ++
      validateFollowUps(view) ++
      validateReminders(view) ++
      validateNotes(view) ++
      validatePreferences(view) ++
      validateIdentifiers(view) ++
      validateCompanionAnimals(view) ++
      validateGifts(view)

  private def validateNames(view: ClosureView): List[String] =
    instances(view, RelationshipsModule.Name).flatMap: record =>
      exactlyOneData(view, record, RelationshipsModule.nameValue, "name value")

  private def validateContactMethods(view: ClosureView): List[String] =
    instances(view, RelationshipsModule.ContactMethod).flatMap: record =>
      exactlyOneObject(view, record, RelationshipsModule.contactFor, "contact owner") ++
        exactlyOneData(view, record, RelationshipsModule.contactKind, "contact kind") ++
        exactlyOneData(view, record, RelationshipsModule.contactValue, "current contact value") ++
        exactlyOneData(view, record, RelationshipsModule.contactStatus, "contact status") ++
        enumValue(
          view,
          record,
          RelationshipsModule.contactStatus,
          Set("active", "retired", "invalid"),
          "contact status"
        )

  private def validateEmployments(view: ClosureView): List[String] =
    instances(view, RelationshipsModule.Employment).flatMap: record =>
      exactlyOneObject(view, record, RelationshipsModule.employmentFor, "employee") ++
        exactlyOneObject(view, record, RelationshipsModule.employer, "employer") ++
        exactlyOneData(view, record, RelationshipsModule.employmentStatus, "employment status") ++
        enumValue(
          view,
          record,
          RelationshipsModule.employmentStatus,
          Set("active", "ended"),
          "employment status"
        )

  private def validateRelationships(view: ClosureView): List[String] =
    instances(view, RelationshipsModule.Relationship).flatMap: record =>
      val participants =
        view.objectBySubjectProperty
          .getOrElse((record, RelationshipsModule.relationshipParticipant), Nil)
          .map(_._1)
          .distinct
      Option
        .when(participants.size < 2)(
          s"${record.display} needs at least two distinct relationship participants"
        )
        .toList ++
        exactlyOneData(view, record, RelationshipsModule.relationshipKind, "relationship kind") ++
        exactlyOneData(
          view,
          record,
          RelationshipsModule.relationshipStatus,
          "relationship status"
        ) ++
        enumValue(
          view,
          record,
          RelationshipsModule.relationshipStatus,
          Set("active", "ended"),
          "relationship status"
        )

  private def validateInteractions(view: ClosureView): List[String] =
    instances(view, RelationshipsModule.Interaction).flatMap: record =>
      val participants =
        view.objectBySubjectProperty
          .getOrElse((record, RelationshipsModule.participant), Nil)
          .map(_._1)
          .distinct
      Option.when(participants.isEmpty)(s"${record.display} needs an interaction participant").toList ++
        exactlyOneData(view, record, RelationshipsModule.occurredAt, "interaction date") ++
        exactlyOneData(view, record, RelationshipsModule.interactionChannel, "interaction channel")

  private def validateFollowUps(view: ClosureView): List[String] =
    instances(view, RelationshipsModule.FollowUpPlan).flatMap: record =>
      val cadence =
        view.dataBySubjectProperty
          .getOrElse((record, RelationshipsModule.cadenceDays), Nil)
          .map(_._1)
      exactlyOneObject(view, record, RelationshipsModule.followUpWith, "follow-up contact") ++
        exactlyOneData(view, record, RelationshipsModule.cadenceDays, "follow-up cadence") ++
        exactlyOneData(view, record, RelationshipsModule.paused, "follow-up pause state") ++
        cadence.flatMap: value =>
          Option.when(value.asDecimal.forall(_ <= 0))(
            s"${record.display} has a non-positive follow-up cadence"
          )

  private def validateReminders(view: ClosureView): List[String] =
    instances(view, RelationshipsModule.Reminder).flatMap: record =>
      exactlyOneObject(view, record, RelationshipsModule.reminderAbout, "reminder contact") ++
        exactlyOneData(view, record, RelationshipsModule.due, "reminder due date") ++
        exactlyOneData(view, record, RelationshipsModule.occasion, "reminder occasion")

  private def validateNotes(view: ClosureView): List[String] =
    instances(view, RelationshipsModule.ContactNote).flatMap: record =>
      exactlyOneObject(view, record, RelationshipsModule.about, "note subject") ++
        exactlyOneData(view, record, RelationshipsModule.noteBody, "note body") ++
        exactlyOneData(view, record, RelationshipsModule.noteKind, "note kind")

  private def validatePreferences(view: ClosureView): List[String] =
    val allowed = Set("likes", "dislikes", "allergy", "topic-to-avoid")
    instances(view, RelationshipsModule.Preference).flatMap: record =>
      exactlyOneObject(view, record, RelationshipsModule.about, "preference subject") ++
        exactlyOneData(view, record, RelationshipsModule.preferencePolarity, "preference polarity") ++
        exactlyOneData(view, record, RelationshipsModule.preferenceText, "preference text") ++
        enumValue(
          view,
          record,
          RelationshipsModule.preferencePolarity,
          allowed,
          "preference polarity"
        )

  private def validateIdentifiers(view: ClosureView): List[String] =
    instances(view, RelationshipsModule.ExternalIdentifier).flatMap: record =>
      exactlyOneObject(view, record, RelationshipsModule.identifierFor, "identifier owner") ++
        exactlyOneObject(view, record, RelationshipsModule.identifierScheme, "identifier scheme") ++
        exactlyOneData(view, record, RelationshipsModule.identifierValue, "identifier value")

  private def validateCompanionAnimals(view: ClosureView): List[String] =
    instances(view, RelationshipsModule.CompanionAnimal).flatMap: record =>
      atLeastOneObject(view, record, RelationshipsModule.companionOf, "associated contact") ++
        exactlyOneObject(view, record, RelationshipsModule.hasName, "current name")

  private def validateGifts(view: ClosureView): List[String] =
    val allowed = Set("idea", "planned", "given", "received")
    instances(view, RelationshipsModule.Gift).flatMap: record =>
      val people =
        view.objectBySubjectProperty
          .getOrElse((record, RelationshipsModule.giftTo), Nil)
          .map(_._1) ++
          view.objectBySubjectProperty
            .getOrElse((record, RelationshipsModule.giftFrom), Nil)
            .map(_._1)
      Option.when(people.distinct.isEmpty)(s"${record.display} needs a gift recipient or giver").toList ++
        exactlyOneData(view, record, RelationshipsModule.giftDescription, "gift description") ++
        exactlyOneData(view, record, RelationshipsModule.giftStatus, "gift status") ++
        enumValue(
          view,
          record,
          RelationshipsModule.giftStatus,
          allowed,
          "gift status"
        )

  private def instances(view: ClosureView, cls: Iri): List[Iri] =
    view.instancesOf.getOrElse(cls, Nil).map(_._1).distinct.sorted

  private def exactlyOneObject(
      view: ClosureView,
      subject: Iri,
      property: Iri,
      label: String
  ): List[String] =
    val count =
      view.objectBySubjectProperty.getOrElse((subject, property), Nil).map(_._1).distinct.size
    Option
      .when(count != 1)(s"${subject.display} needs exactly one $label; found $count")
      .toList

  private def exactlyOneData(
      view: ClosureView,
      subject: Iri,
      property: Iri,
      label: String
  ): List[String] =
    val count =
      view.dataBySubjectProperty.getOrElse((subject, property), Nil).map(_._1).distinct.size
    Option
      .when(count != 1)(s"${subject.display} needs exactly one $label; found $count")
      .toList

  private def atLeastOneObject(
      view: ClosureView,
      subject: Iri,
      property: Iri,
      label: String
  ): List[String] =
    val count =
      view.objectBySubjectProperty.getOrElse((subject, property), Nil).map(_._1).distinct.size
    Option.when(count < 1)(s"${subject.display} needs at least one $label").toList

  private def enumValue(
      view: ClosureView,
      subject: Iri,
      property: Iri,
      allowed: Set[String],
      label: String
  ): List[String] =
    view.dataBySubjectProperty
      .getOrElse((subject, property), Nil)
      .map(_._1.text)
      .distinct
      .flatMap(value =>
        Option.when(!allowed.contains(value))(
          s"${subject.display} has unsupported $label '$value'"
        )
      )
