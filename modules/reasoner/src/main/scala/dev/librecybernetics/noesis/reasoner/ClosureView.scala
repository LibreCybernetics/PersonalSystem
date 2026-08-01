package dev.librecybernetics.noesis.reasoner

import dev.librecybernetics.noesis.logic.*

object ClosureView:
  /** Named tuples make these short-lived index rows readable without introducing wrapper types.
    * They are implementation-shaped data; durable and domain-level records remain case classes
    * (Scala 3 Reference: named tuples).
    */
  type Supported[A] = (value: A, justifications: Set[Justification])
  type Edge = (sub: Iri, sup: Iri)
  type ClassFact = (individual: Iri, cls: Iri)
  type ObjectFact = (subject: Iri, property: Iri, obj: Iri)
  type DataFact = (subject: Iri, property: Iri, literal: Literal)
  type ObjectRow = (subject: Iri, obj: Iri, justifications: Set[Justification])
  type DataRow = (subject: Iri, literal: Literal, justifications: Set[Justification])
  type ObjectValue = (obj: Iri, justifications: Set[Justification])
  type DataValue = (literal: Literal, justifications: Set[Justification])
  type Relation = (from: Iri, to: Iri, justifications: Set[Justification])

/** An indexed, read-only view of a set of justified facts, handed to each [[Rule]].
  *
  * Rules receive this rather than a raw map so a module-contributed rule (SPEC §5.1) gets the same
  * lookup performance as the built-ins without reimplementing indexing. Indices are lazy: a rule
  * that only reads class assertions never pays to index property chains.
  */
final class ClosureView(val facts: Map[Axiom, Set[Justification]]):
  import ClosureView.*

  def justificationsFor(axiom: Axiom): Set[Justification] = facts.getOrElse(axiom, Set.empty)

  def contains(axiom: Axiom): Boolean = facts.contains(axiom)

  private def supported[A](value: A, justifications: Set[Justification]): Supported[A] =
    (value = value, justifications = justifications)

  private def collect[A](pf: PartialFunction[Axiom, A]): List[Supported[A]] =
    facts.iterator
      .flatMap((axiom, js) => pf.lift(axiom).map(value => supported(value, js)))
      .toList

  // ── TBox / RBox indices ───────────────────────────────────────────────────

  lazy val subClassEdges: List[Supported[Edge]] =
    collect { case Axiom.SubClassOf(s, p) => (sub = s, sup = p) }

  lazy val subClassBySub: Map[Iri, List[Supported[Iri]]] =
    subClassEdges.groupMap(_.value.sub)(entry =>
      supported(entry.value.sup, entry.justifications)
    )

  lazy val subPropertyEdges: List[Supported[Edge]] =
    collect { case Axiom.SubPropertyOf(s, p) => (sub = s, sup = p) }

  lazy val subPropertyBySub: Map[Iri, List[Supported[Iri]]] =
    subPropertyEdges.groupMap(_.value.sub)(entry =>
      supported(entry.value.sup, entry.justifications)
    )

  lazy val domains: Map[Iri, List[Supported[Iri]]] =
    collect[Edge] { case Axiom.PropertyDomain(p, c) => (sub = p, sup = c) }
      .groupMap(_.value.sub)(entry => supported(entry.value.sup, entry.justifications))

  lazy val ranges: Map[Iri, List[Supported[Iri]]] =
    collect[Edge] { case Axiom.PropertyRange(p, c) => (sub = p, sup = c) }
      .groupMap(_.value.sub)(entry => supported(entry.value.sup, entry.justifications))

  lazy val symmetric: Map[Iri, Set[Justification]] =
    collect { case Axiom.SymmetricProperty(p) => p }
      .map(entry => entry.value -> entry.justifications)
      .toMap

  lazy val transitive: Map[Iri, Set[Justification]] =
    collect { case Axiom.TransitiveProperty(p) => p }
      .map(entry => entry.value -> entry.justifications)
      .toMap

  lazy val irreflexive: Map[Iri, Set[Justification]] =
    collect { case Axiom.IrreflexiveProperty(p) => p }
      .map(entry => entry.value -> entry.justifications)
      .toMap

  lazy val inverses: List[Supported[Edge]] =
    collect { case Axiom.InverseProperties(a, b) => (sub = a, sup = b) }

  lazy val chains: List[Supported[(steps: List[ChainStep], sup: Iri)]] =
    collect { case Axiom.PropertyChain(steps, sup) => (steps = steps, sup = sup) }

  lazy val disjointClasses: List[Supported[Edge]] =
    collect { case Axiom.DisjointClasses(a, b) => (sub = a, sup = b) }

  lazy val timeVarying: Set[Iri] =
    collect { case Axiom.TimeVarying(p) => p }.map(_.value).toSet

  // ── ABox indices ──────────────────────────────────────────────────────────

  lazy val classAssertions: List[Supported[ClassFact]] =
    collect { case Axiom.ClassAssertion(i, c) => (individual = i, cls = c) }

  /** class IRI → its known instances. */
  lazy val instancesOf: Map[Iri, List[Supported[Iri]]] =
    classAssertions.groupMap(_.value.cls)(entry =>
      supported(entry.value.individual, entry.justifications)
    )

  /** individual IRI → the classes it is known to belong to. */
  lazy val classesOf: Map[Iri, List[Supported[Iri]]] =
    classAssertions.groupMap(_.value.individual)(entry =>
      supported(entry.value.cls, entry.justifications)
    )

  lazy val objectAssertions: List[Supported[ObjectFact]] =
    collect { case Axiom.ObjectAssertion(s, p, o) =>
      (subject = s, property = p, obj = o)
    }

  /** property IRI → (subject, object, justifications). */
  lazy val objectByProperty: Map[Iri, List[ObjectRow]] =
    objectAssertions.groupMap(_.value.property)(entry =>
      (
        subject = entry.value.subject,
        obj = entry.value.obj,
        justifications = entry.justifications
      )
    )

  /** (subject, property) → (object, justifications) — the forward-traversal index. */
  lazy val objectBySubjectProperty: Map[(Iri, Iri), List[ObjectValue]] =
    objectAssertions.groupMap(entry => (entry.value.subject, entry.value.property))(entry =>
      (obj = entry.value.obj, justifications = entry.justifications)
    )

  lazy val dataAssertions: List[Supported[DataFact]] =
    collect { case Axiom.DataAssertion(s, p, v) =>
      (subject = s, property = p, literal = v)
    }

  lazy val dataByProperty: Map[Iri, List[DataRow]] =
    dataAssertions.groupMap(_.value.property)(entry =>
      (
        subject = entry.value.subject,
        literal = entry.value.literal,
        justifications = entry.justifications
      )
    )

  /** (subject, property) → (literal, justifications) — the data-property lookup index. */
  lazy val dataBySubjectProperty: Map[(Iri, Iri), List[DataValue]] =
    dataAssertions.groupMap(entry => (entry.value.subject, entry.value.property))(entry =>
      (literal = entry.value.literal, justifications = entry.justifications)
    )

  lazy val sameIndividuals: List[Supported[Edge]] =
    collect { case Axiom.SameIndividual(a, b) => (sub = a, sup = b) }

  lazy val differentIndividuals: List[Supported[Edge]] =
    collect { case Axiom.DifferentIndividuals(a, b) => (sub = a, sup = b) }

  /** Value-only lookups keep callers that do not resolve policy or provenance out of index rows. */
  def instances(cls: Iri): List[Iri] = instancesOf.getOrElse(cls, Nil).map(_.value)

  def classes(individual: Iri): List[Iri] = classesOf.getOrElse(individual, Nil).map(_.value)

  def objectValues(subject: Iri, property: Iri): List[Iri] =
    objectBySubjectProperty.getOrElse((subject, property), Nil).map(_.obj)

  def dataValues(subject: Iri, property: Iri): List[Literal] =
    dataBySubjectProperty.getOrElse((subject, property), Nil).map(_.literal)

  /** Traverses one chain step as a binary relation, honoring the inverse flag. */
  def relationFor(step: ChainStep): List[Relation] =
    objectByProperty.getOrElse(step.property, Nil).map: row =>
      if step.inverse then
        (from = row.obj, to = row.subject, justifications = row.justifications)
      else
        (from = row.subject, to = row.obj, justifications = row.justifications)
