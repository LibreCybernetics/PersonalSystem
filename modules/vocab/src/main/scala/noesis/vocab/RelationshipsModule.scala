package noesis.vocab

import noesis.logic.*
import noesis.core.policy.{ModuleDefaults, PolicyBook, TermPolicy}
import noesis.reasoner.{ClosureView, ReasonerConfig, Rule}
import noesis.core.verbalize.Templates
import noesis.lms.{ItemPolicy, ItemPolicyBook}

/** Relationships (SPEC §7).
  *
  * The design commitments here are the spec's, and they are deliberate: relationships are
  * cardinality-free (`partnerOf` has no functionality, so concurrent partners are first-class),
  * there is no disjointness between relationship kinds (a co-parent can be a friend and a former
  * partner), and `parentOf`/`childOf` carry no arity or gender assumptions. Kinship packs that would
  * encode family structures which do not hold universally are opt-in, not installed here.
  */
object RelationshipsModule extends Module:
  val prefix = "crm"
  val version = "0.1.0"

  // Classes
  val Agent: Iri = iri("Agent")
  val Person: Iri = iri("Person")
  val Organization: Iri = iri("Organization")
  val Interaction: Iri = iri("Interaction")
  val LifeEvent: Iri = iri("LifeEvent")
  val Gift: Iri = iri("Gift")
  val Preference: Iri = iri("Preference")
  val Relationship: Iri = iri("Relationship")
  val Name: Iri = iri("Name")

  // Data properties
  val birthday: Iri = iri("birthday")
  val hasName: Iri = iri("hasName")
  val nameValue: Iri = iri("nameValue")
  val nameKind: Iri = iri("nameKind")
  val namePronunciation: Iri = iri("namePronunciation")
  val pronouns: Iri = iri("pronouns")
  val gender: Iri = iri("gender")
  val contactPoint: Iri = iri("contactPoint")
  val metOn: Iri = iri("metOn")
  val healthNote: Iri = iri("healthNote")

  // Object properties
  val knows: Iri = iri("knows")
  val friendOf: Iri = iri("friendOf")
  val partnerOf: Iri = iri("partnerOf")
  val spouseOf: Iri = iri("spouseOf")
  val metamourOf: Iri = iri("metamourOf")
  val chosenFamilyOf: Iri = iri("chosenFamilyOf")
  val siblingOf: Iri = iri("siblingOf")
  val parentOf: Iri = iri("parentOf")
  val childOf: Iri = iri("childOf")
  val worksAt: Iri = iri("worksAt")
  val colleagueOf: Iri = iri("colleagueOf")
  val reportsTo: Iri = iri("reportsTo")
  val mentorOf: Iri = iri("mentorOf")
  val introducedBy: Iri = iri("introducedBy")
  val participant: Iri = iri("participant")
  val mentionedTopic: Iri = iri("mentionedTopic")

  val ontology: List[Axiom] = List(
    // Alignment with the core upper ontology, so a crm:Person is a core:Person (SPEC §7.1).
    Axiom.SubClassOf(Person, CoreModule.Person),
    Axiom.SubClassOf(Organization, CoreModule.Organization),
    Axiom.SubClassOf(Person, Agent),
    Axiom.SubClassOf(Organization, Agent),
    Axiom.SubClassOf(Agent, CoreModule.Agent),

    // The social relationships relate agents. Declared as `Agent` rather than `Person` on purpose:
    // Person and Organization are disjoint in core, so a narrower range would turn "I know this
    // company" into an inconsistency. Having a range at all is also what tells a caller these are
    // object properties, so `spouseOf marco` resolves to the entity instead of the string "marco".
    Axiom.PropertyDomain(knows, Agent),
    Axiom.PropertyRange(knows, Agent),
    Axiom.PropertyDomain(friendOf, Agent),
    Axiom.PropertyRange(friendOf, Agent),
    Axiom.PropertyDomain(partnerOf, Agent),
    Axiom.PropertyRange(partnerOf, Agent),
    Axiom.PropertyDomain(spouseOf, Agent),
    Axiom.PropertyRange(spouseOf, Agent),
    Axiom.PropertyDomain(siblingOf, Agent),
    Axiom.PropertyRange(siblingOf, Agent),
    Axiom.PropertyDomain(chosenFamilyOf, Agent),
    Axiom.PropertyRange(chosenFamilyOf, Agent),
    Axiom.PropertyDomain(metamourOf, Agent),
    Axiom.PropertyRange(metamourOf, Agent),
    Axiom.PropertyDomain(colleagueOf, Agent),
    Axiom.PropertyRange(colleagueOf, Agent),
    Axiom.PropertyDomain(mentorOf, Agent),
    Axiom.PropertyRange(mentorOf, Agent),
    Axiom.PropertyDomain(introducedBy, Agent),
    Axiom.PropertyRange(introducedBy, Agent),

    // knows is symmetric; friendOf and partnerOf are specializations of it.
    Axiom.SymmetricProperty(knows),
    Axiom.SubPropertyOf(friendOf, knows),
    Axiom.SubPropertyOf(partnerOf, knows),
    // spouseOf ⊑ partnerOf, and neither carries cardinality: concurrent partners are first-class.
    Axiom.SubPropertyOf(spouseOf, partnerOf),
    Axiom.SymmetricProperty(partnerOf),
    Axiom.SymmetricProperty(siblingOf),
    Axiom.SymmetricProperty(chosenFamilyOf),
    Axiom.SubPropertyOf(chosenFamilyOf, knows),
    Axiom.SubPropertyOf(siblingOf, knows),

    // parentOf/childOf are inverses with no arity or gender assumptions.
    Axiom.InverseProperties(parentOf, childOf),
    Axiom.PropertyDomain(parentOf, Person),
    Axiom.PropertyRange(parentOf, Person),
    // Declared in its own right, not left implicit in the inverse: being an inverse says nothing
    // about whether the property relates entities or literals.
    Axiom.PropertyDomain(childOf, Person),
    Axiom.PropertyRange(childOf, Person),

    // Employment is a state, so it is captured as a fluent (SPEC §3.6).
    Axiom.TimeVarying(worksAt),
    Axiom.PropertyDomain(worksAt, Person),
    Axiom.PropertyRange(worksAt, Organization),

    // Names and pronouns are time-varying: a rename is one supersession (SPEC §7.2).
    Axiom.TimeVarying(hasName),
    Axiom.TimeVarying(pronouns),
    Axiom.PropertyDomain(hasName, Person),

    // The spec's RBox default. colleagueOf is irreflexive so the chain does not make everyone
    // their own colleague.
    Axiom.PropertyChain(List(ChainStep(worksAt), ChainStep(worksAt, inverse = true)), colleagueOf),
    Axiom.IrreflexiveProperty(colleagueOf),
    Axiom.SymmetricProperty(colleagueOf),
    Axiom.IrreflexiveProperty(metamourOf),

    Axiom.PropertyDomain(reportsTo, Person),
    Axiom.PropertyRange(reportsTo, Person),
    Axiom.PropertyDomain(birthday, Person),
    Axiom.SubClassOf(Interaction, iri("Event")),
    Axiom.PropertyRange(participant, Agent)
  )

  /** `metamourOf ← partnerOf ∘ partnerOf − (partnerOf ∪ identity)` (SPEC §7.1).
    *
    * Not expressible as an OWL property chain, because it subtracts. It lives here as a module rule
    * rather than in the core reasoner, which is exactly what the [[Rule]] extension point is for.
    */
  val metamourRule: Rule = new Rule:
    val name = "crm:metamourOf"

    def derive(view: ClosureView)(using ReasonerConfig) =
      val partners = view.objectByProperty.getOrElse(partnerOf, Nil)
      val partnerPairs = partners.map((s, o, _) => (s, o)).toSet

      for
        (person, middle, j1) <- partners.iterator
        (metamour, j2) <- view.objectBySubjectProperty.getOrElse((middle, partnerOf), Nil).iterator
        // Subtract identity and direct partnership: your partner's partner is your metamour only
        // if they are neither you nor also your own partner.
        if metamour != person
        if !partnerPairs.contains((person, metamour))
      yield Axiom.ObjectAssertion(person, metamourOf, metamour) -> Rule.combine(j1, j2)

  override val rules: List[Rule] = List(metamourRule)

  override val policies: PolicyBook = PolicyBook.empty
    // Default sensitivity `personal`; utility high, because the point of this module is keeping the
    // owner *fluent* in their relationships, not merely storing them (SPEC §7).
    .withModule(ModuleDefaults(prefix, Sensitivity.Personal, utilityWeight = 0.9))
    .withProperty(birthday, TermPolicy.utility(0.9))
    .withProperty(hasName, TermPolicy.utility(1.0))
    .withProperty(pronouns, TermPolicy.utility(1.0))
    .withProperty(namePronunciation, TermPolicy.utility(0.8))
    // Contact data is lookup data, below the suspend threshold unless the owner stars it.
    .withProperty(contactPoint, TermPolicy(recallUtility = Some(0.05)))
    // Health, and identity history, auto-escalate to `sensitive` (SPEC §7.2, §7.4).
    .withProperty(healthNote, TermPolicy(escalateTo = Some(Sensitivity.Sensitive)))
    .withProperty(gender, TermPolicy(escalateTo = Some(Sensitivity.Sensitive)))
    .withClass(LifeEvent, TermPolicy(escalateTo = Some(Sensitivity.Sensitive)))

  override val itemPolicies: ItemPolicyBook = ItemPolicyBook.empty
    // Always quizzed: the facts whose forgetting is socially costly (SPEC §7.4).
    .withProperty(birthday, ItemPolicy.AutoActivate)
    .withProperty(hasName, ItemPolicy.AutoActivate)
    .withProperty(pronouns, ItemPolicy.AutoActivate)
    .withProperty(namePronunciation, ItemPolicy.AutoActivate)
    .withProperty(partnerOf, ItemPolicy.AutoActivate)
    .withProperty(spouseOf, ItemPolicy.AutoActivate)
    .withProperty(parentOf, ItemPolicy.AutoActivate)
    .withProperty(worksAt, ItemPolicy.AutoActivate)
    // Never quizzed unless starred.
    .withProperty(contactPoint, ItemPolicy.Ignore)

  override val templates: Templates = Templates.empty
    .withProperty(birthday, "{s}'s birthday is {o}")
    .withProperty(hasName, "{s} goes by {o}")
    .withProperty(pronouns, "{s} uses the pronouns {o}")
    .withProperty(namePronunciation, "{s}'s name is pronounced {o}")
    .withProperty(worksAt, "{s} works at {o}")
    .withProperty(knows, "{s} knows {o}")
    .withProperty(friendOf, "{s} is a friend of {o}")
    .withProperty(partnerOf, "{s} is a partner of {o}")
    .withProperty(spouseOf, "{s} is married to {o}")
    .withProperty(metamourOf, "{s} is a metamour of {o}")
    .withProperty(parentOf, "{s} is a parent of {o}")
    .withProperty(childOf, "{s} is a child of {o}")
    .withProperty(siblingOf, "{s} is a sibling of {o}")
    .withProperty(colleagueOf, "{s} is a colleague of {o}")
    .withProperty(chosenFamilyOf, "{s} is chosen family to {o}")
    .withProperty(reportsTo, "{s} reports to {o}")
    .withProperty(introducedBy, "{s} was introduced by {o}")
    .withClass(Person, "{s} is a person")
    .withClass(Organization, "{s} is an organization")
