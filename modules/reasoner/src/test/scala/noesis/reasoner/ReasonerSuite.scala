package noesis.reasoner

import munit.FunSuite
import noesis.logic.*
import noesis.reasoner.Fixtures.*

/** Inference tests.
  *
  * Each case asserts both the derived fact *and* its justification, because SPEC §3.3.1 and §4.4
  * both depend on justifications being right — a closure with correct facts and wrong provenance
  * would pass a facts-only test while silently breaking the privacy model.
  */
class ReasonerSuite extends FunSuite:

  /** Builds a graph in which every axiom is asserted under its own id. */
  private def graphOf(axioms: Axiom*): Graph =
    Graph(axioms.map(a => a -> Set[Support](Support.Asserted(a.id))).toMap)

  private def closureOf(axioms: Axiom*): Closure = Reasoner.closure(graphOf(axioms*))

  /** The premise sets of every justification for `axiom`, resolved back to axioms. */
  private def premisesOf(closure: Closure, axiom: Axiom): Set[Set[Axiom]] =
    closure
      .justificationsFor(axiom)
      .map(_.premises.collect { case Support.Asserted(id) => id })
      .map(ids => ids.flatMap(id => closure.axioms.find(_.id == id)))

  test("subClassOf is transitive"):
    val closure =
      closureOf(Axiom.SubClassOf(Person, Agent), Axiom.SubClassOf(Agent, Iri("core:Thing")))
    assert(closure.contains(Axiom.SubClassOf(Person, Iri("core:Thing"))))

  test("class assertions propagate up the hierarchy, carrying both premises"):
    val subclass = Axiom.SubClassOf(Person, Agent)
    val assertion = Axiom.ClassAssertion(alice, Person)
    val closure = closureOf(subclass, assertion)

    val derived = Axiom.ClassAssertion(alice, Agent)
    assert(closure.contains(derived))
    assertEquals(premisesOf(closure, derived), Set(Set(subclass, assertion)))

  test("an asserted fact keeps its own one-premise justification"):
    val assertion = Axiom.ClassAssertion(alice, Person)
    val closure = closureOf(Axiom.SubClassOf(Person, Agent), assertion)
    assertEquals(premisesOf(closure, assertion), Set(Set(assertion)))

  test("subPropertyOf propagates assertions, so friendOf implies knows"):
    val closure = closureOf(
      Axiom.SubPropertyOf(friendOf, knows),
      Axiom.ObjectAssertion(alice, friendOf, marco)
    )
    assert(closure.contains(Axiom.ObjectAssertion(alice, knows, marco)))

  test("a two-step property hierarchy composes: spouseOf ⊑ partnerOf ⊑ knows"):
    val closure = closureOf(
      Axiom.SubPropertyOf(spouseOf, partnerOf),
      Axiom.SubPropertyOf(partnerOf, knows),
      Axiom.ObjectAssertion(sarah, spouseOf, marco)
    )
    assert(closure.contains(Axiom.ObjectAssertion(sarah, partnerOf, marco)))
    assert(closure.contains(Axiom.ObjectAssertion(sarah, knows, marco)))

  test("domain and range assign classes from a bare property assertion"):
    val closure = closureOf(
      Axiom.PropertyDomain(worksAt, Person),
      Axiom.PropertyRange(worksAt, Organization),
      Axiom.ObjectAssertion(alice, worksAt, acme)
    )
    assert(closure.contains(Axiom.ClassAssertion(alice, Person)), "domain did not fire")
    assert(closure.contains(Axiom.ClassAssertion(acme, Organization)), "range did not fire")

  test("domain applies to data assertions too"):
    val closure = closureOf(
      Axiom.PropertyDomain(birthday, Person),
      Axiom.DataAssertion(lia, birthday, Literal.Date(PartialDate.monthDay(5, 12)))
    )
    assert(closure.contains(Axiom.ClassAssertion(lia, Person)))

  test("symmetric properties infer the reverse direction"):
    val closure =
      closureOf(Axiom.SymmetricProperty(knows), Axiom.ObjectAssertion(alice, knows, marco))
    assert(closure.contains(Axiom.ObjectAssertion(marco, knows, alice)))

  test("transitive properties chain without looping forever"):
    val closure = closureOf(
      Axiom.TransitiveProperty(ancestorOf),
      Axiom.ObjectAssertion(lia, ancestorOf, sarah),
      Axiom.ObjectAssertion(sarah, ancestorOf, alice)
    )
    assert(closure.contains(Axiom.ObjectAssertion(lia, ancestorOf, alice)))
    assert(closure.saturated, "the fixpoint should terminate on a finite chain")

  test("a transitive cycle terminates rather than exhausting the iteration cap"):
    val closure = closureOf(
      Axiom.TransitiveProperty(knows),
      Axiom.ObjectAssertion(alice, knows, marco),
      Axiom.ObjectAssertion(marco, knows, sarah),
      Axiom.ObjectAssertion(sarah, knows, alice)
    )
    assert(closure.saturated, "a cycle must still reach a fixpoint")
    assert(closure.contains(Axiom.ObjectAssertion(alice, knows, sarah)))

  test("inverse properties infer both directions: parentOf ⁻ = childOf"):
    val closure = closureOf(
      Axiom.InverseProperties(parentOf, childOf),
      Axiom.ObjectAssertion(sarah, parentOf, lia)
    )
    assert(closure.contains(Axiom.ObjectAssertion(lia, childOf, sarah)))

    val reverse = closureOf(
      Axiom.InverseProperties(parentOf, childOf),
      Axiom.ObjectAssertion(lia, childOf, sarah)
    )
    assert(reverse.contains(Axiom.ObjectAssertion(sarah, parentOf, lia)))

  test("the spec's colleagueOf chain derives colleagues from a shared employer"):
    // worksAt ∘ worksAt⁻ ⊑ colleagueOf  (SPEC §7.1)
    val closure = closureOf(
      Axiom.PropertyChain(List(ChainStep(worksAt), ChainStep(worksAt, inverse = true)), colleagueOf),
      Axiom.IrreflexiveProperty(colleagueOf),
      Axiom.ObjectAssertion(alice, worksAt, acme),
      Axiom.ObjectAssertion(marco, worksAt, acme)
    )

    assert(closure.contains(Axiom.ObjectAssertion(alice, colleagueOf, marco)))
    assert(closure.contains(Axiom.ObjectAssertion(marco, colleagueOf, alice)))

  test("an irreflexive chain super-property does not make anyone their own colleague"):
    val closure = closureOf(
      Axiom.PropertyChain(List(ChainStep(worksAt), ChainStep(worksAt, inverse = true)), colleagueOf),
      Axiom.IrreflexiveProperty(colleagueOf),
      Axiom.ObjectAssertion(alice, worksAt, acme)
    )
    assert(
      !closure.contains(Axiom.ObjectAssertion(alice, colleagueOf, alice)),
      "irreflexivity must suppress the self-loop the chain would otherwise entail"
    )

  test("people at different employers are not colleagues"):
    val closure = closureOf(
      Axiom.PropertyChain(List(ChainStep(worksAt), ChainStep(worksAt, inverse = true)), colleagueOf),
      Axiom.IrreflexiveProperty(colleagueOf),
      Axiom.ObjectAssertion(alice, worksAt, acme),
      Axiom.ObjectAssertion(marco, worksAt, molina)
    )
    assert(!closure.contains(Axiom.ObjectAssertion(alice, colleagueOf, marco)))

  test("a fluent-backed premise is tracked as fluent support, not as an asserted axiom"):
    val fluentId = FluentId.unsafe("fl_1")
    val subclass = Axiom.SubClassOf(Person, Agent)
    val domain = Axiom.PropertyDomain(worksAt, Person)
    val employment = Axiom.ObjectAssertion(alice, worksAt, acme)

    val graph = Graph(
      Map(
        subclass -> Set[Support](Support.Asserted(subclass.id)),
        domain -> Set[Support](Support.Asserted(domain.id)),
        employment -> Set[Support](Support.FromFluent(fluentId))
      )
    )
    val closure = Reasoner.closure(graph)

    val derived = Axiom.ClassAssertion(alice, Person)
    assert(closure.contains(derived))
    assert(
      closure.justificationsFor(derived).exists(_.premises.contains(Support.FromFluent(fluentId))),
      "the fluent must appear in the justification, or disclosure cannot classify it"
    )

  test("a fact with two independent derivations keeps both justifications"):
    // alice knows marco both via friendOf ⊑ knows and via symmetry from marco knows alice
    val viaFriend = Axiom.ObjectAssertion(alice, friendOf, marco)
    val viaSymmetry = Axiom.ObjectAssertion(marco, knows, alice)
    val closure = closureOf(
      Axiom.SubPropertyOf(friendOf, knows),
      Axiom.SymmetricProperty(knows),
      viaFriend,
      viaSymmetry
    )

    val target = Axiom.ObjectAssertion(alice, knows, marco)
    assert(closure.contains(target))
    assert(
      closure.justificationsFor(target).size >= 2,
      s"expected multiple derivations, got ${closure.justificationsFor(target)}"
    )

  test("justification count is capped, keeping the closure bounded"):
    given ReasonerConfig = ReasonerConfig(maxJustifications = 2)
    val capped = Rule.cap(
      (1 to 10)
        .map(i => Justification.asserted(Axiom.ClassAssertion(Iri(s"noesis:e/p$i"), Person).id))
        .toSet
    )
    assertEquals(capped.size, 2)

  test("minimal() discards justifications that are supersets of smaller ones"):
    val a = Support.Asserted(Axiom.ClassAssertion(alice, Person).id)
    val b = Support.Asserted(Axiom.ClassAssertion(marco, Person).id)
    val small = Justification(Set(a))
    val large = Justification(Set(a, b))

    assertEquals(Justification.minimal(Set(small, large)), Set(small))

  test("entailed() reports derived facts and excludes asserted ones"):
    val assertion = Axiom.ClassAssertion(alice, Person)
    val subclass = Axiom.SubClassOf(Person, Agent)
    val base = graphOf(assertion, subclass)
    val entailed = Reasoner.closure(base).entailed(base)

    assert(entailed.contains(Axiom.ClassAssertion(alice, Agent)))
    assert(!entailed.contains(assertion))

  // ── Consistency (SPEC §3.4) ────────────────────────────────────────────────

  test("an individual in two disjoint classes is inconsistent"):
    val closure = closureOf(
      Axiom.DisjointClasses(Person, Organization),
      Axiom.ClassAssertion(alice, Person),
      Axiom.ClassAssertion(alice, Organization)
    )
    val problems = Consistency.check(closure)

    assertEquals(problems.map(_.kind), List(InconsistencyKind.DisjointClassMembership))
    assert(
      problems.forall(_.justification.premises.nonEmpty),
      "a rejection needs a justification"
    )

  test("disjointness is detected through inference, not only on asserted classes"):
    // alice is only asserted to be a Person; Organization membership is derived via range.
    val closure = closureOf(
      Axiom.DisjointClasses(Person, Organization),
      Axiom.PropertyRange(worksAt, Organization),
      Axiom.ClassAssertion(alice, Person),
      Axiom.ObjectAssertion(marco, worksAt, alice)
    )
    assert(Consistency.check(closure).nonEmpty, "inferred disjointness violation was missed")

  test("asserting an individual is both the same as and different from another is inconsistent"):
    val closure = closureOf(
      Axiom.SameIndividual(alice, marco),
      Axiom.DifferentIndividuals(alice, marco)
    )
    assertEquals(Consistency.check(closure).map(_.kind), List(InconsistencyKind.SameAndDifferent))

  test("a self-loop on an irreflexive property is inconsistent"):
    val closure = closureOf(
      Axiom.IrreflexiveProperty(colleagueOf),
      Axiom.ObjectAssertion(alice, colleagueOf, alice)
    )
    assertEquals(Consistency.check(closure).map(_.kind), List(InconsistencyKind.IrreflexiveSelfLoop))

  test("the crm schema on its own is consistent"):
    assertEquals(Consistency.check(Reasoner.closure(graphOf(crmSchema*))), Nil)

  // ── EL profile warnings (SPEC §3.1) ────────────────────────────────────────

  test("inverse and symmetric properties warn about leaving OWL 2 EL"):
    assert(Profile.elWarning(Axiom.InverseProperties(parentOf, childOf)).isDefined)
    assert(Profile.elWarning(Axiom.SymmetricProperty(knows)).isDefined)
    assert(
      Profile
        .elWarning(Axiom.PropertyChain(List(ChainStep(worksAt, inverse = true)), colleagueOf))
        .isDefined
    )

  test("EL-safe axioms produce no warning"):
    assert(Profile.isEl(Axiom.SubClassOf(Person, Agent)))
    assert(Profile.isEl(Axiom.ClassAssertion(alice, Person)))
    assert(Profile.isEl(Axiom.PropertyDomain(worksAt, Person)))
    assert(
      Profile.isEl(Axiom.PropertyChain(List(ChainStep(parentOf), ChainStep(parentOf)), ancestorOf))
    )
