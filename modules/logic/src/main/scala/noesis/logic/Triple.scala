package noesis.logic

import cats.Order
import io.circe.derivation.ConfiguredCodec

/** The object position of a triple: either a reference to an entity or a data value. */
enum Node derives ConfiguredCodec:
  case Ref(iri: Iri)
  case Lit(literal: Literal)

  def render: String = this match
    case Ref(iri) => iri.value
    case Lit(l)   => l.render

  def asIri: Option[Iri] = this match
    case Ref(iri) => Some(iri)
    case _        => None

object Node:
  given Order[Node] = Order.by(_.render)

/** A triple view over an axiom, so queries and exports have one uniform shape to work on. */
final case class Triple(subject: Iri, property: Iri, obj: Node):
  def render: String = s"${subject.value} ${property.value} ${obj.render}"

object Triple:
  given Order[Triple] = Order.by(t => (t.subject, t.property, t.obj))

/** Core vocabulary: the terms the Knowledge Core itself understands (SPEC §3, §12.6). */
object Vocab:
  val rdfType: Iri = Iri("rdf:type")
  val subClassOf: Iri = Iri("rdfs:subClassOf")
  val subPropertyOf: Iri = Iri("rdfs:subPropertyOf")
  val label: Iri = Iri("rdfs:label")

  /** The single human principal (SPEC §1.1). */
  val me: Iri = Iri("core:me")

  val Agent: Iri = Iri("core:Agent")
  val Person: Iri = Iri("core:Person")
  val Organization: Iri = Iri("core:Organization")
  val Fluent: Iri = Iri("core:Fluent")

  val timeVarying: Iri = Iri("core:timeVarying")

/** Conversions between axioms and their triple projection.
  *
  * Only some axioms are triples: schema constructs like property chains and disjointness have no
  * faithful single-triple form, so they are queried through their own predicates rather than
  * flattened misleadingly.
  */
object Triples:
  def of(axiom: Axiom): Option[Triple] = axiom match
    case Axiom.ClassAssertion(i, c)     => Some(Triple(i, Vocab.rdfType, Node.Ref(c)))
    case Axiom.ObjectAssertion(s, p, o) => Some(Triple(s, p, Node.Ref(o)))
    case Axiom.DataAssertion(s, p, v)   => Some(Triple(s, p, Node.Lit(v)))
    case Axiom.SubClassOf(a, b)         => Some(Triple(a, Vocab.subClassOf, Node.Ref(b)))
    case Axiom.SubPropertyOf(a, b)      => Some(Triple(a, Vocab.subPropertyOf, Node.Ref(b)))
    case _                              => None

  def toAxiom(triple: Triple): Axiom = triple match
    case Triple(s, p, Node.Ref(o)) if p == Vocab.rdfType       => Axiom.ClassAssertion(s, o)
    case Triple(s, p, Node.Ref(o)) if p == Vocab.subClassOf    => Axiom.SubClassOf(s, o)
    case Triple(s, p, Node.Ref(o)) if p == Vocab.subPropertyOf => Axiom.SubPropertyOf(s, o)
    case Triple(s, p, Node.Ref(o))                             => Axiom.ObjectAssertion(s, p, o)
    case Triple(s, p, Node.Lit(v))                             => Axiom.DataAssertion(s, p, v)
