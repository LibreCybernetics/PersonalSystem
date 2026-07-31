package dev.librecybernetics.noesis.reasoner.query

import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.reasoner.Closure

/** A term in a query pattern: either bound to a constant or a variable to solve for. */
enum Term:
  case Var(name: String)
  case Ref(iri: Iri)
  case Lit(literal: Literal)

  def render: String = this match
    case Var(n)   => s"?$n"
    case Ref(i)   => i.display
    case Lit(l)   => l.render

/** One triple pattern. `rdf:type` in the property position matches class assertions. */
final case class Pattern(subject: Term, property: Term, obj: Term):
  def render: String = s"${subject.render} ${property.render} ${obj.render}"

  def variables: Set[String] =
    Set(subject, property, obj).collect { case Term.Var(n) => n }

/** A conjunctive basic graph pattern — the intersection of every pattern's solutions. */
final case class BasicGraphPattern(patterns: List[Pattern]):
  def variables: Set[String] = patterns.flatMap(_.variables).toSet

/** A variable binding. */
final case class Solution(bindings: Map[String, Node]):
  def get(variable: String): Option[Node] = bindings.get(variable)

  def render(order: List[String]): String =
    order.map(v => s"$v=${bindings.get(v).map(_.render).getOrElse("-")}").mkString(" ")

  /** Extends the binding, or fails if `variable` is already bound to something else. */
  def bind(variable: String, node: Node): Option[Solution] =
    bindings.get(variable) match
      case Some(existing) if existing != node => None
      case _                                  => Some(Solution(bindings.updated(variable, node)))

object Solution:
  val empty: Solution = Solution(Map.empty)

/** Basic graph pattern matching over a closure (SPEC §3.4).
  *
  * This is not SPARQL 1.1 — no OPTIONAL, no property paths, no aggregation. It is the conjunctive
  * core, which is what entity resolution, the quiz generator's distractor lookups, and the module
  * queries in §7–§8 actually need. Because it runs over the *closure* rather than the asserted
  * graph, queries see entailments for free: asking for `?p rdf:type crm:Person` finds people who
  * were only ever asserted to be `crm:Agent` subclasses.
  */
object Query:

  def solve(closure: Closure, bgp: BasicGraphPattern): List[Solution] =
    val triples = closure.triples.toList.sortBy(triple =>
      (triple.subject.value, triple.property.value, triple.obj.render)
    )
    val solved = bgp.patterns.foldLeft(List(Solution.empty)): (solutions, pattern) =>
      for
        solution <- solutions
        triple <- triples
        extended <- matchTriple(pattern, triple, solution).toList
      yield extended
    val order = bgp.variables.toList.sorted
    solved.distinct.sortBy(_.render(order))

  /** Attempts to match one pattern against one triple, extending `solution`. */
  private def matchTriple(pattern: Pattern, triple: Triple, solution: Solution): Option[Solution] =
    for
      s1 <- unify(pattern.subject, Node.Ref(triple.subject), solution)
      s2 <- unify(pattern.property, Node.Ref(triple.property), s1)
      s3 <- unify(pattern.obj, triple.obj, s2)
    yield s3

  private def unify(term: Term, node: Node, solution: Solution): Option[Solution] =
    term match
      case Term.Var(name)  => solution.bind(name, node)
      case Term.Ref(iri)   => Option.when(node == Node.Ref(iri))(solution)
      case Term.Lit(value) => Option.when(node == Node.Lit(value))(solution)
