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
      Axiom.DataAssertion(lia, birthday, Literal.anniversary(5, 12))
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

  test("justification count caps retain exact paths and mark the result incomplete"):
    given ReasonerConfig = ReasonerConfig(maxJustifications = 2)
    val capped = Rule.cap(
      (1 to 10)
        .map(i => Justification.asserted(Axiom.ClassAssertion(Iri(s"noesis:e/p$i"), Person).id))
        .toSet
    )
    assertEquals(capped.count(_.complete), 2)
    assert(capped.contains(Justification.incomplete))

  test("a justification count exactly at the cap remains complete"):
    given ReasonerConfig = ReasonerConfig(maxJustifications = 2)
    val exact = Set(
      Justification.asserted(Axiom.ClassAssertion(alice, Person).id),
      Justification.asserted(Axiom.ClassAssertion(marco, Person).id)
    )
    assertEquals(Rule.cap(exact), exact)

  test("justification size is inclusive at the boundary and explicit beyond it"):
    given ReasonerConfig = ReasonerConfig(maxJustificationSize = 1)
    val support = Support.Asserted(Axiom.ClassAssertion(alice, Person).id)
    val one = Set(Justification.of(support))
    val other = Set(Justification.of(Support.Asserted(Axiom.ClassAssertion(marco, Person).id)))
    assertEquals(Rule.combine(one, one), one)
    assertEquals(Rule.combine(one, other), Set(Justification.incomplete))

  test("minimal() discards justifications that are supersets of smaller ones"):
    val a = Support.Asserted(Axiom.ClassAssertion(alice, Person).id)
    val b = Support.Asserted(Axiom.ClassAssertion(marco, Person).id)
    val small = Justification(Set(a))
    val large = Justification(Set(a, b))

    assertEquals(Justification.minimal(Set(small, large)), Set(small))

  test("incomplete markers neither subsume nor merge into exact provenance"):
    val a = Support.Asserted(Axiom.ClassAssertion(alice, Person).id)
    val b = Support.Asserted(Axiom.ClassAssertion(marco, Person).id)
    val small = Justification(Set(a))
    val large = Justification(Set(a, b))
    val incompleteLarge = Justification(Set(a, b), complete = false)
    val incompleteSmall = Justification(Set(a), complete = false)

    assert(!incompleteLarge.subsumedBy(small))
    assert(!large.subsumedBy(incompleteSmall))
    assertEquals(incompleteLarge.merge(small), Justification.incomplete)
    assertEquals(small.merge(incompleteSmall), Justification.incomplete)

  test("explanations expose exact paths but never an incomplete marker"):
    val fact = Axiom.ClassAssertion(alice, Person)
    val exact = Justification.asserted(fact.id)
    val closure = Closure(
      Map(fact -> Set(exact, Justification.incomplete)),
      iterations = 0,
      saturated = true
    )
    assertEquals(
      closure.explain(fact),
      Some(Explanation(fact, Set(exact)))
    )

  test("a projected closure retains inherited incompleteness"):
    val closure = Closure(
      Map.empty,
      iterations = 0,
      saturated = true,
      inheritedIncompleteReasons = Set("upstream limit")
    )
    assert(!closure.complete)
    assertEquals(closure.incompleteReasons, Set("upstream limit"))

  test("support and explanation helpers distinguish empty, asserted, fluent, and mixed provenance"):
    val assertion = Axiom.ClassAssertion(alice, Person)
    val asserted = Justification.asserted(assertion.id)
    val fluent = Justification.of(Support.FromFluent(FluentId.unsafe("fl_1")))
    val mixed = Explanation(assertion, Set(asserted, fluent))

    assertEquals(Support.Asserted(assertion.id).render, assertion.id.value)
    assertEquals(Support.FromFluent(FluentId.unsafe("fl_1")).render, "fl_1")
    assert(Justification.empty.isEmpty)
    assert(!asserted.isEmpty)
    assertEquals(asserted.axiomIds, Set(assertion.id))
    assertEquals(fluent.axiomIds, Set.empty)
    assert(mixed.isAsserted)
    assert(!mixed.isDerived)
    assert(Explanation(assertion, Set(fluent)).isDerived)

  test("graph and closure views partition assertions from schema and preserve support"):
    val assertion = Axiom.ClassAssertion(alice, Person)
    val schema = Axiom.SubClassOf(Person, Agent)
    val assertedSupport = Support.Asserted(assertion.id)
    val schemaSupport = Support.Asserted(schema.id)
    val first = Graph.of(assertion -> Set(assertedSupport))
    val graph = first ++ Graph.of(schema -> Set(schemaSupport), assertion -> Set(schemaSupport))
    val closure = Reasoner.closure(graph)

    assertEquals(graph.assertions, Set(assertion))
    assertEquals(graph.schema, Set(schema))
    assertEquals(graph.supportFor(assertion), Set(assertedSupport, schemaSupport))
    assertEquals(graph.filter(_.isAssertional).axioms, Set(assertion))
    assertEquals(
      closure.assertions,
      Set(assertion, Axiom.ClassAssertion(alice, Agent))
    )
    assertEquals(closure.asGraph.supportFor(assertion), Set(assertedSupport, schemaSupport))

  test("the iteration cap reports an explicitly unsaturated closure at its boundary"):
    val graph = graphOf(Axiom.SubClassOf(Person, Agent), Axiom.ClassAssertion(alice, Person))
    val capped = Reasoner.closure(graph, cfg = ReasonerConfig(maxIterations = 0))
    assertEquals(capped.iterations, 0)
    assert(!capped.saturated)
    assert(!capped.complete)
    assertEquals(capped.incompleteReasons, Set("iteration limit reached"))
    assert(!capped.contains(Axiom.ClassAssertion(alice, Agent)))

  test("a justification cap makes closure incompleteness observable"):
    val classes = (1 to 3).map(i => Iri(s"noesis:class/path$i"))
    val paths = classes.map(Axiom.SubClassOf(_, Agent))
    val assertions = classes.map(Axiom.ClassAssertion(alice, _))
    val graph = graphOf((paths ++ assertions)*)
    val capped = Reasoner.closure(
      graph,
      cfg = ReasonerConfig(maxJustifications = 1)
    )
    val target = Axiom.ClassAssertion(alice, Agent)

    assert(capped.contains(target))
    assert(!capped.complete)
    assertEquals(
      capped.incompleteReasons,
      Set("justification tracking limit reached")
    )
    assertEquals(capped.justificationsFor(target).count(_.complete), 1)
    assert(capped.justificationsFor(target).contains(Justification.incomplete))

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
    assert(!Consistency.isConsistent(closure))
    assertEquals(
      problems.map(_.detail),
      List("noesis:e/alice is both Person and Organization, which are disjoint")
    )
    val rendered = problems.map(_.render)
    assert(rendered.exists(_.startsWith("[disjoint-classes] noesis:e/alice is both")))
    problems.zip(rendered).foreach: entry =>
      val (problem, text) = entry
      val premises = problem.justification.premises.toList.sorted.map(_.render).mkString(", ")
      assert(text.endsWith(s"(from: $premises)"), text)

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
    val problems = Consistency.check(closure)
    assertEquals(problems.map(_.kind), List(InconsistencyKind.SameAndDifferent))
    assertEquals(
      problems.map(_.detail),
      List("noesis:e/alice is asserted both the same as and different from noesis:e/marco")
    )

  test("a self-loop on an irreflexive property is inconsistent"):
    val closure = closureOf(
      Axiom.IrreflexiveProperty(colleagueOf),
      Axiom.ObjectAssertion(alice, colleagueOf, alice)
    )
    val problems = Consistency.check(closure)
    assertEquals(problems.map(_.kind), List(InconsistencyKind.IrreflexiveSelfLoop))
    assertEquals(
      problems.map(_.detail),
      List("noesis:e/alice colleagueOf itself, but colleagueOf is irreflexive")
    )

  test("the crm schema on its own is consistent"):
    val closure = Reasoner.closure(graphOf(crmSchema*))
    assertEquals(Consistency.check(closure), Nil)
    assert(Consistency.isConsistent(closure))

  test("subproperty transitivity derives the schema edge itself"):
    val closure = closureOf(
      Axiom.SubPropertyOf(spouseOf, partnerOf),
      Axiom.SubPropertyOf(partnerOf, knows)
    )
    assert(closure.contains(Axiom.SubPropertyOf(spouseOf, knows)))
