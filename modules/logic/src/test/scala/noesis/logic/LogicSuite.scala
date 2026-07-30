package noesis.logic

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

/** Compatibility tests for the persisted formal language. */
class LogicSuite extends FunSuite:
  private val alice = Iri("noesis:e/alice")
  private val person = Iri("crm:Person")

  test("canonical axiom JSON and its content-derived id remain stable"):
    val axiom = Axiom.ClassAssertion(alice, person)
    val canonical = """{"individual":"noesis:e/alice","cls":"crm:Person","type":"ClassAssertion"}"""

    assertEquals(axiom.asJson.dropNullValues.noSpaces, canonical)
    assertEquals(axiom.id.value, "ax_b1bfb8fa359d0b982772c65a")

  test("every triple-shaped axiom round-trips through the ternary view"):
    val property = Iri("crm:knows")
    val axioms = List(
      Axiom.ClassAssertion(alice, person),
      Axiom.ObjectAssertion(alice, property, Iri("noesis:e/marco")),
      Axiom.DataAssertion(alice, Vocab.label, Literal.Str("Alice")),
      Axiom.SubClassOf(person, Vocab.Agent),
      Axiom.SubPropertyOf(property, Iri("crm:relatedTo"))
    )

    axioms.foreach: axiom =>
      assertEquals(Triples.of(axiom).map(Triples.toAxiom), Some(axiom))

  test("non-ternary OWL constructs are not flattened into misleading triples"):
    assertEquals(Triples.of(Axiom.DisjointClasses(person, Vocab.Organization)), None)
    assertEquals(
      Triples.of(Axiom.PropertyChain(List(ChainStep(Iri("crm:parentOf"))), Iri("crm:ancestorOf"))),
      None
    )

  test("the explicit clear patch remains distinct from an absent patch after JSON round-trip"):
    val clear = AnnotationPatch(sensitivity = Patch.Clear)
    val leave = AnnotationPatch(sensitivity = Patch.Leave)

    assertEquals(decode[AnnotationPatch](clear.asJson.noSpaces), Right(clear))
    assertNotEquals(clear, leave)
