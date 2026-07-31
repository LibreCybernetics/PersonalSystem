package dev.librecybernetics.noesis.reasoner

import dev.librecybernetics.noesis.logic.*

/** A detected contradiction, with the justification the UI needs to explain it (SPEC §3.4).
  *
  * The `justification` field is what makes the contradiction actionable — "conflicts with: *A
  * worksAt Acme*, added 2025-03-02" — rather than a bare rejection the owner cannot act on.
  */
final case class Inconsistency(
    kind: InconsistencyKind,
    detail: String,
    conflicting: Set[Axiom],
    justification: Justification
):
  def render: String =
    val premises = justification.premises.toList.sorted.map(_.render).mkString(", ")
    s"[${kind.label}] $detail (from: $premises)"

enum InconsistencyKind(val label: String):
  case DisjointClassMembership extends InconsistencyKind("disjoint-classes")
  case SameAndDifferent extends InconsistencyKind("same-and-different")
  case IrreflexiveSelfLoop extends InconsistencyKind("irreflexive-self-loop")

/** Consistency checking over a closure.
  *
  * Runs on every commit against a scratch copy (SPEC §3.5.4), so an inconsistent commit is rejected
  * with a justification instead of being written and cleaned up later. That ordering is what keeps
  * the journal-is-truth invariant honest: the journal never contains a state the reasoner rejects.
  */
object Consistency:

  def check(closure: Closure): List[Inconsistency] =
    val view = closure.view
    disjointViolations(view) ++ sameAndDifferent(view) ++ irreflexiveViolations(view)

  def isConsistent(closure: Closure): Boolean = check(closure).isEmpty

  /** An individual belonging to two classes declared disjoint. */
  private def disjointViolations(view: ClosureView): List[Inconsistency] =
    for
      ((left, right), jDisjoint) <- view.disjointClasses
      (individual, jLeft) <- view.instancesOf.getOrElse(left, Nil)
      jRight <- view.justificationsFor(Axiom.ClassAssertion(individual, right)).toList
      combined = jDisjoint.headOption.getOrElse(Justification.empty)
    yield Inconsistency(
      kind = InconsistencyKind.DisjointClassMembership,
      detail = s"${individual.display} is both ${left.local} and ${right.local}, which are disjoint",
      conflicting = Set(
        Axiom.ClassAssertion(individual, left),
        Axiom.ClassAssertion(individual, right),
        Axiom.DisjointClasses(left, right)
      ),
      justification = combined
        .merge(jLeft.toList.sorted.headOption.getOrElse(Justification.empty))
        .merge(jRight)
    )

  /** Two individuals asserted both identical and distinct. */
  private def sameAndDifferent(view: ClosureView): List[Inconsistency] =
    for
      ((a, b), jSame) <- view.sameIndividuals
      jDiff <- (view.justificationsFor(Axiom.DifferentIndividuals(a, b)) ++
        view.justificationsFor(Axiom.DifferentIndividuals(b, a))).toList
      jS <- jSame.toList.sorted.headOption.toList
    yield Inconsistency(
      kind = InconsistencyKind.SameAndDifferent,
      detail = s"${a.display} is asserted both the same as and different from ${b.display}",
      conflicting = Set(Axiom.SameIndividual(a, b), Axiom.DifferentIndividuals(a, b)),
      justification = jS.merge(jDiff)
    )

  /** A self-loop on a property declared irreflexive. */
  private def irreflexiveViolations(view: ClosureView): List[Inconsistency] =
    for
      (property, jIrr) <- view.irreflexive.toList
      (subject, obj, jAssertion) <- view.objectByProperty.getOrElse(property, Nil)
      if subject == obj
      jI <- jIrr.toList.sorted.headOption.toList
      jA <- jAssertion.toList.sorted.headOption.toList
    yield Inconsistency(
      kind = InconsistencyKind.IrreflexiveSelfLoop,
      detail = s"${subject.display} ${property.local} itself, but ${property.local} is irreflexive",
      conflicting = Set(Axiom.ObjectAssertion(subject, property, obj)),
      justification = jI.merge(jA)
    )

