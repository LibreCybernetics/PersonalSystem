package noesis.vocab

import noesis.logic.*
import noesis.core.policy.{ModuleDefaults, PolicyBook}
import noesis.core.verbalize.Templates
import noesis.lms.{ItemPolicy, ItemPolicyBook}

/** The core upper ontology (SPEC §3, §12.6).
  *
  * Deliberately thin. §12.6 leaves open how much belongs in core, and the answer this MVP takes is:
  * only what other modules need to align on — `core:Agent` and its two subclasses, so `crm:Person`
  * and `vf:Agent` can denote the same Marco (§8), plus the fluent vocabulary.
  */
object CoreModule extends Module:
  val prefix = "core"
  val version = "0.1.0"

  val Agent: Iri = Vocab.Agent
  val Person: Iri = Vocab.Person
  val Organization: Iri = Vocab.Organization
  val me: Iri = Vocab.me

  val ontology: List[Axiom] = List(
    Axiom.SubClassOf(Person, Agent),
    Axiom.SubClassOf(Organization, Agent),
    // A person is not an organization. This is what makes a mis-captured `worksAt` target an
    // inconsistency the commit pre-flight catches rather than a silent modeling error.
    Axiom.DisjointClasses(Person, Organization),
    Axiom.ClassAssertion(me, Person)
  )

  override val policies: PolicyBook = PolicyBook.empty
    .withModule(ModuleDefaults(prefix, Sensitivity.Public, utilityWeight = 0.5))

  override val itemPolicies: ItemPolicyBook =
    // The upper ontology is scaffolding, not something to be quizzed on.
    ItemPolicyBook.empty.withClass(Agent, ItemPolicy.Ignore)

  override val templates: Templates = Templates.empty
    .withProperty(Vocab.label, "{s} is called {o}")
    .withClass(Person, "{s} is a person")
    .withClass(Organization, "{s} is an organization")
