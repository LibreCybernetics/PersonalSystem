package dev.librecybernetics.noesis.vocab

import munit.FunSuite

import dev.librecybernetics.noesis.core.verbalize.Templates
import dev.librecybernetics.noesis.logic.*

/** The vocabulary as something the owner can look through (SPEC §5.1, PRODUCT.md F1, US-04).
  *
  * Read back out of the module contract rather than transcribed, so these tests are run against the
  * modules that actually ship: a term that stops being declared stops being listed, and one that is
  * added is listed without anything here being told about it.
  */
class VocabularySuite extends FunSuite:

  private val terms = Vocabulary.of(Modules.all)

  private def term(name: String): Vocabulary.Term =
    Vocabulary.find(terms, name).getOrElse(fail(s"no such term: $name"))
  // ── Terms the shipped modules do not happen to exercise ───────────────────
  //
  // Every property `crm:` declares also declares a domain or a range, so the shipped vocabulary
  // never asks whether a term used *only* as a subproperty, an inverse or a link in a chain is a
  // property. A module may declare one that way, and then the browser would call it a class.

  /** Borrows the `ll` prefix so its terms compact; never registered in `Modules.all`. */
  private object Synthetic extends Module:
    val prefix = "ll"
    val version = "0.0.0"
    val onlySub: Iri = iri("onlySub")
    val onlySuper: Iri = iri("onlySuper")
    val onlyInverse: Iri = iri("onlyInverse")
    val onlyChained: Iri = iri("onlyChained")
    val onlyChainTarget: Iri = iri("onlyChainTarget")
    val plainClass: Iri = iri("PlainClass")
    val namedForTemplate: Iri = iri("aObscure")
    val marriedTo: Iri = iri("zMarriedToSomeone")
    val secondStep: Iri = iri("secondStep")

    val ontology: List[Axiom] = List(
      Axiom.SubPropertyOf(onlySub, onlySuper),
      Axiom.InverseProperties(onlyInverse, onlySub),
      Axiom.PropertyChain(List(ChainStep(onlyChained), ChainStep(secondStep)), onlyChainTarget),
      // A transitive property so that a *class* in this module is asked about it too.
      Axiom.TransitiveProperty(onlySub),
      Axiom.PropertyDomain(marriedTo, plainClass),
      Axiom.SubClassOf(plainClass, CoreModule.Agent),
      Axiom.PropertyDomain(namedForTemplate, plainClass)
    )

    override val templates: Templates = Templates.empty
      .withProperty(namedForTemplate, "{s} is married to {o}")

  private val synthetic = Vocabulary.of(List(Synthetic))

  private def role(iri: Iri): Vocabulary.Role =
    synthetic.find(_.iri == iri).map(_.role).getOrElse(fail(s"not listed: ${iri.display}"))

  test("every shipped module contributes its terms, and only its own"):
    assertEquals(terms.map(_.module).distinct.sorted, List("core", "crm", "ll", "note", "vf"))
    assert(terms.forall(t => t.iri.display.startsWith(s"${t.module}:")), "a term escaped its module")

  test("a term is listed once, however many axioms mention it"):
    // `crm:spouseOf` appears in a domain, a range and a subproperty axiom.
    assertEquals(terms.count(_.iri == RelationshipsModule.spouseOf), 1)

  test("what a property relates is read from the ontology"):
    val spouseOf = term("crm:spouseOf")
    assertEquals(spouseOf.role, Vocabulary.Role.Property)
    assertEquals(spouseOf.domain, List(RelationshipsModule.Agent))
    assertEquals(spouseOf.range, List(RelationshipsModule.Agent))
    assertEquals(spouseOf.template, Some("{s} is married to {o}"))

  test("a class is listed as a class, with what it specializes"):
    val person = term("crm:Person")
    assertEquals(person.role, Vocabulary.Role.Class)
    assert(person.domain.contains(CoreModule.Person), person.domain.toString)

  test("the defaults shown are the ones the cascade would apply"):
    // The owner cannot otherwise see these, and they decide whether a fact may leave the machine.
    val birthday = term("crm:birthday")
    assertEquals(birthday.utility, 0.9, "the term policy overrides the module weight")
    assertEquals(birthday.sensitivity, Sensitivity.Personal, "and falls back to the module default")

  test("an escalating term says so, since the default alone would understate it"):
    assertEquals(term("crm:healthNote").escalatesTo, Some(Sensitivity.Sensitive))

  test("a time-varying property is marked, because asserting it opens a state"):
    assert(term("note:text").timeVarying, "note:text is a fluent (§8.5.1)")
    assert(!term("note:blockOf").timeVarying, "and note:blockOf deliberately is not")

  // ── Searching ─────────────────────────────────────────────────────────────

  test("a term is found by its name"):
    assert(Vocabulary.search(terms, "spouse").map(_.iri).contains(RelationshipsModule.spouseOf))

  test("a term is found by how it reads, not only by what it is called"):
    // The owner knows they want to record that someone is married, not that the term contains
    // the substring "spouse". Matching the template is what closes that gap.
    assert(
      Vocabulary.search(terms, "married").map(_.iri).contains(RelationshipsModule.spouseOf),
      "searching for the words the verbalizer uses should find the term"
    )

  test("an exact name outranks a term that merely contains it"):
    val found = Vocabulary.search(terms, "text").map(_.iri)
    assertEquals(found.headOption, Some(NotesModule.text))

  test("searching for nothing matches nothing, rather than everything"):
    assertEquals(Vocabulary.search(terms, ""), Nil)
    assertEquals(Vocabulary.search(terms, "   "), Nil)

  test("a query matching no term returns nothing rather than a guess"):
    assertEquals(Vocabulary.search(terms, "zzzzz"), Nil)

  test("search ignores case, since the owner is typing prose"):
    assertEquals(Vocabulary.search(terms, "SPOUSE").map(_.iri), Vocabulary.search(terms, "spouse").map(_.iri))

  // ── Looking one up ────────────────────────────────────────────────────────

  test("a term resolves written out or as a bare name"):
    assertEquals(Vocabulary.find(terms, "crm:birthday").map(_.iri), Some(RelationshipsModule.birthday))
    assertEquals(Vocabulary.find(terms, "birthday").map(_.iri), Some(RelationshipsModule.birthday))
    assertEquals(Vocabulary.find(terms, "CRM:Birthday").map(_.iri), Some(RelationshipsModule.birthday))

  test("an unknown term resolves to nothing rather than to something similar"):
    assertEquals(Vocabulary.find(terms, "crm:birthdate"), None)

  // ── Telling the owner what to type ────────────────────────────────────────

  test("the example for a property names the kind of value it takes"):
    assertEquals(
      Vocabulary.example(term("crm:spouseOf")),
      "noesis assert <subject> crm:spouseOf <Agent>"
    )

  test("a property with no declared range says only that a value goes there"):
    // `crm:birthday` declares no range: the axiom language's `PropertyRange` puts its object in the
    // class role, so a datatype cannot be declared there without punning it against its datatype
    // role (ISO/IEC 11179-5 §8.1.2). The example says what it honestly can.
    assertEquals(term("crm:birthday").range, Nil)
    assertEquals(
      Vocabulary.example(term("crm:birthday")),
      "noesis assert <subject> crm:birthday <value>"
    )

  test("the example for a class is how an entity is given that type"):
    assertEquals(
      Vocabulary.example(term("crm:Person")),
      "noesis assert <entity> rdf:type crm:Person"
    )


  test("a property declared only as a subproperty is still a property"):
    assertEquals(role(Synthetic.onlySub), Vocabulary.Role.Property)
    assertEquals(role(Synthetic.onlySuper), Vocabulary.Role.Property)

  test("a property declared only as an inverse is still a property"):
    assertEquals(role(Synthetic.onlyInverse), Vocabulary.Role.Property)

  test("a property declared only inside a chain is still a property"):
    assertEquals(role(Synthetic.onlyChained), Vocabulary.Role.Property)
    assertEquals(role(Synthetic.onlyChainTarget), Vocabulary.Role.Property)

  test("a term in no property position is a class"):
    assertEquals(role(Synthetic.plainClass), Vocabulary.Role.Class)

  test("a name match outranks a term that only reads that way"):
    // Ordered so that only the tier can explain the result: the template-only match sorts *earlier*
    // by identifier, so if a name match did not outrank it, it would come first.
    val found = Vocabulary.search(synthetic, "obscure").map(_.iri)
    assertEquals(found.headOption, Some(Synthetic.namedForTemplate))

    val byTemplate = Vocabulary.search(synthetic, "married").map(_.iri)
    assertEquals(
      byTemplate,
      List(Synthetic.marriedTo, Synthetic.namedForTemplate),
      "the term whose *name* says it comes before the one that merely reads that way"
    )
