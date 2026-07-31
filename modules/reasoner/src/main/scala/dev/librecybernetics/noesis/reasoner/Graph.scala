package dev.librecybernetics.noesis.reasoner

import dev.librecybernetics.noesis.logic.*

/** A set of axioms, each carrying the journal-backed reasons it is present.
  *
  * Keeping support attached at the graph level rather than recomputing it later is what lets
  * disclosure filtering and derived belief share one notion of "why" (SPEC §3.3.1, §4.4).
  */
final case class Graph(support: Map[Axiom, Set[Support]]):
  def axioms: Set[Axiom] = support.keySet

  def contains(axiom: Axiom): Boolean = support.contains(axiom)

  def supportFor(axiom: Axiom): Set[Support] = support.getOrElse(axiom, Set.empty)

  def size: Int = support.size

  def ++(other: Graph): Graph =
    Graph(other.support.foldLeft(support): (acc, kv) =>
      val (axiom, sup) = kv
      acc.updated(axiom, acc.getOrElse(axiom, Set.empty) ++ sup)
    )

  def withAxiom(axiom: Axiom, sup: Set[Support]): Graph =
    Graph(support.updated(axiom, supportFor(axiom) ++ sup))

  def filter(p: Axiom => Boolean): Graph = Graph(support.filter((a, _) => p(a)))

  /** The triple view, for querying and export. */
  def triples: Set[Triple] = axioms.flatMap(Triples.of)

  def assertions: Set[Axiom] = axioms.filter(_.isAssertional)

  /** Schema axioms only — what a module's ontology diff is computed against. */
  def schema: Set[Axiom] = axioms.filterNot(_.isAssertional)

object Graph:
  val empty: Graph = Graph(Map.empty)

  def of(entries: (Axiom, Set[Support])*): Graph = Graph(entries.toMap)
