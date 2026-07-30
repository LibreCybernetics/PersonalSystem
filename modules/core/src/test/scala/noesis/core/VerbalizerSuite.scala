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

  test("structured naming follows only its declared path and compares link and value recency"):
    val oldName = Iri("noesis:e/old-name")
    val newerLink = Iri("noesis:e/newer-link")
    val unrelated = Iri("noesis:e/unrelated")
    val structuredNameValue = Iri("test:nameValue")
    val entries = List(
      JournalEntry(
        1L,
        Instant.EPOCH,
        Operation.Assert(
          Axiom.DataAssertion(alice, Vocab.label, Literal.string("Fallback")).id,
          Axiom.DataAssertion(alice, Vocab.label, Literal.string("Fallback"))
        )
      ),
      JournalEntry(
        2L,
        Instant.EPOCH,
        Operation.Assert(
          Axiom.ObjectAssertion(alice, hasName, oldName).id,
          Axiom.ObjectAssertion(alice, hasName, oldName)
        )
      ),
      JournalEntry(
        3L,
        Instant.EPOCH,
        Operation.Assert(
          Axiom.DataAssertion(oldName, structuredNameValue, Literal.string("Stale")).id,
          Axiom.DataAssertion(oldName, structuredNameValue, Literal.string("Stale"))
        )
      ),
      JournalEntry(
        100L,
        Instant.EPOCH,
        Operation.Assert(
          Axiom.DataAssertion(oldName, structuredNameValue, Literal.string("Current by value")).id,
          Axiom.DataAssertion(oldName, structuredNameValue, Literal.string("Current by value"))
        )
      ),
      JournalEntry(
        50L,
        Instant.EPOCH,
        Operation.Assert(
          Axiom.ObjectAssertion(alice, hasName, newerLink).id,
          Axiom.ObjectAssertion(alice, hasName, newerLink)
        )
      ),
      JournalEntry(
        51L,
        Instant.EPOCH,
        Operation.Assert(
          Axiom.DataAssertion(newerLink, structuredNameValue, Literal.string("Current by link")).id,
          Axiom.DataAssertion(newerLink, structuredNameValue, Literal.string("Current by link"))
        )
      ),
      JournalEntry(
        200L,
        Instant.EPOCH,
        Operation.Assert(
          Axiom.ObjectAssertion(alice, worksAt, unrelated).id,
          Axiom.ObjectAssertion(alice, worksAt, unrelated)
        )
      ),
      JournalEntry(
        201L,
        Instant.EPOCH,
        Operation.Assert(
          Axiom.DataAssertion(unrelated, structuredNameValue, Literal.string("Wrong path")).id,
          Axiom.DataAssertion(unrelated, structuredNameValue, Literal.string("Wrong path"))
        )
      )
    )
    val naming = Naming.from(
      KbState.replay(entries),
      schemes = List(Naming.Scheme(hasName, structuredNameValue))
    )

    assertEquals(naming.label(alice), "Current by value")

  test("an ongoing structured-name link and value outrank assertions and literal labels"):
    val oldName = Iri("noesis:e/old-name")
    val currentName = Iri("noesis:e/current-name")
    val structuredNameValue = Iri("test:nameValue")
    val labelAxiom = Axiom.DataAssertion(alice, Vocab.label, Literal.string("Fallback"))
    val oldLink = Axiom.ObjectAssertion(alice, hasName, oldName)
    val oldValue = Axiom.DataAssertion(oldName, structuredNameValue, Literal.string("Old"))
    val staleCurrentValue =
      Axiom.DataAssertion(
        currentName,
        structuredNameValue,
        Literal.string("Stale current value")
      )
    val state = KbState.replay(
      List(
        JournalEntry(1L, Instant.EPOCH, Operation.Assert(labelAxiom.id, labelAxiom)),
        JournalEntry(2L, Instant.EPOCH, Operation.Assert(oldLink.id, oldLink)),
        JournalEntry(3L, Instant.EPOCH, Operation.Assert(oldValue.id, oldValue)),
        JournalEntry(
          4L,
          Instant.EPOCH,
          Operation.Assert(staleCurrentValue.id, staleCurrentValue)
        ),
        JournalEntry(
          5L,
          Instant.EPOCH,
          Operation.OpenFluent(
            Fluent(FluentId.unsafe("fl_current_name_link"), alice, hasName, Node.Ref(currentName))
          )
        ),
        JournalEntry(
          6L,
          Instant.EPOCH,
          Operation.OpenFluent(
            Fluent(
              FluentId.unsafe("fl_current_name_value"),
              currentName,
              structuredNameValue,
              Node.Lit(Literal.string("Current"))
            )
          )
        )
      )
    )
    val naming = Naming.from(
      state,
      schemes = List(Naming.Scheme(hasName, structuredNameValue))
    )

    assertEquals(naming.label(alice), "Current")

  test("structured name values asserted at the same sequence use a stable lexical tie-break"):
    val name = Iri("noesis:e/name")
    val structuredNameValue = Iri("test:nameValue")
    val link = Axiom.ObjectAssertion(alice, hasName, name)
    val alpha = Axiom.DataAssertion(name, structuredNameValue, Literal.string("Alpha"))
    val zulu = Axiom.DataAssertion(name, structuredNameValue, Literal.string("Zulu"))
    val state = KbState.replay(
      List(
        JournalEntry(1L, Instant.EPOCH, Operation.Assert(link.id, link)),
        JournalEntry(2L, Instant.EPOCH, Operation.Assert(zulu.id, zulu)),
        JournalEntry(2L, Instant.EPOCH, Operation.Assert(alpha.id, alpha))
      )
    )

    assertEquals(
      Naming
        .from(state, schemes = List(Naming.Scheme(hasName, structuredNameValue)))
        .label(alice),
      "Alpha"
    )
