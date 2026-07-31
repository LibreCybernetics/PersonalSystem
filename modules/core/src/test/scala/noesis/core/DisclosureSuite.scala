package noesis.core

import java.time.Instant

import munit.FunSuite
import noesis.core.Fixtures.*
import noesis.journal.{JournalEntry, Operation}
import noesis.logic.*
import noesis.core.policy.*
import noesis.core.projection.{AxiomRecord, KbState, Projections}
import noesis.reasoner.{Closure, Justification, Reasoner, Support}

/** The privacy model (SPEC §3.3, §3.3.1, §9).
  *
  * The derivation rule — `min over justifications (max over axioms)` — is the subtlest thing in the
  * spec and the most damaging to get wrong, so it gets the most cases here, including the explicit
  * requirement that "a conclusion derivable from public facts alone is public, whatever other
  * derivation paths exist."
  */
class DisclosureSuite extends FunSuite:

  private def stateOf(assertions: (Axiom, AxiomAnnotations)*): KbState =
    KbState.replay(
      assertions.zipWithIndex.map: (pair, i) =>
        val (axiom, annotations) = pair
        JournalEntry(i + 1L, Instant.EPOCH, Operation.Assert(axiom.id, axiom, annotations))
    )

  private def at(level: Sensitivity, scopes: Set[Iri] = Set.empty): AxiomAnnotations =
    AxiomAnnotations.ownerConfirmed.withSensitivity(level, scopes)

  private def record(axiom: Axiom, annotations: AxiomAnnotations): AxiomRecord =
    AxiomRecord(axiom.id, axiom, annotations, AxiomStatus.Active, 1L)

  // ── The policy cascade (SPEC §3.3) ─────────────────────────────────────────

  test("an explicit owner override beats every lower cascade level"):
    val book = PolicyBook.empty
      .withProperty(birthday, TermPolicy(sensitivity = Some(Sensitivity.Public)))
      .withModule(ModuleDefaults("crm", Sensitivity.Personal))
    val axiom = Axiom.DataAssertion(lia, birthday, Literal.date(PartialDate.monthDay(5, 12)))

    val overridden = record(axiom, at(Sensitivity.Sensitive))
    assertEquals(PolicyCascade.sensitivity(overridden, book), Sensitivity.Sensitive)

  test("a property policy applies when the owner set nothing"):
    val book = PolicyBook.empty.withProperty(salary, TermPolicy(sensitivity = Some(Sensitivity.Sensitive)))
    val axiom = Axiom.DataAssertion(alice, salary, Literal.decimal(BigDecimal(90000)))

    assertEquals(
      PolicyCascade.sensitivity(record(axiom, AxiomAnnotations.empty), book),
      Sensitivity.Sensitive
    )

  test("a module default applies when no term policy matches"):
    val book = PolicyBook.empty.withModule(ModuleDefaults("vf", Sensitivity.Sensitive))
    val axiom = Axiom.DataAssertion(alice, Iri("vf:balance"), Literal.decimal(BigDecimal(120)))

    assertEquals(
      PolicyCascade.sensitivity(record(axiom, AxiomAnnotations.empty), book),
      Sensitivity.Sensitive
    )

  test("unlabeled assertions default to personal, never public"):
    val axiom = Axiom.ObjectAssertion(alice, knows, marco)
    assertEquals(
      PolicyCascade.sensitivity(record(axiom, AxiomAnnotations.empty), PolicyBook.empty),
      Sensitivity.Personal
    )

  test("schema axioms default to public, since vocabulary is not data about anyone"):
    val axiom = Axiom.SubClassOf(Person, Agent)
    assertEquals(
      PolicyCascade.sensitivity(record(axiom, AxiomAnnotations.empty), PolicyBook.empty),
      Sensitivity.Public
    )

  test("escalation raises sensitivity but never lowers it"):
    val book = PolicyBook.empty.withProperty(
      Iri("crm:healthNote"),
      TermPolicy(sensitivity = Some(Sensitivity.Personal), escalateTo = Some(Sensitivity.Sensitive))
    )
    val axiom = Axiom.DataAssertion(marco, Iri("crm:healthNote"), Literal.string("allergic to nuts"))

    assertEquals(
      PolicyCascade.sensitivity(record(axiom, AxiomAnnotations.empty), book),
      Sensitivity.Sensitive
    )

  // ── Recall utility (SPEC §3.3.2) ──────────────────────────────────────────

  test("recall utility falls back through property policy then module weight"):
    val book = PolicyBook.empty
      .withProperty(birthday, TermPolicy.utility(0.9))
      .withModule(ModuleDefaults("vf", utilityWeight = 0.2))

    val birthdayAxiom = Axiom.DataAssertion(lia, birthday, Literal.date(PartialDate.monthDay(5, 12)))
    val ledgerAxiom = Axiom.DataAssertion(alice, Iri("vf:quantity"), Literal.decimal(BigDecimal(1)))

    assertEquals(PolicyCascade.recallUtility(record(birthdayAxiom, AxiomAnnotations.empty), book), 0.9)
    assertEquals(PolicyCascade.recallUtility(record(ledgerAxiom, AxiomAnnotations.empty), book), 0.2)

  test("an owner-set utility ignores behavioral boosts entirely"):
    val axiom = Axiom.ObjectAssertion(alice, knows, marco)
    val rec = record(axiom, AxiomAnnotations.ownerConfirmed.withUtility(0.3))
    val book = PolicyBook.empty.withSignals(axiom.id, Signals(ownerViews = 500, queryHits = 500))

    assertEquals(
      PolicyCascade.recallUtility(rec, book),
      0.3,
      "a slider the owner moved must not drift under use"
    )

  test("behavioral signals raise utility, and agent reads count far less than owner views"):
    val axiom = Axiom.ObjectAssertion(alice, knows, marco)
    val rec = record(axiom, AxiomAnnotations.empty)

    val base = PolicyCascade.recallUtility(rec, PolicyBook.empty)
    val ownerRead =
      PolicyCascade.recallUtility(rec, PolicyBook.empty.withSignals(axiom.id, Signals(ownerViews = 20)))
    val agentRead =
      PolicyCascade.recallUtility(rec, PolicyBook.empty.withSignals(axiom.id, Signals(agentReads = 20)))

    assert(ownerRead > base, "owner views should raise utility")
    assert(
      agentRead - base < (ownerRead - base) / 10,
      s"agent reads must be discounted far below owner reads (§12.10): owner=$ownerRead agent=$agentRead"
    )

  test("an upcoming occasion boosts utility, and the boost decays without reinforcement"):
    val axiom = Axiom.DataAssertion(lia, birthday, Literal.date(PartialDate.monthDay(5, 12)))
    val rec = record(axiom, AxiomAnnotations.empty)

    val fresh = PolicyCascade.recallUtility(
      rec,
      PolicyBook.empty.withSignals(axiom.id, Signals(upcomingOccasion = true))
    )
    val stale = PolicyCascade.recallUtility(
      rec,
      PolicyBook.empty
        .withSignals(axiom.id, Signals(upcomingOccasion = true, daysSinceReinforcement = 365))
    )

    assert(fresh > 0.5, "an upcoming occasion should raise utility above the default")
    assert(stale < fresh, "the boost should decay")
    assert(stale >= 0.5, "decay must not eat into the policy base")

  test("an active goal contributes its independent temporal boost"):
    assertEquals(PolicyCascade.temporalBoost(Signals.none), 0.0)
    assertEquals(PolicyCascade.temporalBoost(Signals(activeGoal = true)), 0.10)
    assertEquals(
      PolicyCascade.temporalBoost(Signals(upcomingOccasion = true, activeGoal = true)),
      0.25
    )

  test("internal without a knowledge scope is flagged as invalid"):
    val axiom = Axiom.ObjectAssertion(alice, worksAt, acme)
    val bad = record(axiom, at(Sensitivity.Internal))
    val good = record(axiom, at(Sensitivity.Internal, Set(orgAcme)))

    assert(PolicyCascade.validate(bad, PolicyBook.empty).nonEmpty)
    assertEquals(PolicyCascade.validate(good, PolicyBook.empty), Nil)

  test("truth confidence validation accepts both endpoints and reports either outside boundary"):
    val axiom = Axiom.ObjectAssertion(alice, knows, marco)
    def violations(confidence: Double) =
      PolicyCascade.validate(
        record(axiom, AxiomAnnotations(truthConfidence = Some(confidence))),
        PolicyBook.empty
      )

    assertEquals(violations(0.0), Nil)
    assertEquals(violations(1.0), Nil)
    assertEquals(
      violations(-0.01),
      List(s"${axiom.id.value} has truthConfidence outside [0,1]")
    )
    assertEquals(
      violations(1.01),
      List(s"${axiom.id.value} has truthConfidence outside [0,1]")
    )

  // ── Per-fact disclosure ───────────────────────────────────────────────────

  test("sensitive facts are withheld from every policy, including maximal grants"):
    val greedy = DisclosurePolicy("agent", Sensitivity.Sensitive, Set(orgAcme))
    assert(!greedy.permits(Sensitivity.Sensitive, Set.empty))

  test("a local policy sees everything, because no boundary is crossed"):
    assert(DisclosurePolicy.localOwner("ui").permits(Sensitivity.Sensitive, Set.empty))

  test("internal facts need a matching scope grant, not merely an internal grant"):
    val acmeGrant = DisclosurePolicy.internal("agent", Set(orgAcme))
    val otherGrant = DisclosurePolicy.internal("agent", Set(Iri("org:other")))

    assert(acmeGrant.permits(Sensitivity.Internal, Set(orgAcme)))
    assert(!otherGrant.permits(Sensitivity.Internal, Set(orgAcme)))
    assert(!acmeGrant.permits(Sensitivity.Internal, Set.empty), "scopeless internal is not grantable")

  test("a public-only policy sees public facts and nothing else"):
    val policy = DisclosurePolicy.publicOnly("agent")
    assert(policy.permits(Sensitivity.Public, Set.empty))
    assert(!policy.permits(Sensitivity.Personal, Set.empty))
    assert(!policy.permits(Sensitivity.Internal, Set(orgAcme)))

  test("a broader maximum level includes every lower level and scoped internal knowledge"):
    val policy = DisclosurePolicy("agent", Sensitivity.Personal, Set(orgAcme))
    assert(policy.permits(Sensitivity.Public, Set.empty))
    assert(policy.permits(Sensitivity.Internal, Set(orgAcme)))
    assert(policy.permits(Sensitivity.Personal, Set.empty))

  // ── The derived-fact rule (SPEC §3.3.1) ───────────────────────────────────

  test("within one justification the most restrictive premise governs (max over axioms)"):
    val subclass = Axiom.SubClassOf(Person, Agent)
    val assertion = Axiom.ClassAssertion(alice, Person)
    val state = stateOf(subclass -> at(Sensitivity.Public), assertion -> at(Sensitivity.Personal))
    val closure = Reasoner.closure(Projections.current(state))
    val resolver = new SupportResolver(state, PolicyBook.empty)

    val derived = Axiom.ClassAssertion(alice, Agent)
    val effective = Disclosure.effectiveLevel(closure.justificationsFor(derived), resolver)

    assertEquals(effective.map(_.level), Some(Sensitivity.Personal))

  test("a conclusion derivable from public facts alone is public, whatever other paths exist"):
    // Two derivations of `alice knows marco`:
    //   (a) friendOf ⊑ knows, from a PUBLIC friendOf assertion
    //   (b) symmetry of knows, from a SENSITIVE knows assertion
    // The spec is explicit: the public path wins.
    val subProperty = Axiom.SubPropertyOf(friendOf, knows)
    val symmetry = Axiom.SymmetricProperty(knows)
    val publicFriend = Axiom.ObjectAssertion(alice, friendOf, marco)
    val sensitiveKnows = Axiom.ObjectAssertion(marco, knows, alice)

    val state = stateOf(
      subProperty -> at(Sensitivity.Public),
      symmetry -> at(Sensitivity.Public),
      publicFriend -> at(Sensitivity.Public),
      sensitiveKnows -> at(Sensitivity.Sensitive)
    )
    val closure = Reasoner.closure(Projections.current(state))
    val resolver = new SupportResolver(state, PolicyBook.empty)
    val derived = Axiom.ObjectAssertion(alice, knows, marco)

    assertEquals(
      Disclosure.effectiveLevel(closure.justificationsFor(derived), resolver).map(_.level),
      Some(Sensitivity.Public)
    )
    assert(
      Disclosure.decide(derived, closure, resolver, DisclosurePolicy.publicOnly("agent")).isDisclosed,
      "the public derivation path should make this disclosable to a public-only agent"
    )

  test("a fact derivable only through a sensitive premise is redacted, with a reason"):
    val symmetry = Axiom.SymmetricProperty(knows)
    val sensitiveKnows = Axiom.ObjectAssertion(marco, knows, alice)
    val state = stateOf(symmetry -> at(Sensitivity.Public), sensitiveKnows -> at(Sensitivity.Sensitive))
    val closure = Reasoner.closure(Projections.current(state))
    val resolver = new SupportResolver(state, PolicyBook.empty)

    val derived = Axiom.ObjectAssertion(alice, knows, marco)
    Disclosure.decide(derived, closure, resolver, DisclosurePolicy.personal("agent")) match
      case decision @ DisclosureDecision.Redact(reason) =>
        assertEquals(reason, "requires sensitive")
        assertEquals(decision.marker, "[redacted]")
      case other                             => fail(s"expected redaction, got $other")

  test("internal scopes union within the chosen justification"):
    val chain =
      Axiom.PropertyChain(List(ChainStep(worksAt), ChainStep(worksAt, inverse = true)), colleagueOf)
    val irreflexive = Axiom.IrreflexiveProperty(colleagueOf)
    val aliceAt = Axiom.ObjectAssertion(alice, worksAt, acme)
    val marcoAt = Axiom.ObjectAssertion(marco, worksAt, acme)
    val otherOrg = Iri("org:beta")

    val state = stateOf(
      chain -> at(Sensitivity.Public),
      irreflexive -> at(Sensitivity.Public),
      aliceAt -> at(Sensitivity.Internal, Set(orgAcme)),
      marcoAt -> at(Sensitivity.Internal, Set(otherOrg))
    )
    val closure = Reasoner.closure(Projections.current(state))
    val resolver = new SupportResolver(state, PolicyBook.empty)
    val derived = Axiom.ObjectAssertion(alice, colleagueOf, marco)

    val effective = Disclosure
      .effectiveLevel(closure.justificationsFor(derived), resolver)
      .getOrElse(fail("derived fact had no effective disclosure level"))
    assertEquals(effective.level, Sensitivity.Internal)
    assertEquals(effective.scopes, Set(orgAcme, otherOrg))

    // Only a grant covering *both* contributing scopes discloses it.
    assert(!Disclosure.decide(derived, closure, resolver, DisclosurePolicy.internal("a", Set(orgAcme))).isDisclosed)
    assert(
      Disclosure
        .decide(derived, closure, resolver, DisclosurePolicy.internal("a", Set(orgAcme, otherOrg)))
        .isDisclosed
    )
    Disclosure.decide(
      derived,
      closure,
      resolver,
      DisclosurePolicy.internal("a", Set(orgAcme))
    ) match
      case DisclosureDecision.Redact(reason) =>
        assertEquals(reason, "requires internal(org:acme, org:beta)")
      case other => fail(s"expected a scoped redaction, got $other")

  test("a fact that is not entailed at all is redacted rather than reported as permitted"):
    val state = stateOf(Axiom.ClassAssertion(alice, Person) -> at(Sensitivity.Public))
    val closure = Reasoner.closure(Projections.current(state))
    val resolver = new SupportResolver(state, PolicyBook.empty)

    val decision = Disclosure.decide(
      Axiom.ClassAssertion(marco, Person),
      closure,
      resolver,
      DisclosurePolicy.localOwner("ui")
    )
    assert(!decision.isDisclosed)
    assertEquals(decision, DisclosureDecision.Redact("not entailed"))
    assertEquals(decision.marker, "[redacted]")

  test("incomplete provenance is never treated as a disclosure grant"):
    val fact = Axiom.ClassAssertion(alice, Person)
    val state = stateOf(fact -> at(Sensitivity.Public))
    val closure = Closure(
      Map(fact -> Set(Justification.incomplete)),
      iterations = 1,
      saturated = true
    )
    val resolver = new SupportResolver(state, PolicyBook.empty)
    val policy = DisclosurePolicy.localOwner("owner")

    assertEquals(
      Disclosure.decide(fact, closure, resolver, policy),
      DisclosureDecision.Redact("provenance incomplete")
    )
    val restricted = Disclosure.restrict(closure, resolver, policy)
    assert(!restricted.contains(fact))
    assert(!restricted.complete)
    assertEquals(
      restricted.incompleteReasons,
      Set("justification tracking limit reached")
    )

  test("partition reports both what is disclosed and what was withheld"):
    val publicFact = Axiom.ClassAssertion(alice, Person)
    val secretFact = Axiom.DataAssertion(alice, salary, Literal.decimal(BigDecimal(90000)))
    val state = stateOf(publicFact -> at(Sensitivity.Public), secretFact -> at(Sensitivity.Sensitive))
    val closure = Reasoner.closure(Projections.current(state))
    val resolver = new SupportResolver(state, PolicyBook.empty)

    val (disclosed, redacted) = Disclosure.partition(
      List(publicFact, secretFact),
      closure,
      resolver,
      DisclosurePolicy.publicOnly("agent")
    )

    assertEquals(disclosed.map(_._1), List(publicFact))
    assertEquals(redacted.map(_._1), List(secretFact))
    val disclosedDecision =
      Disclosure.decide(publicFact, closure, resolver, DisclosurePolicy.publicOnly("agent"))
    assertEquals(disclosedDecision.marker, "")

  test("an unresolvable premise fails closed, not open"):
    val resolver = new SupportResolver(KbState.empty, PolicyBook.empty)
    assertEquals(resolver.levelOf(Support.Asserted(AxiomId.unsafe("ax_missing")))._1, Sensitivity.Sensitive)
    assertEquals(resolver.levelOf(Support.FromFluent(FluentId.unsafe("fl_missing")))._1, Sensitivity.Sensitive)

  test("a fluent-backed triple is governed by the fluent's own sensitivity"):
    val fluentId = FluentId.unsafe("fl_1")
    val fluent = Fluent(
      fluentId,
      alice,
      worksAt,
      Node.Ref(acme),
      annotations = at(Sensitivity.Internal, Set(orgAcme))
    )
    val state = KbState.replay(List(JournalEntry(1L, Instant.EPOCH, Operation.OpenFluent(fluent))))
    val closure = Reasoner.closure(Projections.current(state))
    val resolver = new SupportResolver(state, PolicyBook.empty)

    val materialized = Axiom.ObjectAssertion(alice, worksAt, acme)
    val effective = Disclosure
      .effectiveLevel(closure.justificationsFor(materialized), resolver)
      .getOrElse(fail("materialized fluent had no effective disclosure level"))

    assertEquals(effective.level, Sensitivity.Internal)
    assertEquals(effective.scopes, Set(orgAcme))
