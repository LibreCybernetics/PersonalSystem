package noesis.core

import java.time.{Instant, LocalDate}

import munit.FunSuite
import noesis.core.Fixtures.*
import noesis.journal.{JournalEntry, Operation}
import noesis.logic.*
import noesis.core.projection.*
import noesis.reasoner.Support

/** Projections must be pure functions of the journal (SPEC §3.2). Each test here replays a specific
  * log and pins exactly what the resulting graph should contain.
  */
class ProjectionSuite extends FunSuite:

  private var seq = 0L
  private def entry(op: Operation): JournalEntry =
    seq += 1
    JournalEntry(seq, Instant.EPOCH.plusSeconds(seq), op)

  private def replay(ops: Operation*): KbState =
    seq = 0L
    KbState.replay(ops.map(entry))

  private val aliceIsPerson = Axiom.ClassAssertion(alice, Person)
  private val marcoIsPerson = Axiom.ClassAssertion(marco, Person)
  private val fl1 = FluentId.unsafe("fl_1")
  private val fl2 = FluentId.unsafe("fl_2")

  test("replay reconstructs asserted axioms with their annotations"):
    val annotations = AxiomAnnotations.ownerConfirmed.withUtility(0.9)
    val state = replay(Operation.Assert(aliceIsPerson.id, aliceIsPerson, annotations))

    assertEquals(state.axioms.size, 1)
    assertEquals(state.axiom(aliceIsPerson.id).map(_.axiom), Some(aliceIsPerson))
    assertEquals(state.axiom(aliceIsPerson.id).map(_.annotations), Some(annotations))
    assertEquals(state.axiom(aliceIsPerson.id).map(_.status), Some(AxiomStatus.Active))

  test("retraction removes an axiom from projections but keeps the record"):
    val state = replay(
      Operation.Assert(aliceIsPerson.id, aliceIsPerson),
      Operation.Retract(aliceIsPerson.id, Some("wrong"))
    )

    assertEquals(state.axiom(aliceIsPerson.id).map(_.status), Some(AxiomStatus.Retracted))
    assertEquals(state.activeAxioms.size, 0)
    assert(!Projections.asserted(state).contains(aliceIsPerson))

  test("re-asserting a retracted axiom revives it under the same id"):
    val state = replay(
      Operation.Assert(aliceIsPerson.id, aliceIsPerson),
      Operation.Retract(aliceIsPerson.id),
      Operation.Assert(aliceIsPerson.id, aliceIsPerson)
    )

    assertEquals(state.axioms.size, 1, "content-derived ids should not duplicate")
    assertEquals(state.activeAxioms.size, 1)

  test("disputed axioms are excluded from reasoning but remain recorded"):
    val state = replay(
      Operation.Assert(aliceIsPerson.id, aliceIsPerson),
      Operation.Dispute(aliceIsPerson.id, Some("conflicts"))
    )

    assertEquals(state.axiom(aliceIsPerson.id).map(_.status), Some(AxiomStatus.Disputed))
    assert(!Projections.asserted(state).contains(aliceIsPerson), "disputed must not reach the graph")

    val restored = KbState.step(state, entry(Operation.Undispute(aliceIsPerson.id)))
    assert(Projections.asserted(restored).contains(aliceIsPerson))

  test("annotate patches only the named dimensions"):
    val start = AxiomAnnotations(
      truthConfidence = Some(0.5),
      sensitivity = Some(Sensitivity.Personal),
      recallUtility = Some(0.2)
    )
    val state = replay(
      Operation.Assert(aliceIsPerson.id, aliceIsPerson, start),
      Operation.Annotate(aliceIsPerson.id, AnnotationPatch(recallUtility = Patch.of(0.95)))
    )

    val annotations =
      state.axiom(aliceIsPerson.id).map(_.annotations).getOrElse(fail("axiom not projected"))
    assertEquals(annotations.recallUtility, Some(0.95))
    assertEquals(annotations.truthConfidence, Some(0.5), "untouched dimension changed")
    assertEquals(annotations.sensitivity, Some(Sensitivity.Personal))

  test("reclassify sets sensitivity and scope together"):
    val state = replay(
      Operation.Assert(aliceIsPerson.id, aliceIsPerson),
      Operation.Reclassify(aliceIsPerson.id, Sensitivity.Internal, Set(orgAcme))
    )

    val annotations =
      state.axiom(aliceIsPerson.id).map(_.annotations).getOrElse(fail("axiom not projected"))
    assertEquals(annotations.sensitivity, Some(Sensitivity.Internal))
    assertEquals(annotations.knowledgeScope, Set(orgAcme))

  test("time travel: replaying a prefix yields the state as of that sequence"):
    val entries = List(
      Operation.Assert(aliceIsPerson.id, aliceIsPerson),
      Operation.Assert(marcoIsPerson.id, marcoIsPerson),
      Operation.Retract(aliceIsPerson.id)
    ).zipWithIndex.map((op, i) => JournalEntry(i + 1L, Instant.EPOCH, op))

    assertEquals(KbState.replayUntil(entries, 1).activeAxioms.size, 1)
    assertEquals(KbState.replayUntil(entries, 2).activeAxioms.size, 2)
    assertEquals(KbState.replayUntil(entries, 3).activeAxioms.size, 1)
    assertEquals(KbState.replay(entries).seq, 3L)

  // ── Fluents (SPEC §3.6) ───────────────────────────────────────────────────

  test("an ongoing fluent materializes into the current graph as a plain triple"):
    val fluent = Fluent(fl1, alice, worksAt, Node.Ref(acme), Some(PartialDate.of(2026, 1, 1)))
    val state = replay(Operation.OpenFluent(fluent))
    val expected = Axiom.ObjectAssertion(alice, worksAt, acme)

    assert(Projections.current(state).contains(expected))
    assert(
      !Projections.asserted(state).contains(expected),
      "the asserted graph must not include fluent-derived triples"
    )
    assertEquals(Projections.current(state).supportFor(expected), Set[Support](Support.FromFluent(fl1)))

  test("a closed fluent drops out of the current graph"):
    val fluent = Fluent(fl1, alice, worksAt, Node.Ref(acme), Some(PartialDate.of(2026, 1, 1)))
    val state = replay(
      Operation.OpenFluent(fluent),
      Operation.CloseFluent(fl1, Some(PartialDate.of(2026, 7, 1)), EndReason.Ended)
    )

    assert(!Projections.current(state).contains(Axiom.ObjectAssertion(alice, worksAt, acme)))
    assertEquals(state.fluent(fl1).map(_.endReason), Some(Some(EndReason.Ended)))
    assertEquals(state.fluent(fl1).flatMap(_.validTo), Some(PartialDate.of(2026, 7, 1)))

  test("supersession closes the old state, opens the new one, and links them"):
    val old = Fluent(fl1, alice, worksAt, Node.Ref(acme), Some(PartialDate.of(2026, 1, 1)))
    val replacement = Fluent(fl2, alice, worksAt, Node.Ref(molina))
    val boundary = PartialDate.of(2026, 7, 1)

    val state = replay(
      Operation.OpenFluent(old),
      Operation.SupersedeFluent(fl1, replacement, Some(boundary))
    )

    val closed = state.fluent(fl1).getOrElse(fail("superseded fluent not projected"))
    assertEquals(closed.validTo, Some(boundary))
    assertEquals(closed.endReason, Some(EndReason.Superseded))
    assertEquals(closed.supersededBy, Some(fl2))

    // The replacement inherits the boundary date, so there is neither a gap nor an overlap.
    assertEquals(state.fluent(fl2).flatMap(_.validFrom), Some(boundary))

    val current = Projections.current(state)
    assert(current.contains(Axiom.ObjectAssertion(alice, worksAt, molina)), "new value missing")
    assert(!current.contains(Axiom.ObjectAssertion(alice, worksAt, acme)), "old value still current")

  test("point-in-time projection returns the state that held on a past date"):
    val old = Fluent(
      fl1,
      alice,
      worksAt,
      Node.Ref(acme),
      validFrom = Some(PartialDate.of(2026, 1, 1)),
      validTo = Some(PartialDate.of(2026, 7, 1))
    )
    val current = Fluent(fl2, alice, worksAt, Node.Ref(molina), Some(PartialDate.of(2026, 7, 1)))
    val state = replay(Operation.OpenFluent(old), Operation.OpenFluent(current))

    val march = Projections.asOf(state, LocalDate.of(2026, 3, 15))
    assert(march.contains(Axiom.ObjectAssertion(alice, worksAt, acme)))
    assert(!march.contains(Axiom.ObjectAssertion(alice, worksAt, molina)))

    val august = Projections.asOf(state, LocalDate.of(2026, 8, 15))
    assert(august.contains(Axiom.ObjectAssertion(alice, worksAt, molina)))
    assert(!august.contains(Axiom.ObjectAssertion(alice, worksAt, acme)))

  test("a fluent with an unknown start still holds now"):
    val fluent = Fluent(fl1, alice, worksAt, Node.Ref(acme), validFrom = None)
    assert(fluent.heldOn(LocalDate.of(2026, 7, 29)), "absent validFrom means unknown, not 'never'")
    assert(fluent.isOngoing)

  test("openFluentsFor finds the matching state and ignores closed and unrelated ones"):
    val state = replay(
      Operation.OpenFluent(Fluent(fl1, alice, worksAt, Node.Ref(acme))),
      Operation.OpenFluent(Fluent(fl2, marco, worksAt, Node.Ref(molina))),
      Operation.CloseFluent(fl2, Some(PartialDate.of(2026, 1, 1)), EndReason.Ended)
    )

    assertEquals(state.openFluentsFor(alice, worksAt).map(_.id), List(fl1))
    assertEquals(state.openFluentsFor(marco, worksAt), Nil)
    assertEquals(state.closedFluentsFor(marco, worksAt).map(_.id), List(fl2))
    assertEquals(state.openFluentsFor(alice, worksAt, Some(Node.Ref(molina))), Nil)

  test("entities include fluent subjects and values, not just asserted individuals"):
    val state = replay(
      Operation.Assert(marcoIsPerson.id, marcoIsPerson),
      Operation.OpenFluent(Fluent(fl1, alice, worksAt, Node.Ref(acme)))
    )
    assertEquals(state.entities, Set(marco, alice, acme))
