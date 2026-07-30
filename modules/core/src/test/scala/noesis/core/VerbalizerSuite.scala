package noesis.core

import java.time.Instant

import munit.FunSuite
import noesis.core.Fixtures.*
import noesis.core.projection.KbState
import noesis.core.verbalize.{Naming, NamingContext, Templates, Verbalizer}
import noesis.journal.{JournalEntry, Operation}
import noesis.logic.*

/** Exact natural-language contract tests: naming is privacy-sensitive and confirmation text is UI. */
class VerbalizerSuite extends FunSuite:
  private val labels = NamingContext(
    Map(
      alice -> "Alice",
      marco -> "Marco",
      Person -> "Person",
      Organization -> "Organization",
      Agent -> "Agent"
    )
  )
  private val verbalizer = new Verbalizer(labels)

  test("fallback labels distinguish opaque handles from humanized vocabulary"):
    assertEquals(labels.label(Iri("noesis:e/0123456789abcdef")), "⟨e/012345⟩")
    assertEquals(labels.label(Iri("crm:falseFriendOf")), "false friend of")
    assertEquals(Naming.humanize("HTTPServerURL"), "http server url")

  test("every axiom form has an exact readable fallback"):
    val axioms = List(
      Axiom.ObjectAssertion(alice, worksAt, marco) -> "Alice works at Marco",
      Axiom.DataAssertion(alice, birthday, Literal.string("May 12")) -> "Alice birthday May 12",
      Axiom.ClassAssertion(alice, Person) -> "Alice is a Person",
      Axiom.SubClassOf(Person, Agent) -> "every Person is an Agent",
      Axiom.DisjointClasses(Person, Organization) ->
        "nothing is both a Person and an Organization",
      Axiom.SubPropertyOf(friendOf, knows) -> "friend of implies knows",
      Axiom.InverseProperties(parentOf, childOf) -> "parent of is the inverse of child of",
      Axiom.SymmetricProperty(knows) -> "knows goes both ways",
      Axiom.TransitiveProperty(ancestorOf) -> "ancestor of chains transitively",
      Axiom.IrreflexiveProperty(colleagueOf) -> "nothing colleague of itself",
      Axiom.PropertyChain(
        List(ChainStep(worksAt), ChainStep(parentOf, inverse = true)),
        colleagueOf
      ) -> "works at, then parent of (reversed) implies colleague of",
      Axiom.PropertyDomain(worksAt, Person) -> "only Persons can works at",
      Axiom.PropertyRange(worksAt, Organization) ->
        "works at always points at an Organization",
      Axiom.TimeVarying(worksAt) -> "works at changes over time",
      Axiom.SameIndividual(alice, marco) -> "Alice is the same as Marco",
      Axiom.DifferentIndividuals(alice, marco) -> "Alice is not Marco"
    )

    axioms.foreach: entry =>
      val (axiom, expected) = entry
      assertEquals(verbalizer(axiom), expected)

  test("class and property templates replace every supported placeholder"):
    val templates = Templates.empty
      .withClass(Person, "{s} is definitely a person")
      .withProperty(worksAt, "{s}|{p}|{o}")
    val custom = new Verbalizer(labels, templates)

    assertEquals(custom.verbalize(Axiom.ClassAssertion(alice, Person)), "Alice is definitely a person")
    assertEquals(
      custom.verbalize(Axiom.ObjectAssertion(alice, worksAt, marco)),
      "Alice|worksAt|Marco"
    )

  test("articles and plurals cover vowels, consonants, empty labels, and existing s endings"):
    val apple = Iri("core:Apple")
    val empty = Iri("core:Empty")
    val glass = Iri("core:Glass")
    val names = NamingContext(labels.labels ++ Map(apple -> "Apple", empty -> "", glass -> "Glass"))
    val v = new Verbalizer(names)

    assertEquals(v.verbalize(Axiom.ClassAssertion(alice, apple)), "Alice is an Apple")
    assertEquals(v.verbalize(Axiom.ClassAssertion(alice, Person)), "Alice is a Person")
    assertEquals(v.verbalize(Axiom.ClassAssertion(alice, empty)), "Alice is a ")
    assertEquals(v.verbalize(Axiom.PropertyDomain(knows, glass)), "only Glass can knows")
    assertEquals(v.verbalize(Axiom.PropertyDomain(knows, Person)), "only Persons can knows")

  test("fluent verbalization distinguishes all four temporal boundary shapes and literal values"):
    val base = Fluent(FluentId.unsafe("fl_1"), alice, worksAt, Node.Ref(marco))
    val from = PartialDate.of(2026, 1, 1)
    val to = PartialDate.of(2026, 7, 1)
    assertEquals(verbalizer.verbalize(base), "Alice works at Marco")
    assertEquals(
      verbalizer.verbalize(base.copy(validFrom = Some(from))),
      "Alice works at Marco (since 2026-01-01)"
    )
    assertEquals(
      verbalizer.verbalize(base.copy(validTo = Some(to))),
      "Alice works at Marco (until 2026-07-01)"
    )
    assertEquals(
      verbalizer.verbalize(base.copy(validFrom = Some(from), validTo = Some(to))),
      "Alice works at Marco (from 2026-01-01 to 2026-07-01)"
    )
    assertEquals(
      verbalizer.verbalize(
        base.copy(statedProperty = Vocab.label, statedValue = Node.Lit(Literal.string("Alicia")))
      ),
      "Alice label Alicia"
    )

  test("naming priority is stable for duplicate configuration entries and equal-priority facts"):
    val labelAxiom = Axiom.DataAssertion(alice, Vocab.label, Literal.string("Label"))
    val nameAxiom = Axiom.DataAssertion(alice, hasName, Literal.string("Name"))
    val state = KbState.replay(
      List(
        JournalEntry(1L, Instant.EPOCH, Operation.Assert(labelAxiom.id, labelAxiom)),
        JournalEntry(2L, Instant.EPOCH, Operation.Assert(nameAxiom.id, nameAxiom)),
        JournalEntry(
          3L,
          Instant.EPOCH,
          Operation.OpenFluent(
            Fluent(FluentId.unsafe("fl_label"), marco, Vocab.label, Node.Lit(Literal.string("Fluent label")))
          )
        ),
        JournalEntry(
          4L,
          Instant.EPOCH,
          Operation.OpenFluent(
            Fluent(FluentId.unsafe("fl_name"), marco, hasName, Node.Lit(Literal.string("Fluent name")))
          )
        )
      )
    )
    val naming = Naming.from(state, List(hasName, Vocab.label, hasName))

    assertEquals(naming.label(alice), "Name")
    assertEquals(naming.label(marco), "Fluent name")
