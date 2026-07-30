package noesis.core

import cats.data.NonEmptyList
import cats.effect.IO
import munit.CatsEffectSuite
import noesis.core.Fixtures.*
import noesis.core.capture.Intent
import noesis.core.event.Event
import noesis.core.kb.*
import noesis.core.model.*
import noesis.core.query.Query
import noesis.core.verbalize.{Naming, Templates}

/** End-to-end tests of the Knowledge Core service (SPEC §3.5, §3.6, §3.8).
  *
  * These are the tests that hold the headline invariant: nothing enters the journal without passing
  * validation, and the journal is the only thing that is written.
  */
class KnowledgeBaseSuite extends CatsEffectSuite:

  private def schemaLoaded: IO[KnowledgeBase[IO]] =
    for
      base <- kb()
      _ <- base.commit(NonEmptyList.fromListUnsafe(crmSchema.map(Intent.Assert(_))))
    yield base

  test("a committed assertion is journaled and visible in the current graph"):
    val axiom = Axiom.ClassAssertion(alice, Person)
    for
      base <- kb()
      result <- base.assert(axiom)
      graph <- base.currentGraph
      entries <- base.journal.stream.compile.toList
    yield
      assert(result.isRight, result.toString)
      assert(graph.contains(axiom))
      assertEquals(entries.length, 1)

  test("the state cache is invalidated by a commit"):
    for
      base <- kb()
      _ <- base.assert(Axiom.ClassAssertion(alice, Person))
      firstCount <- base.state.map(_.activeAxioms.size)
      _ <- base.assert(Axiom.ClassAssertion(marco, Person))
      secondCount <- base.state.map(_.activeAxioms.size)
    yield
      assertEquals(firstCount, 1)
      assertEquals(secondCount, 2, "a stale cache would still report 1")

  test("an inconsistent commit is rejected and leaves the journal untouched"):
    for
      base <- kb()
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.DisjointClasses(Person, Organization)),
          Intent.Assert(Axiom.ClassAssertion(alice, Person))
        )
      )
      before <- base.journal.stream.compile.toList
      rejected <- base.assert(Axiom.ClassAssertion(alice, Organization))
      after <- base.journal.stream.compile.toList
    yield
      rejected match
        case Left(CommitRejected.Inconsistent(problems)) =>
          assert(problems.nonEmpty)
          assert(problems.head.justification.premises.nonEmpty, "rejection must carry a justification")
        case other => fail(s"expected an inconsistency rejection, got $other")
      assertEquals(after.length, before.length, "a rejected commit must not write to the journal")

  test("an inconsistency reachable only through inference is still caught pre-commit"):
    for
      base <- kb()
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.DisjointClasses(Person, Organization)),
          Intent.Assert(Axiom.PropertyRange(worksAt, Organization)),
          Intent.Assert(Axiom.ClassAssertion(alice, Person))
        )
      )
      // marco worksAt alice ⟹ alice : Organization, contradicting alice : Person
      rejected <- base.assert(Axiom.ObjectAssertion(marco, worksAt, alice))
    yield assert(rejected.isLeft, s"expected rejection, got $rejected")

  test("a whole bundle is rejected together, not partially applied"):
    for
      base <- kb()
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.DisjointClasses(Person, Organization)),
          Intent.Assert(Axiom.ClassAssertion(alice, Person))
        )
      )
      before <- base.state.map(_.activeAxioms.size)
      rejected <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(marco, Person)),      // fine on its own
          Intent.Assert(Axiom.ClassAssertion(alice, Organization)) // makes the bundle inconsistent
        )
      )
      after <- base.state.map(_.activeAxioms.size)
    yield
      assert(rejected.isLeft)
      assertEquals(after, before, "the innocent half of the bundle leaked through")

  test("committing an intent that names a missing axiom reports the problem"):
    for
      base <- kb()
      result <- base.commit(NonEmptyList.one(Intent.Retract(AxiomId.unsafe("ax_nope"))))
    yield result match
      case Left(CommitRejected.NotCaptured(problems)) =>
        assert(problems.head.detail.contains("no such axiom"), problems.head.detail)
      case other => fail(s"expected a capture problem, got $other")

  // ── Fluent sugar (SPEC §3.6) ──────────────────────────────────────────────

  test("a plain assertion on a time-varying property silently opens a fluent"):
    for
      base <- schemaLoaded
      _ <- base.assert(Axiom.ObjectAssertion(alice, worksAt, acme))
      state <- base.state
      graph <- base.currentGraph
    yield
      assertEquals(state.ongoingFluents.size, 1, "the sugar should have created a fluent")
      assertEquals(
        state.axioms.values.count(_.axiom == Axiom.ObjectAssertion(alice, worksAt, acme)),
        0,
        "a time-varying assertion must not also be stored as a bare axiom"
      )
      assert(graph.contains(Axiom.ObjectAssertion(alice, worksAt, acme)), "not materialized")

  test("a non-time-varying property is asserted directly, with no fluent"):
    for
      base <- schemaLoaded
      _ <- base.assert(Axiom.ObjectAssertion(sarah, spouseOf, marco))
      state <- base.state
    yield
      assertEquals(state.ongoingFluents.size, 0)
      assert(state.axioms.values.exists(_.axiom == Axiom.ObjectAssertion(sarah, spouseOf, marco)))

  test("re-asserting the same current value is a no-op, not a second fluent"):
    for
      base <- schemaLoaded
      _ <- base.assert(Axiom.ObjectAssertion(alice, worksAt, acme))
      seqBefore <- base.journal.lastSeq
      result <- base.assert(Axiom.ObjectAssertion(alice, worksAt, acme))
      seqAfter <- base.journal.lastSeq
      state <- base.state
    yield
      assert(result.isRight, result.toString)
      assertEquals(seqAfter, seqBefore, "a redundant capture should not append to the journal")
      assertEquals(state.ongoingFluents.size, 1)

  test("asserting a new value for an open time-varying property supersedes rather than duplicating"):
    for
      base <- schemaLoaded
      _ <- base.assert(Axiom.ObjectAssertion(alice, worksAt, acme))
      _ <- base.assert(Axiom.ObjectAssertion(alice, worksAt, molina))
      state <- base.state
      graph <- base.currentGraph
    yield
      assertEquals(state.ongoingFluents.size, 1, "two simultaneous current employers")
      assertEquals(state.fluents.size, 2)
      assert(graph.contains(Axiom.ObjectAssertion(alice, worksAt, molina)))
      assert(!graph.contains(Axiom.ObjectAssertion(alice, worksAt, acme)))

  test("closing a state that was never open is refused with a helpful message"):
    for
      base <- schemaLoaded
      result <- base.commit(NonEmptyList.one(Intent.CloseState(alice, worksAt)))
    yield result match
      case Left(CommitRejected.NotCaptured(problems)) =>
        assert(problems.head.detail.contains("no state to close"), problems.head.detail)
      case other => fail(s"expected refusal, got $other")

  test("closing after a state has already been closed mentions the historical fluent"):
    for
      base <- schemaLoaded
      _ <- base.assert(Axiom.ObjectAssertion(alice, worksAt, acme))
      _ <- base.commit(
        NonEmptyList.one(Intent.CloseState(alice, worksAt, validTo = Some(PartialDate.of(2026, 7, 1))))
      )
      result <- base.commit(NonEmptyList.one(Intent.CloseState(alice, worksAt)))
    yield result match
      case Left(CommitRejected.NotCaptured(problems)) =>
        assert(problems.head.detail.contains("closed one(s) exist"), problems.head.detail)
      case other => fail(s"expected refusal referencing the closed fluent, got $other")

  test("a bundle can close one state and open another, seeing its own earlier effects"):
    for
      base <- schemaLoaded
      _ <- base.assert(Axiom.ObjectAssertion(alice, worksAt, acme))
      result <- base.commit(
        NonEmptyList.of(
          Intent.CloseState(alice, worksAt, validTo = Some(PartialDate.of(2026, 7, 1))),
          Intent.OpenState(
            alice,
            worksAt,
            Node.Ref(molina),
            validFrom = Some(PartialDate.of(2026, 7, 2))
          )
        )
      )
      graph <- base.currentGraph
      state <- base.state
    yield
      assert(result.isRight, result.toString)
      assertEquals(state.ongoingFluents.size, 1)
      assert(graph.contains(Axiom.ObjectAssertion(alice, worksAt, molina)))

  test("supersession emits one state.changed carrying both the old and new value"):
    for
      base <- schemaLoaded
      _ <- base.assert(Axiom.ObjectAssertion(alice, worksAt, acme))
      result <- base.commit(
        NonEmptyList.one(Intent.Supersede(alice, worksAt, Node.Ref(molina), Some(PartialDate.of(2026, 7, 1))))
      )
    yield
      val events = result.fold(r => fail(r.render), _.events)
      events.collectFirst { case e: Event.StateChanged => e } match
        case Some(changed) =>
          assertEquals(changed.previous, Some(Node.Ref(acme)))
          assertEquals(changed.current, Some(Node.Ref(molina)))
          assertEquals(changed.property, worksAt)
        case None => fail(s"expected a state.changed event, got ${events.map(_.name)}")

  test("point-in-time queries answer about a past employer"):
    for
      base <- schemaLoaded
      _ <- base.commit(
        NonEmptyList.one(
          Intent.OpenState(alice, worksAt, Node.Ref(acme), Some(PartialDate.of(2026, 1, 1)))
        )
      )
      _ <- base.commit(
        NonEmptyList.one(Intent.Supersede(alice, worksAt, Node.Ref(molina), Some(PartialDate.of(2026, 7, 1))))
      )
      march <- base.graphAsOf(java.time.LocalDate.of(2026, 3, 1))
      now <- base.currentGraph
    yield
      assert(march.contains(Axiom.ObjectAssertion(alice, worksAt, acme)), "March should show Acme")
      assert(now.contains(Axiom.ObjectAssertion(alice, worksAt, molina)), "now should show Molina")

  // ── Events (SPEC §2) ──────────────────────────────────────────────────────

  test("a commit emits axiom.added and entailment.changed"):
    for
      base <- schemaLoaded
      result <- base.assert(Axiom.ClassAssertion(alice, Person))
    yield
      val names = result.fold(r => fail(r.render), _.events.map(_.name)).toSet
      assert(names.contains("axiom.added"), names.toString)
      assert(
        names.contains("entailment.changed"),
        s"Person ⊑ Agent should produce a new entailment: $names"
      )

  test("retraction emits axiom.retracted"):
    val axiom = Axiom.ClassAssertion(alice, Person)
    for
      base <- kb()
      _ <- base.assert(axiom)
      result <- base.commit(NonEmptyList.one(Intent.Retract(axiom.id)))
    yield assert(result.fold(r => fail(r.render), _.events.map(_.name)).contains("axiom.retracted"))

  // ── Reasoning services (SPEC §3.4, §3.8) ──────────────────────────────────

  test("entails and explain agree, and an explanation names its premises"):
    for
      base <- kb()
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.SubClassOf(Person, Agent)),
          Intent.Assert(Axiom.ClassAssertion(alice, Person))
        )
      )
      derived = Axiom.ClassAssertion(alice, Agent)
      entailed <- base.entails(derived)
      explanation <- base.explain(derived)
    yield
      assert(entailed)
      val justification = explanation.get.justifications.head
      assertEquals(justification.size, 2)
      assert(explanation.get.isDerived, "an entailment should not be reported as asserted")

  test("query over the knowledge base sees fluent-backed and inferred facts together"):
    for
      base <- schemaLoaded
      _ <- base.assert(Axiom.ObjectAssertion(alice, worksAt, acme))
      _ <- base.assert(Axiom.ObjectAssertion(marco, worksAt, acme))
      bgp = Query.parse("noesis:e/alice crm:colleagueOf ?whom").fold(fail(_), identity)
      solutions <- base.query(bgp)
    yield assertEquals(solutions.flatMap(_.get("whom")), List(Node.Ref(marco)))

  test("committing a non-EL axiom succeeds but reports a profile warning"):
    for
      base <- kb()
      result <- base.assert(Axiom.SymmetricProperty(knows))
    yield
      val warnings = result.fold(r => fail(r.render), _.profileWarnings)
      assertEquals(warnings.length, 1)
      assert(warnings.head._2.contains("EL"), warnings.head._2)

  test("time travel: stateAt reconstructs an earlier journal prefix"):
    for
      base <- kb()
      first <- base.assert(Axiom.ClassAssertion(alice, Person))
      _ <- base.assert(Axiom.ClassAssertion(marco, Person))
      firstSeq = first.fold(r => fail(r.render), _.commit.entries.head.seq)
      earlier <- base.stateAt(firstSeq)
      latest <- base.state
    yield
      assertEquals(earlier.activeAxioms.size, 1)
      assertEquals(latest.activeAxioms.size, 2)

  // ── Verbalization (SPEC §5.2, §7.2) ───────────────────────────────────────

  test("verbalization falls back to a humanized property name"):
    for
      base <- kb()
      _ <- base.assert(Axiom.ObjectAssertion(alice, worksAt, acme))
      text <- base.verbalize(Axiom.ObjectAssertion(alice, worksAt, acme))
    yield assert(text.contains("works at"), text)

  test("a module template overrides the fallback rendering"):
    val templates = Templates.empty.withProperty(birthday, "{s}'s birthday is {o}")
    for
      base <- kb(KbConfig.default.withTemplates(templates))
      _ <- base.assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Lía")))
      text <- base.verbalize(
        Axiom.DataAssertion(lia, birthday, Literal.Date(PartialDate.monthDay(5, 12)))
      )
    yield assertEquals(text, "Lía's birthday is --05-12")

  test("verbalization uses the current name after a rename, not the former one"):
    // SPEC §7.2: the verbalizer always uses the current name, including about past periods.
    for
      base <- kb(KbConfig.default.copy(namingProperties = List(hasName, Vocab.label)))
      _ <- base.assert(Axiom.TimeVarying(hasName))
      _ <- base.commit(
        NonEmptyList.one(Intent.OpenState(alice, hasName, Node.Lit(Literal.string("Adam"))))
      )
      _ <- base.commit(
        NonEmptyList.one(
          Intent.Supersede(alice, hasName, Node.Lit(Literal.string("Alice")), Some(PartialDate.of(2026, 5, 1)))
        )
      )
      text <- base.verbalize(Axiom.ObjectAssertion(alice, worksAt, acme))
    yield
      assert(text.startsWith("Alice"), s"expected the current name, got: $text")
      assert(!text.contains("Adam"), s"a former name leaked into verbalization: $text")

  test("naming prefers an ongoing fluent over a plain label assertion"):
    for
      base <- kb(KbConfig.default.copy(namingProperties = List(hasName, Vocab.label)))
      _ <- base.assert(Axiom.TimeVarying(hasName))
      _ <- base.assert(Axiom.DataAssertion(marco, Vocab.label, Literal.string("stale label")))
      _ <- base.commit(
        NonEmptyList.one(Intent.OpenState(marco, hasName, Node.Lit(Literal.string("Marco"))))
      )
      state <- base.state
    yield assertEquals(Naming.from(state, List(hasName, Vocab.label)).label(marco), "Marco")

  test("an unnamed opaque entity renders as a short handle rather than a raw UUID"):
    for
      base <- kb()
      state <- base.state
    yield
      val label = Naming.from(state).label(Iri("noesis:e/0123456789abcdef"))
      assert(label.startsWith("⟨") && label.length < 15, label)

  // ── Annotations through the service ───────────────────────────────────────

  test("effective annotations resolve through the cascade, not the raw override"):
    import noesis.core.policy.{ModuleDefaults, PolicyBook, TermPolicy}
    val book = PolicyBook.empty
      .withProperty(birthday, TermPolicy.utility(0.9))
      .withModule(ModuleDefaults("crm", noesis.core.model.Sensitivity.Personal))
    val axiom = Axiom.DataAssertion(lia, birthday, Literal.Date(PartialDate.monthDay(5, 12)))

    for
      base <- kb(KbConfig.default.withPolicies(book))
      _ <- base.assert(axiom)
      effective <- base.effectiveAnnotations(axiom.id)
    yield
      assertEquals(effective.map(_.recallUtility), Some(0.9))
      assertEquals(effective.map(_.sensitivity), Some(noesis.core.model.Sensitivity.Personal))
      assertEquals(effective.map(_.truthConfidence), Some(1.0))

  test("policyViolations reports an internal axiom with no knowledge scope"):
    val axiom = Axiom.ObjectAssertion(alice, worksAt, acme)
    for
      base <- kb()
      _ <- base.assert(axiom, AxiomAnnotations.ownerConfirmed.withSensitivity(Sensitivity.Internal))
      violations <- base.policyViolations
    yield assert(violations.exists(_.contains("knowledgeScope")), violations.toString)
