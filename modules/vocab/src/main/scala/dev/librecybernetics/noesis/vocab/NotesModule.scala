package dev.librecybernetics.noesis.vocab

import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.core.policy.{ModuleDefaults, PolicyBook, TermPolicy}
import dev.librecybernetics.noesis.core.verbalize.Templates
import dev.librecybernetics.noesis.lms.{ItemPolicy, ItemPolicyBook}

/** Notes, journaling and the outline (SPEC §8.5).
  *
  * The load-bearing decision is that **blocks are fluents**. A block's text, its parent and its
  * position among its siblings are states with a start and possibly an end, which is exactly what
  * §3.6 already models — so editing supersedes, moving supersedes, per-block history is replay, and
  * `as-of` reconstructs a note as it stood on a past date. The journal gains no operation, and the
  * time-travel machinery this module needs is machinery that already exists and is already tested.
  *
  * What is *not* a fluent is as deliberate. `note:blockOf` is asserted once and never changes: a
  * block moving to another note is a different block, because everything that points at it — an
  * extracted fact, a quote, a link — points at it *in* a note. Making the note reassignable would
  * make every such reference conditional on a date.
  */
object NotesModule extends Module:
  val prefix = "note"
  val version = "0.1.0"

  /** A page. `Daily` is the dated one, and is named for the page rather than for the append-only
    * log of §3.2 — this project calls that the journal, and one of the two would end up meaning
    * the other (naming register, `note:` uniqueness rule).
    */
  val Note: Iri = iri("Note")
  val Daily: Iri = iri("Daily")
  val Permanent: Iri = iri("Permanent")
  val Literature: Iri = iri("Literature")

  /** The addressable unit. Provenance points here, which is why §8.5 chose block-level addressing
    * over whole-note references.
    */
  val Block: Iri = iri("Block")

  val title: Iri = iri("title")
  val createdOn: Iri = iri("createdOn")
  val tag: Iri = iri("tag")

  val blockOf: Iri = iri("blockOf")
  val parentBlock: Iri = iri("parentBlock")
  val order: Iri = iri("order")
  val text: Iri = iri("text")
  val mentions: Iri = iri("mentions")

  val ontology: List[Axiom] = List(
    Axiom.SubClassOf(Daily, Note),
    Axiom.SubClassOf(Permanent, Note),
    Axiom.SubClassOf(Literature, Note),

    // A block is not a page. Without this the two are merely unrelated, and an outline that
    // accidentally made a note its own parent would be consistent.
    Axiom.DisjointClasses(Note, Block),

    Axiom.PropertyDomain(title, Note),
    Axiom.PropertyDomain(createdOn, Note),
    Axiom.PropertyDomain(tag, Note),

    Axiom.PropertyDomain(blockOf, Block),
    Axiom.PropertyRange(blockOf, Note),
    Axiom.PropertyDomain(parentBlock, Block),
    Axiom.PropertyRange(parentBlock, Block),
    Axiom.PropertyDomain(order, Block),
    Axiom.PropertyDomain(text, Block),

    // A block may mention anything: a person, an organization, a place, a source. `owl:Thing` is
    // imported rather than a superclass coined, and the declaration is not decorative — *any*
    // declared range is what makes the CLI type the object as a reference instead of a string
    // (the trap that produced `spouseOf "marco"`), and nothing is disjoint from `owl:Thing`, so no
    // legitimate mention can be made inconsistent by it.
    Axiom.PropertyDomain(mentions, Block),
    Axiom.PropertyRange(mentions, Vocab.Thing),

    // The three states of §8.5.1. Editing, re-indenting and re-ordering are supersessions, so the
    // previous value keeps its interval rather than being overwritten.
    Axiom.TimeVarying(text),
    Axiom.TimeVarying(parentBlock),
    Axiom.TimeVarying(order),

    // A block is never its own parent, and an outline is a tree rather than a cycle. Irreflexivity
    // is the half that can be stated in the axiom language; the rest is the projection's job.
    Axiom.IrreflexiveProperty(parentBlock)
  )

  /** Notes are `personal` by default and escalate on the note they live in (SPEC §8.5.8).
    *
    * The escalation that matters is not expressible here: a block from which a `sensitive` fact was
    * extracted must itself become at least `sensitive`, or a sensitive fact could leave the system
    * by quoting the paragraph it came from. That is a capture-time consequence of what extraction
    * produced rather than a property of the term, so it belongs with extraction and arrives with
    * it — this book sets the floor it escalates from.
    */
  override val policies: PolicyBook = PolicyBook.empty
    .withModule(ModuleDefaults(prefix, Sensitivity.Personal, utilityWeight = 0.1))
    // What is written about someone is at least as revealing as the facts drawn out of it.
    .withProperty(text, TermPolicy(escalateTo = Some(Sensitivity.Sensitive)))
    // A title and a tag are how the owner finds a note again; they are not its contents.
    .withProperty(title, TermPolicy.utility(0.2))
    .withProperty(tag, TermPolicy.utility(0.1))

  /** Nothing about the *structure* of a note is worth remembering from memory.
    *
    * A note edited ten times would otherwise draft ten change items — `state.changed` fires on
    * every supersession — and the review queue would fill with the mechanics of writing rather than
    * with anything learned. The facts extracted *from* notes are ordinary axioms and are scheduled
    * by the ordinary cascade, which is the whole point of extracting into the formal representation.
    */
  override val itemPolicies: ItemPolicyBook = ItemPolicyBook.empty
    .withClass(Note, ItemPolicy.Ignore)
    .withClass(Block, ItemPolicy.Ignore)
    .withProperty(text, ItemPolicy.Ignore)
    .withProperty(order, ItemPolicy.Ignore)
    .withProperty(parentBlock, ItemPolicy.Ignore)
    .withProperty(blockOf, ItemPolicy.Ignore)
    .withProperty(mentions, ItemPolicy.Ignore)
    .withProperty(title, ItemPolicy.Ignore)
    .withProperty(createdOn, ItemPolicy.Ignore)
    .withProperty(tag, ItemPolicy.Ignore)

  override val templates: Templates = Templates.empty
    .withProperty(title, "{s} is titled {o}")
    .withProperty(tag, "{s} is tagged {o}")
    .withProperty(blockOf, "{s} is a block of {o}")
    .withProperty(mentions, "{s} mentions {o}")
