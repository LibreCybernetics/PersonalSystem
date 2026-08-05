package dev.librecybernetics.noesis.app

import munit.FunSuite

import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.reasoner.Closure

/** The structured boundary keeps ontology evidence when deciding reference versus literal values. */
class AssertionsSuite extends FunSuite:
  private val subject = Iri("noesis:e/subject")
  private val property = Iri("noesis:test/property")
  private val cls = Iri("noesis:test/Class")

  private def closure(axioms: Axiom*): Closure =
    Closure(axioms.map(_ -> Set.empty).toMap, 0, saturated = true)

  test("rdf:type and a declared range produce reference assertions"):
    assertEquals(
      Assertions.build(Closure.empty, subject, Vocab.rdfType, cls.value),
      Axiom.ClassAssertion(subject, cls)
    )
    assertEquals(
      Assertions.build(closure(Axiom.PropertyRange(property, cls)), subject, property, "object"),
      Axiom.ObjectAssertion(subject, property, Workspace.iri("object"))
    )

  test("declared object and data usage disambiguate properties without a range"):
    val target = Iri("noesis:e/target")
    assertEquals(
      Assertions.build(
        closure(Axiom.ObjectAssertion(subject, property, target)),
        subject,
        property,
        "other"
      ),
      Axiom.ObjectAssertion(subject, property, Workspace.iri("other"))
    )
    assertEquals(
      Assertions.build(
        closure(Axiom.DataAssertion(subject, property, Literal.string("old"))),
        subject,
        property,
        "new"
      ),
      Axiom.DataAssertion(subject, property, Literal.string("new"))
    )

  test("label and fallback values preserve literal versus explicit IRI intent"):
    assertEquals(
      Assertions.build(Closure.empty, subject, Vocab.label, "Marco"),
      Axiom.DataAssertion(subject, Vocab.label, Literal.string("Marco"))
    )
    assertEquals(
      Assertions.build(Closure.empty, subject, property, "crm:Person"),
      Axiom.ObjectAssertion(subject, property, Iri("crm:Person"))
    )
    assertEquals(
      Assertions.build(Closure.empty, subject, property, "plain text"),
      Axiom.DataAssertion(subject, property, Literal.string("plain text"))
    )
