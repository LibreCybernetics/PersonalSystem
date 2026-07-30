package noesis.core.reason

import noesis.core.model.*

/** An indexed, read-only view of a set of justified facts, handed to each [[Rule]].
  *
  * Rules receive this rather than a raw map so a module-contributed rule (SPEC §5.1) gets the same
  * lookup performance as the built-ins without reimplementing indexing. Indices are lazy: a rule
  * that only reads class assertions never pays to index property chains.
  */
final class ClosureView(val facts: Map[Axiom, Set[Justification]]):

  def justificationsFor(axiom: Axiom): Set[Justification] = facts.getOrElse(axiom, Set.empty)

  def contains(axiom: Axiom): Boolean = facts.contains(axiom)

  private def collect[A](pf: PartialFunction[Axiom, A]): List[(A, Set[Justification])] =
    facts.iterator.collect { case (axiom, js) if pf.isDefinedAt(axiom) => (pf(axiom), js) }.toList

  // ── TBox / RBox indices ───────────────────────────────────────────────────

  lazy val subClassEdges: List[((Iri, Iri), Set[Justification])] =
    collect { case Axiom.SubClassOf(s, p) => (s, p) }

  lazy val subClassBySub: Map[Iri, List[(Iri, Set[Justification])]] =
    subClassEdges.groupMap(_._1._1)((e, js) => (e._2, js))

  lazy val subPropertyEdges: List[((Iri, Iri), Set[Justification])] =
    collect { case Axiom.SubPropertyOf(s, p) => (s, p) }

  lazy val subPropertyBySub: Map[Iri, List[(Iri, Set[Justification])]] =
    subPropertyEdges.groupMap(_._1._1)((e, js) => (e._2, js))

  lazy val domains: Map[Iri, List[(Iri, Set[Justification])]] =
    collect { case Axiom.PropertyDomain(p, c) => (p, c) }.groupMap(_._1._1)((e, js) => (e._2, js))

  lazy val ranges: Map[Iri, List[(Iri, Set[Justification])]] =
    collect { case Axiom.PropertyRange(p, c) => (p, c) }.groupMap(_._1._1)((e, js) => (e._2, js))

  lazy val symmetric: Map[Iri, Set[Justification]] =
    collect { case Axiom.SymmetricProperty(p) => p }.toMap

  lazy val transitive: Map[Iri, Set[Justification]] =
    collect { case Axiom.TransitiveProperty(p) => p }.toMap

  lazy val irreflexive: Map[Iri, Set[Justification]] =
    collect { case Axiom.IrreflexiveProperty(p) => p }.toMap

  lazy val inverses: List[((Iri, Iri), Set[Justification])] =
    collect { case Axiom.InverseProperties(a, b) => (a, b) }

  lazy val chains: List[((List[ChainStep], Iri), Set[Justification])] =
    collect { case Axiom.PropertyChain(steps, sup) => (steps, sup) }

  lazy val disjointClasses: List[((Iri, Iri), Set[Justification])] =
    collect { case Axiom.DisjointClasses(a, b) => (a, b) }

  lazy val timeVarying: Set[Iri] = collect { case Axiom.TimeVarying(p) => p }.map(_._1).toSet

  // ── ABox indices ──────────────────────────────────────────────────────────

  lazy val classAssertions: List[((Iri, Iri), Set[Justification])] =
    collect { case Axiom.ClassAssertion(i, c) => (i, c) }

  /** class IRI → its known instances. */
  lazy val instancesOf: Map[Iri, List[(Iri, Set[Justification])]] =
    classAssertions.groupMap(_._1._2)((e, js) => (e._1, js))

  /** individual IRI → the classes it is known to belong to. */
  lazy val classesOf: Map[Iri, List[(Iri, Set[Justification])]] =
    classAssertions.groupMap(_._1._1)((e, js) => (e._2, js))

  lazy val objectAssertions: List[((Iri, Iri, Iri), Set[Justification])] =
    collect { case Axiom.ObjectAssertion(s, p, o) => (s, p, o) }

  /** property IRI → (subject, object, justifications). */
  lazy val objectByProperty: Map[Iri, List[(Iri, Iri, Set[Justification])]] =
    objectAssertions.groupMap(_._1._2)((e, js) => (e._1, e._3, js))

  /** (subject, property) → (object, justifications) — the forward-traversal index. */
  lazy val objectBySubjectProperty: Map[(Iri, Iri), List[(Iri, Set[Justification])]] =
    objectAssertions.groupMap(e => (e._1._1, e._1._2))((e, js) => (e._3, js))

  lazy val dataAssertions: List[((Iri, Iri, Literal), Set[Justification])] =
    collect { case Axiom.DataAssertion(s, p, v) => (s, p, v) }

  lazy val dataByProperty: Map[Iri, List[(Iri, Literal, Set[Justification])]] =
    dataAssertions.groupMap(_._1._2)((e, js) => (e._1, e._3, js))

  lazy val sameIndividuals: List[((Iri, Iri), Set[Justification])] =
    collect { case Axiom.SameIndividual(a, b) => (a, b) }

  lazy val differentIndividuals: List[((Iri, Iri), Set[Justification])] =
    collect { case Axiom.DifferentIndividuals(a, b) => (a, b) }

  /** Traverses one chain step as a binary relation, honoring the inverse flag. */
  def relationFor(step: ChainStep): List[(Iri, Iri, Set[Justification])] =
    objectByProperty.getOrElse(step.property, Nil).map: (s, o, js) =>
      if step.inverse then (o, s, js) else (s, o, js)
