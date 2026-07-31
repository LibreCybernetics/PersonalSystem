package dev.librecybernetics.noesis.vocab

import dev.librecybernetics.noesis.core.policy.{ModuleDefaults, PolicyBook, TermPolicy}
import dev.librecybernetics.noesis.core.verbalize.{Naming, Templates}
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.reasoner.{ClosureView, ReasonerConfig, Rule}
import dev.librecybernetics.noesis.lms.{ItemPolicy, ItemPolicyBook}

/** Personal Relationship Management vocabulary (SPEC §7).
  *
  * Contact records are ordinary ontology individuals. Reification is intentional: it permits
  * concurrent phone numbers, addresses, accounts, employments and relationships while retaining
  * provenance and history for each value. Nothing here creates a second contact database.
  */
object RelationshipsModule extends Module:
  val prefix = "crm"
  val version = "0.1.0"

  // Classes
  val Agent: Iri = iri("Agent")
  val Person: Iri = iri("Person")
  val Organization: Iri = iri("Organization")
  val NamedEntity: Iri = iri("NamedEntity")
  val Name: Iri = iri("Name")
  val ContactMethod: Iri = iri("ContactMethod")
  val EmailAddress: Iri = iri("EmailAddress")
  val TelephoneNumber: Iri = iri("TelephoneNumber")
  val OnlineAccount: Iri = iri("OnlineAccount")
  val PostalAddress: Iri = iri("PostalAddress")
  val ExternalIdentifier: Iri = iri("ExternalIdentifier")
  val IdentifierScheme: Iri = iri("IdentifierScheme")
  val Employment: Iri = iri("Employment")
  val Interaction: Iri = iri("Interaction")
  val LifeEvent: Iri = iri("LifeEvent")
  val Gift: Iri = iri("Gift")
  val Preference: Iri = iri("Preference")
  val ContactNote: Iri = iri("ContactNote")
  val Circle: Iri = iri("Circle")
  val FollowUpPlan: Iri = iri("FollowUpPlan")
  val Reminder: Iri = iri("Reminder")
  val CompanionAnimal: Iri = iri("CompanionAnimal")
  val Relationship: Iri = iri("Relationship")
  val Parenthood: Iri = iri("Parenthood")

  // Name and identity properties
  val birthday: Iri = iri("birthday")
  val hasName: Iri = iri("hasName")
  val hasAlternativeName: Iri = iri("hasAlternativeName")
  val nameValue: Iri = iri("nameValue")
  val nameKind: Iri = iri("nameKind")
  val familyName: Iri = iri("familyName")
  val givenName: Iri = iri("givenName")
  val additionalName: Iri = iri("additionalName")
  val honorificPrefix: Iri = iri("honorificPrefix")
  val honorificSuffix: Iri = iri("honorificSuffix")
  val nameLanguage: Iri = iri("nameLanguage")
  val nameScript: Iri = iri("nameScript")
  val namePronunciation: Iri = iri("namePronunciation")
  val pronouns: Iri = iri("pronouns")
  val gender: Iri = iri("gender")
  val identifierFor: Iri = iri("identifierFor")
  val identifierScheme: Iri = iri("identifierScheme")
  val identifierValue: Iri = iri("identifierValue")

  // Contact method and address properties
  val contactFor: Iri = iri("contactFor")
  val contactKind: Iri = iri("contactKind")
  val contactValue: Iri = iri("contactValue")
  val contactLabel: Iri = iri("contactLabel")
  val contactPurpose: Iri = iri("contactPurpose")
  val contactStatus: Iri = iri("contactStatus")
  val preferenceRank: Iri = iri("preferenceRank")
  val formattedAddress: Iri = iri("formattedAddress")
  val streetAddress: Iri = iri("streetAddress")
  val extendedAddress: Iri = iri("extendedAddress")
  val locality: Iri = iri("locality")
  val region: Iri = iri("region")
  val postalCode: Iri = iri("postalCode")
  val countryCode: Iri = iri("countryCode")

  // Employment
  val employmentFor: Iri = iri("employmentFor")
  val employer: Iri = iri("employer")
  val jobTitle: Iri = iri("jobTitle")
  val department: Iri = iri("department")
  val workLocation: Iri = iri("workLocation")
  val employmentStatus: Iri = iri("employmentStatus")
  val worksAt: Iri = iri("worksAt")

  // Social graph and reified relationships
  val knows: Iri = iri("knows")
  val friendOf: Iri = iri("friendOf")
  val partnerOf: Iri = iri("partnerOf")
  val spouseOf: Iri = iri("spouseOf")
  val metamourOf: Iri = iri("metamourOf")
  val chosenFamilyOf: Iri = iri("chosenFamilyOf")
  val siblingOf: Iri = iri("siblingOf")
  val parentOf: Iri = iri("parentOf")
  val childOf: Iri = iri("childOf")
  val colleagueOf: Iri = iri("colleagueOf")
  val reportsTo: Iri = iri("reportsTo")
  val mentorOf: Iri = iri("mentorOf")
  val introducedBy: Iri = iri("introducedBy")
  val relationshipParticipant: Iri = iri("relationshipParticipant")
  val relationshipKind: Iri = iri("relationshipKind")
  val relationshipDescription: Iri = iri("relationshipDescription")
  val relationshipStatus: Iri = iri("relationshipStatus")
  val anniversary: Iri = iri("anniversary")

  // Interactions, notes, preferences and attention
  val participant: Iri = iri("participant")
  val occurredAt: Iri = iri("occurredAt")
  val interactionKind: Iri = iri("interactionKind")
  val interactionChannel: Iri = iri("interactionChannel")
  val interactionDirection: Iri = iri("interactionDirection")
  val location: Iri = iri("location")
  val mentionedTopic: Iri = iri("mentionedTopic")
  val interactionSummary: Iri = iri("interactionSummary")
  val about: Iri = iri("about")
  val noteBody: Iri = iri("noteBody")
  val noteKind: Iri = iri("noteKind")
  val recordedAt: Iri = iri("recordedAt")
  val preferencePolarity: Iri = iri("preferencePolarity")
  val preferenceTopic: Iri = iri("preferenceTopic")
  val preferenceText: Iri = iri("preferenceText")
  val preferenceContext: Iri = iri("preferenceContext")
  val followUpWith: Iri = iri("followUpWith")
  val cadenceDays: Iri = iri("cadenceDays")
  val qualifyingChannel: Iri = iri("qualifyingChannel")
  val paused: Iri = iri("paused")
  val reminderAbout: Iri = iri("reminderAbout")
  val due: Iri = iri("due")
  val recurrence: Iri = iri("recurrence")
  val occasion: Iri = iri("occasion")
  val companionOf: Iri = iri("companionOf")
  val member: Iri = iri("member")
  val metOn: Iri = iri("metOn")
  val healthNote: Iri = iri("healthNote")
  val giftTo: Iri = iri("giftTo")
  val giftFrom: Iri = iri("giftFrom")
  val giftDescription: Iri = iri("giftDescription")
  val giftStatus: Iri = iri("giftStatus")
  val giftOccasion: Iri = iri("giftOccasion")

  private val foafAgent = Iri("foaf:Agent")
  private val foafPerson = Iri("foaf:Person")
  private val foafOrganization = Iri("foaf:Organization")
  private val foafGroup = Iri("foaf:Group")
  private val foafMember = Iri("foaf:member")

  private def domain(property: Iri, cls: Iri): Axiom = Axiom.PropertyDomain(property, cls)
  private def range(property: Iri, cls: Iri): Axiom = Axiom.PropertyRange(property, cls)

  val ontology: List[Axiom] =
    List(
      Axiom.SubClassOf(Person, Agent),
      Axiom.SubClassOf(Organization, Agent),
      Axiom.SubClassOf(Agent, CoreModule.Agent),
      Axiom.SubClassOf(Person, CoreModule.Person),
      Axiom.SubClassOf(Organization, CoreModule.Organization),
      Axiom.SubClassOf(Person, NamedEntity),
      Axiom.SubClassOf(Organization, NamedEntity),
      Axiom.SubClassOf(CompanionAnimal, NamedEntity),
      Axiom.SubClassOf(EmailAddress, ContactMethod),
      Axiom.SubClassOf(TelephoneNumber, ContactMethod),
      Axiom.SubClassOf(OnlineAccount, ContactMethod),
      Axiom.SubClassOf(PostalAddress, ContactMethod),

      // Stable, one-way FOAF alignment. FOAF data is still translated at the import boundary.
      Axiom.SubClassOf(Agent, foafAgent),
      Axiom.SubClassOf(Person, foafPerson),
      Axiom.SubClassOf(Organization, foafOrganization),
      Axiom.SubClassOf(Circle, foafGroup),
      Axiom.SubPropertyOf(member, foafMember),

      domain(hasName, NamedEntity),
      range(hasName, Name),
      domain(hasAlternativeName, NamedEntity),
      range(hasAlternativeName, Name),
      domain(birthday, Person),
      domain(nameValue, Name),
      domain(nameKind, Name),
      domain(familyName, Name),
      domain(givenName, Name),
      domain(additionalName, Name),
      domain(honorificPrefix, Name),
      domain(honorificSuffix, Name),
      domain(nameLanguage, Name),
      domain(nameScript, Name),
      domain(namePronunciation, Name),
      domain(pronouns, Person),
      domain(gender, Person),
      domain(identifierFor, ExternalIdentifier),
      range(identifierFor, Agent),
      domain(identifierScheme, ExternalIdentifier),
      range(identifierScheme, IdentifierScheme),
      domain(identifierValue, ExternalIdentifier),

      domain(contactFor, ContactMethod),
      range(contactFor, Agent),
      domain(contactKind, ContactMethod),
      domain(contactValue, ContactMethod),
      domain(contactLabel, ContactMethod),
      domain(contactPurpose, ContactMethod),
      domain(contactStatus, ContactMethod),
      domain(preferenceRank, ContactMethod),
      domain(formattedAddress, PostalAddress),
      domain(streetAddress, PostalAddress),
      domain(extendedAddress, PostalAddress),
      domain(locality, PostalAddress),
      domain(region, PostalAddress),
      domain(postalCode, PostalAddress),
      domain(countryCode, PostalAddress),

      domain(employmentFor, Employment),
      range(employmentFor, Person),
      domain(employer, Employment),
      range(employer, Organization),
      domain(jobTitle, Employment),
      domain(department, Employment),
      domain(workLocation, Employment),
      domain(employmentStatus, Employment),
      domain(worksAt, Person),
      range(worksAt, Organization),

      domain(relationshipParticipant, Relationship),
      range(relationshipParticipant, Agent),
      domain(relationshipKind, Relationship),
      domain(relationshipDescription, Relationship),
      domain(relationshipStatus, Relationship),
      domain(anniversary, Relationship),

      domain(participant, Interaction),
      range(participant, Agent),
      domain(occurredAt, Interaction),
      domain(interactionKind, Interaction),
      domain(interactionChannel, Interaction),
      domain(interactionDirection, Interaction),
      // Where an interaction happened. The range is ValueFlows' place class, so `crm:` says *that*
      // something has a place and `vf:` says what a place is; declaring it also makes the CLI type
      // the value as a reference rather than as a literal (see AGENTS.md).
      domain(location, Interaction),
      range(location, ResourcesModule.SpatialThing),
      domain(mentionedTopic, Interaction),
      domain(interactionSummary, Interaction),
      range(about, Agent),
      domain(noteBody, ContactNote),
      domain(noteKind, ContactNote),
      domain(recordedAt, ContactNote),
      domain(preferencePolarity, Preference),
      domain(preferenceTopic, Preference),
      domain(preferenceText, Preference),
      domain(preferenceContext, Preference),
      domain(followUpWith, FollowUpPlan),
      range(followUpWith, Agent),
      domain(cadenceDays, FollowUpPlan),
      domain(qualifyingChannel, FollowUpPlan),
      domain(paused, FollowUpPlan),
      domain(reminderAbout, Reminder),
      range(reminderAbout, Agent),
      domain(due, Reminder),
      domain(recurrence, Reminder),
      domain(occasion, Reminder),
      domain(companionOf, CompanionAnimal),
      range(companionOf, Agent),
      domain(member, Circle),
      range(member, Agent),
      domain(giftTo, Gift),
      range(giftTo, Agent),
      domain(giftFrom, Gift),
      range(giftFrom, Agent),
      domain(giftDescription, Gift),
      domain(giftStatus, Gift),
      domain(giftOccasion, Gift),

      Axiom.TimeVarying(hasName),
      Axiom.TimeVarying(pronouns),
      Axiom.TimeVarying(contactValue),
      Axiom.TimeVarying(contactStatus),
      Axiom.TimeVarying(preferenceRank),
      Axiom.TimeVarying(employmentStatus),
      Axiom.TimeVarying(relationshipStatus),
      Axiom.TimeVarying(paused),

      domain(knows, Agent),
      range(knows, Agent),
      domain(friendOf, Agent),
      range(friendOf, Agent),
      domain(partnerOf, Agent),
      range(partnerOf, Agent),
      domain(spouseOf, Agent),
      range(spouseOf, Agent),
      domain(siblingOf, Agent),
      range(siblingOf, Agent),
      domain(chosenFamilyOf, Agent),
      range(chosenFamilyOf, Agent),
      domain(metamourOf, Agent),
      range(metamourOf, Agent),
      domain(colleagueOf, Agent),
      range(colleagueOf, Agent),
      domain(mentorOf, Agent),
      range(mentorOf, Agent),
      domain(introducedBy, Agent),
      range(introducedBy, Agent),
      domain(reportsTo, Person),
      range(reportsTo, Person),
      domain(parentOf, Person),
      range(parentOf, Person),
      domain(childOf, Person),
      range(childOf, Person),

      Axiom.SymmetricProperty(knows),
      Axiom.SubPropertyOf(friendOf, knows),
      Axiom.SubPropertyOf(partnerOf, knows),
      Axiom.SubPropertyOf(spouseOf, partnerOf),
      Axiom.SymmetricProperty(partnerOf),
      Axiom.SymmetricProperty(siblingOf),
      Axiom.SymmetricProperty(chosenFamilyOf),
      Axiom.SubPropertyOf(chosenFamilyOf, knows),
      Axiom.SubPropertyOf(siblingOf, knows),
      Axiom.InverseProperties(parentOf, childOf),
      Axiom.PropertyChain(
        List(ChainStep(worksAt), ChainStep(worksAt, inverse = true)),
        colleagueOf
      ),
      Axiom.IrreflexiveProperty(colleagueOf),
      Axiom.SymmetricProperty(colleagueOf),
      Axiom.IrreflexiveProperty(metamourOf)
    )

  /** Active employment records materialize the compatibility `worksAt` relation (SPEC §7.1).
    *
    * All three premises remain in the justification because disclosure and derived belief rely on
    * exactly the same support graph.
    */
  val employmentRule: Rule = new Rule:
    val name = "crm:activeEmployment"

    def derive(view: ClosureView)(using ReasonerConfig) =
      for
        (record, person, employedForSupport) <-
          view.objectByProperty.getOrElse(employmentFor, Nil).iterator
        (organization, employerSupport) <-
          view.objectBySubjectProperty.getOrElse((record, employer), Nil).iterator
        (status, statusSupport) <-
          view.dataBySubjectProperty.getOrElse((record, employmentStatus), Nil).iterator
        if status.text == "active"
      yield
        Axiom.ObjectAssertion(person, worksAt, organization) ->
          Rule.combineAll(Seq(employedForSupport, employerSupport, statusSupport))

  /** `metamourOf ← partnerOf ∘ partnerOf − (partnerOf ∪ identity)` (SPEC §7.1). */
  val metamourRule: Rule = new Rule:
    val name = "crm:metamourOf"

    def derive(view: ClosureView)(using ReasonerConfig) =
      val partners = view.objectByProperty.getOrElse(partnerOf, Nil)
      val partnerPairs = partners.map((subject, obj, _) => (subject, obj)).toSet

      for
        (person, middle, firstSupport) <- partners.iterator
        (metamour, secondSupport) <-
          view.objectBySubjectProperty.getOrElse((middle, partnerOf), Nil).iterator
        if metamour != person
        if !partnerPairs.contains((person, metamour))
      yield
        Axiom.ObjectAssertion(person, metamourOf, metamour) ->
          Rule.combine(firstSupport, secondSupport)

  override val rules: List[Rule] = List(employmentRule, metamourRule)

  override val namingSchemes: List[Naming.Scheme] = List(Naming.Scheme(hasName, nameValue))

  override val validators = List(PrmValidation)

  override val importers = List(VCardImporter, FoafImporter)

  override val exporters = List(VCardExporter, FoafExporter)

  override val agendaProducers = List(PrmAgenda)

  override val policies: PolicyBook = PolicyBook.empty
    .withModule(ModuleDefaults(prefix, Sensitivity.Personal, utilityWeight = 0.9))
    .withProperty(birthday, TermPolicy.utility(0.9))
    .withProperty(hasName, TermPolicy.utility(1.0))
    .withProperty(pronouns, TermPolicy.utility(1.0))
    .withProperty(namePronunciation, TermPolicy.utility(0.8))
    .withProperty(hasAlternativeName, TermPolicy.utility(0.4))
    .withProperty(contactValue, TermPolicy.utility(0.05))
    .withProperty(contactLabel, TermPolicy.utility(0.05))
    .withProperty(contactKind, TermPolicy.utility(0.05))
    .withProperty(contactPurpose, TermPolicy.utility(0.05))
    .withProperty(contactStatus, TermPolicy.utility(0.05))
    .withProperty(preferenceRank, TermPolicy.utility(0.05))
    .withProperty(formattedAddress, TermPolicy(Sensitivity.Sensitive.some, Some(0.0)))
    .withProperty(streetAddress, TermPolicy(Sensitivity.Sensitive.some, Some(0.0)))
    .withProperty(extendedAddress, TermPolicy(Sensitivity.Sensitive.some, Some(0.0)))
    .withProperty(locality, TermPolicy(Sensitivity.Sensitive.some, Some(0.0)))
    .withProperty(region, TermPolicy(Sensitivity.Sensitive.some, Some(0.0)))
    .withProperty(postalCode, TermPolicy(Sensitivity.Sensitive.some, Some(0.0)))
    .withProperty(countryCode, TermPolicy(Sensitivity.Sensitive.some, Some(0.0)))
    .withProperty(noteBody, TermPolicy.utility(0.05))
    .withProperty(interactionSummary, TermPolicy.utility(0.05))
    .withProperty(identifierValue, TermPolicy.utility(0.0))
    .withProperty(healthNote, TermPolicy(escalateTo = Some(Sensitivity.Sensitive)))
    .withProperty(gender, TermPolicy(escalateTo = Some(Sensitivity.Sensitive)))
    .withClass(LifeEvent, TermPolicy(escalateTo = Some(Sensitivity.Sensitive)))
    // Place is `sensitive` outright rather than escalating, and that is the whole reason the place
    // model waited (SPEC §12.12): one meeting place is a fact, but places plus the dates §7 already
    // records are a movement trace, and no per-provider grant should be able to release one.
    .withProperty(location, TermPolicy(sensitivity = Some(Sensitivity.Sensitive)))

  override val itemPolicies: ItemPolicyBook = ItemPolicyBook.empty
    // Reified record scaffolding is lookup structure, not memory material.
    .withClass(Name, ItemPolicy.Ignore)
    .withClass(ContactMethod, ItemPolicy.Ignore)
    .withClass(EmailAddress, ItemPolicy.Ignore)
    .withClass(TelephoneNumber, ItemPolicy.Ignore)
    .withClass(OnlineAccount, ItemPolicy.Ignore)
    .withClass(PostalAddress, ItemPolicy.Ignore)
    .withClass(ExternalIdentifier, ItemPolicy.Ignore)
    .withClass(IdentifierScheme, ItemPolicy.Ignore)
    .withClass(Employment, ItemPolicy.Ignore)
    .withClass(Interaction, ItemPolicy.Ignore)
    .withClass(ContactNote, ItemPolicy.Ignore)
    .withClass(FollowUpPlan, ItemPolicy.Ignore)
    .withClass(Reminder, ItemPolicy.Ignore)
    .withProperty(birthday, ItemPolicy.AutoActivate)
    .withProperty(hasName, ItemPolicy.AutoActivate)
    .withProperty(nameValue, ItemPolicy.Ignore)
    .withProperty(nameKind, ItemPolicy.Ignore)
    .withProperty(familyName, ItemPolicy.Ignore)
    .withProperty(givenName, ItemPolicy.Ignore)
    .withProperty(additionalName, ItemPolicy.Ignore)
    .withProperty(honorificPrefix, ItemPolicy.Ignore)
    .withProperty(honorificSuffix, ItemPolicy.Ignore)
    .withProperty(nameLanguage, ItemPolicy.Ignore)
    .withProperty(nameScript, ItemPolicy.Ignore)
    .withProperty(pronouns, ItemPolicy.AutoActivate)
    .withProperty(namePronunciation, ItemPolicy.AutoActivate)
    .withProperty(hasAlternativeName, ItemPolicy.DraftForReview)
    .withProperty(partnerOf, ItemPolicy.AutoActivate)
    .withProperty(spouseOf, ItemPolicy.AutoActivate)
    .withProperty(parentOf, ItemPolicy.AutoActivate)
    .withProperty(worksAt, ItemPolicy.AutoActivate)
    .withProperty(contactValue, ItemPolicy.Ignore)
    .withProperty(contactFor, ItemPolicy.Ignore)
    .withProperty(contactLabel, ItemPolicy.Ignore)
    .withProperty(contactKind, ItemPolicy.Ignore)
    .withProperty(contactPurpose, ItemPolicy.Ignore)
    .withProperty(contactStatus, ItemPolicy.Ignore)
    .withProperty(preferenceRank, ItemPolicy.Ignore)
    .withProperty(formattedAddress, ItemPolicy.Ignore)
    .withProperty(streetAddress, ItemPolicy.Ignore)
    .withProperty(extendedAddress, ItemPolicy.Ignore)
    .withProperty(locality, ItemPolicy.Ignore)
    .withProperty(region, ItemPolicy.Ignore)
    .withProperty(postalCode, ItemPolicy.Ignore)
    .withProperty(countryCode, ItemPolicy.Ignore)
    .withProperty(identifierValue, ItemPolicy.Ignore)
    .withProperty(identifierFor, ItemPolicy.Ignore)
    .withProperty(identifierScheme, ItemPolicy.Ignore)
    .withProperty(employmentFor, ItemPolicy.Ignore)
    .withProperty(employer, ItemPolicy.Ignore)
    .withProperty(jobTitle, ItemPolicy.Ignore)
    .withProperty(department, ItemPolicy.Ignore)
    .withProperty(workLocation, ItemPolicy.Ignore)
    .withProperty(employmentStatus, ItemPolicy.Ignore)
    .withProperty(participant, ItemPolicy.Ignore)
    .withProperty(occurredAt, ItemPolicy.Ignore)
    .withProperty(interactionKind, ItemPolicy.Ignore)
    .withProperty(interactionChannel, ItemPolicy.Ignore)
    .withProperty(interactionDirection, ItemPolicy.Ignore)
    .withProperty(interactionSummary, ItemPolicy.Ignore)
    .withProperty(about, ItemPolicy.Ignore)
    .withProperty(noteBody, ItemPolicy.Ignore)
    .withProperty(noteKind, ItemPolicy.Ignore)
    .withProperty(recordedAt, ItemPolicy.Ignore)
    .withProperty(followUpWith, ItemPolicy.Ignore)
    .withProperty(cadenceDays, ItemPolicy.Ignore)
    .withProperty(qualifyingChannel, ItemPolicy.Ignore)
    .withProperty(paused, ItemPolicy.Ignore)
    .withProperty(reminderAbout, ItemPolicy.Ignore)
    .withProperty(due, ItemPolicy.Ignore)
    .withProperty(recurrence, ItemPolicy.Ignore)
    .withProperty(occasion, ItemPolicy.Ignore)

  override val templates: Templates = Templates.empty
    .withProperty(birthday, "{s}'s birthday is {o}")
    .withProperty(hasName, "{s} goes by {o}")
    .withProperty(hasAlternativeName, "{s} is also known as {o}")
    .withProperty(nameValue, "{s}'s name is {o}")
    .withProperty(pronouns, "{s} uses the pronouns {o}")
    .withProperty(namePronunciation, "{s}'s name is pronounced {o}")
    .withProperty(worksAt, "{s} works at {o}")
    .withProperty(contactValue, "{s}'s contact value is {o}")
    .withProperty(contactFor, "{s} is a contact method for {o}")
    .withProperty(knows, "{s} knows {o}")
    .withProperty(friendOf, "{s} is a friend of {o}")
    .withProperty(partnerOf, "{s} is a partner of {o}")
    .withProperty(spouseOf, "{s} is married to {o}")
    .withProperty(metamourOf, "{s} is a metamour of {o}")
    .withProperty(parentOf, "{s} is a parent of {o}")
    .withProperty(childOf, "{s} is a child of {o}")
    .withProperty(siblingOf, "{s} is a sibling of {o}")
    .withProperty(colleagueOf, "{s} is a colleague of {o}")
    .withProperty(location, "{s} took place at {o}")
    .withProperty(chosenFamilyOf, "{s} is chosen family to {o}")
    .withProperty(reportsTo, "{s} reports to {o}")
    .withProperty(introducedBy, "{s} was introduced by {o}")
    .withClass(Person, "{s} is a person")
    .withClass(Organization, "{s} is an organization")
    .withClass(CompanionAnimal, "{s} is a companion animal")

  extension [A](value: A) private def some: Option[A] = Some(value)
