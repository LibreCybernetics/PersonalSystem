package noesis.core

import munit.FunSuite
import noesis.core.Fixtures.*
import noesis.core.model.*
import noesis.core.projection.Graph
import noesis.core.query.*
import noesis.core.reason.{Closure, Reasoner}

/** Query tests (SPEC §3.4).
  *
  * Queries run over the *closure*, not the asserted graph, so the important cases here are the ones
  * where the answer exists only because of inference — that is what makes "Russian words whose
  * Spanish equivalent shares a Latin root" (§6) answerable at all.
  */
class QuerySuite extends FunSuite:

  private def closureOf(axioms: Axiom*): Closure =
    Reasoner.closure(Graph(axioms.map(a => a -> Set[Support](Support.Asserted(a.id))).toMap))

  private val world = closureOf(
    (crmSchema ++ List(
      Axiom.ClassAssertion(alice, Person),
      Axiom.ClassAssertion(marco, Person),
      Axiom.ClassAssertion(sarah, Person),
      Axiom.ObjectAssertion(alice, worksAt, acme),
      Axiom.ObjectAssertion(marco, worksAt, acme),
      Axiom.ObjectAssertion(sarah, worksAt, molina),
      Axiom.ObjectAssertion(sarah, spouseOf, marco),
      Axiom.DataAssertion(lia, birthday, Literal.Date(PartialDate.monthDay(5, 12)))
    ))*
  )

  private def solve(text: String): List[Solution] =
    Query.solve(world, Query.parse(text).fold(err => fail(err), identity))

  test("a single pattern binds one variable"):
    val results = solve("?who crm:worksAt noesis:e/acme")
    assertEquals(
      results.flatMap(_.get("who")).toSet,
      Set(Node.Ref(alice), Node.Ref(marco))
    )

  test("a conjunctive pattern joins on a shared variable"):
    val results = solve("?who rdf:type crm:Person . ?who crm:worksAt noesis:e/molina")
    assertEquals(results.flatMap(_.get("who")), List(Node.Ref(sarah)))

  test("repeating a variable in one pattern constrains it to a self-relation"):
    // Nobody works at themselves, so this must return nothing rather than cross-joining.
    assertEquals(solve("?x crm:worksAt ?x"), Nil)

  test("queries see inferred class membership, not just asserted"):
    // Nobody is asserted to be a crm:Agent; it follows from Person ⊑ Agent.
    val agents = solve("?who rdf:type crm:Agent").flatMap(_.get("who")).toSet
    assert(agents.contains(Node.Ref(alice)), s"expected inferred Agent membership, got $agents")

  test("queries see inferred property assertions, so spouseOf answers a knows query"):
    val results = solve("noesis:e/sarah crm:knows ?whom")
    assertEquals(results.flatMap(_.get("whom")), List(Node.Ref(marco)))

  test("the colleagueOf chain is queryable like any other property"):
    val colleagues = solve("noesis:e/alice crm:colleagueOf ?whom").flatMap(_.get("whom")).toSet
    assertEquals(colleagues, Set(Node.Ref(marco)))

  test("a two-hop query finds colleagues through a shared employer"):
    val results: Set[(Node, Node)] = solve("?a crm:worksAt ?org . ?b crm:worksAt ?org")
      .flatMap(s => (s.get("a"), s.get("b")).tupled)
      .filter((a, b) => a != b)
      .toSet
    val expected: Set[(Node, Node)] =
      Set(Node.Ref(alice) -> Node.Ref(marco), Node.Ref(marco) -> Node.Ref(alice))
    assertEquals(results, expected)

  test("literals match by value, including partial dates"):
    val results = solve("""?who crm:birthday "--05-12"""")
    assertEquals(results.flatMap(_.get("who")), List(Node.Ref(lia)))

  test("an unmatched pattern yields no solutions"):
    assertEquals(solve("?who crm:worksAt noesis:e/nowhere"), Nil)

  test("a fully-bound pattern acts as an entailment check"):
    assertEquals(solve("noesis:e/alice crm:worksAt noesis:e/acme").length, 1)
    assertEquals(solve("noesis:e/alice crm:worksAt noesis:e/molina").length, 0)

  test("schema is queryable through rdfs:subClassOf"):
    val supers = solve("crm:Person rdfs:subClassOf ?super").flatMap(_.get("super")).toSet
    assert(supers.contains(Node.Ref(Agent)))

  test("parse rejects a malformed pattern instead of guessing"):
    assert(Query.parse("?onlytwo crm:worksAt").isLeft)
    assert(Query.parse("").exists(_.patterns.isEmpty))

  test("parse handles multiple dot-separated patterns and quoted literals"):
    val parsed = Query.parse("""?p rdf:type crm:Person . ?p crm:birthday "--05-12"""").fold(fail(_), identity)
    assertEquals(parsed.patterns.length, 2)
    assertEquals(parsed.variables, Set("p"))
    assertEquals(parsed.patterns(1).obj, Term.Lit(Literal.Date(PartialDate.monthDay(5, 12))))

  extension [A, B](pair: (Option[A], Option[B]))
    private def tupled: Option[(A, B)] = pair._1.flatMap(a => pair._2.map(b => (a, b)))
