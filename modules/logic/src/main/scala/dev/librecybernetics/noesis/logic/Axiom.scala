package dev.librecybernetics.noesis.logic

import java.security.MessageDigest

import cats.Order
import io.circe.derivation.ConfiguredCodec
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}

/** A stable identifier for an asserted axiom (SPEC §3.1).
  *
  * Derived from the axiom's canonical JSON rather than minted randomly, which buys two properties
  * the spec needs: annotations and learning items keep addressing the same axiom across a
  * retract/re-assert cycle, and asserting the same axiom twice is idempotent instead of silently
  * duplicating it.
  */
opaque type AxiomId = String

object AxiomId:
  /** SHA-256 over the axiom's RFC 8785 canonical form, truncated to twelve bytes.
    *
    * Canonicalization lives in [[Canonical]] rather than here precisely so that this identifier
    * does not depend on how circe happens to order fields — see that object for why SPEC §6.2
    * would otherwise be unenforceable.
    */
  def of(axiom: Axiom): AxiomId =
    val digest = MessageDigest.getInstance("SHA-256").digest(Canonical.bytes(axiom.asJson))
    "ax_" + digest.take(12).map("%02x".format(_)).mkString

  def unsafe(value: String): AxiomId = value

  extension (id: AxiomId) def value: String = id

  given Order[AxiomId] = Order.by(_.value)
  given Ordering[AxiomId] = Order[AxiomId].toOrdering
  given Encoder[AxiomId] = Encoder.encodeString.contramap(_.value)
  given Decoder[AxiomId] = Decoder.decodeString.map(unsafe)
  given io.circe.KeyEncoder[AxiomId] = io.circe.KeyEncoder.encodeKeyString.contramap(_.value)
  given io.circe.KeyDecoder[AxiomId] = io.circe.KeyDecoder.decodeKeyString.map(unsafe)

/** One step of a property chain, optionally traversed backwards.
  *
  * Inverse steps are what make the spec's `worksAt ∘ worksAt⁻ ⊑ colleagueOf` (§7.1) expressible.
  */
final case class ChainStep(property: Iri, inverse: Boolean = false) derives ConfiguredCodec

/** An OWL-style axiom.
  *
  * This is the MVP's expressivity: the RDFS core plus the handful of OWL role constructs the
  * relationship and accounting modules actually need (symmetry, transitivity, inverses, chains,
  * disjointness). SPEC §3.1 sets OWL 2 DL as the ceiling; §11 anticipates delegating to a real
  * reasoner later. Nothing here depends on the *reasoner* being ours — `dev.librecybernetics.noesis.reasoner` is
  * pluggable, so growing expressivity means adding cases and rules, not reshaping the journal.
  */
enum Axiom derives ConfiguredCodec:
  // ── TBox: concepts ────────────────────────────────────────────────────────
  case SubClassOf(sub: Iri, sup: Iri)
  case DisjointClasses(left: Iri, right: Iri)

  // ── RBox: roles ───────────────────────────────────────────────────────────
  case SubPropertyOf(sub: Iri, sup: Iri)
  case InverseProperties(left: Iri, right: Iri)
  case SymmetricProperty(property: Iri)
  case TransitiveProperty(property: Iri)

  /** Nothing relates to itself under this property.
    *
    * Needed because the spec's own chain default `worksAt ∘ worksAt⁻ ⊑ colleagueOf` (§7.1) would
    * otherwise make everyone their own colleague. Declaring `colleagueOf` irreflexive both suppresses
    * that derivation and makes an asserted self-loop a genuine inconsistency.
    */
  case IrreflexiveProperty(property: Iri)
  case PropertyChain(chain: List[ChainStep], sup: Iri)
  case PropertyDomain(property: Iri, cls: Iri)
  case PropertyRange(property: Iri, cls: Iri)

  /** Marks a property as `core:timeVarying` (SPEC §3.6): assertions on it become fluents. */
  case TimeVarying(property: Iri)

  // ── ABox: individuals ─────────────────────────────────────────────────────
  case ClassAssertion(individual: Iri, cls: Iri)
  case ObjectAssertion(subject: Iri, property: Iri, obj: Iri)
  case DataAssertion(subject: Iri, property: Iri, value: Literal)
  case SameIndividual(left: Iri, right: Iri)
  case DifferentIndividuals(left: Iri, right: Iri)

  def id: AxiomId = AxiomId.of(this)

  /** True for assertions about individuals, which is what carries belief and gets quizzed. */
  def isAssertional: Boolean = this match
    case _: (ClassAssertion | ObjectAssertion | DataAssertion | SameIndividual |
          DifferentIndividuals) =>
      true
    case _ => false

  /** Every IRI this axiom mentions — used for entity pages, search and impact analysis. */
  def signature: Set[Iri] = this match
    case SubClassOf(a, b)             => Set(a, b)
    case DisjointClasses(a, b)        => Set(a, b)
    case SubPropertyOf(a, b)          => Set(a, b)
    case InverseProperties(a, b)      => Set(a, b)
    case SymmetricProperty(p)         => Set(p)
    case TransitiveProperty(p)        => Set(p)
    case IrreflexiveProperty(p)       => Set(p)
    case PropertyChain(chain, sup)    => chain.map(_.property).toSet + sup
    case PropertyDomain(p, c)         => Set(p, c)
    case PropertyRange(p, c)          => Set(p, c)
    case TimeVarying(p)               => Set(p)
    case ClassAssertion(i, c)         => Set(i, c)
    case ObjectAssertion(s, p, o)     => Set(s, p, o)
    case DataAssertion(s, p, _)       => Set(s, p)
    case SameIndividual(a, b)         => Set(a, b)
    case DifferentIndividuals(a, b)   => Set(a, b)

  /** The individuals (as opposed to vocabulary terms) this axiom is about. */
  def individuals: Set[Iri] = this match
    case ClassAssertion(i, _)       => Set(i)
    case ObjectAssertion(s, _, o)   => Set(s, o)
    case DataAssertion(s, _, _)     => Set(s)
    case SameIndividual(a, b)       => Set(a, b)
    case DifferentIndividuals(a, b) => Set(a, b)
    case _                          => Set.empty

  /** The property this axiom asserts, when it asserts one. Drives the policy cascade (§3.3). */
  def assertedProperty: Option[Iri] = this match
    case ObjectAssertion(_, p, _) => Some(p)
    case DataAssertion(_, p, _)   => Some(p)
    case _                        => None

  /** Manchester-ish rendering, the "raw" third view in the confirmation UI (SPEC §3.5.5). */
  def manchester: String = this match
    case SubClassOf(a, b)           => s"${a.local} SubClassOf: ${b.local}"
    case DisjointClasses(a, b)      => s"${a.local} DisjointWith: ${b.local}"
    case SubPropertyOf(a, b)        => s"${a.local} SubPropertyOf: ${b.local}"
    case InverseProperties(a, b)    => s"${a.local} InverseOf: ${b.local}"
    case SymmetricProperty(p)       => s"${p.local} Characteristics: Symmetric"
    case TransitiveProperty(p)      => s"${p.local} Characteristics: Transitive"
    case IrreflexiveProperty(p)     => s"${p.local} Characteristics: Irreflexive"
    case PropertyChain(chain, sup)  =>
      val path = chain.map(s => if s.inverse then s"${s.property.local}⁻" else s.property.local)
      s"${path.mkString(" o ")} SubPropertyOf: ${sup.local}"
    case PropertyDomain(p, c)       => s"${p.local} Domain: ${c.local}"
    case PropertyRange(p, c)        => s"${p.local} Range: ${c.local}"
    case TimeVarying(p)             => s"${p.local} Characteristics: TimeVarying"
    case ClassAssertion(i, c)       => s"${i.local} Types: ${c.local}"
    case ObjectAssertion(s, p, o)   => s"${s.local} Facts: ${p.local} ${o.local}"
    case DataAssertion(s, p, v)     => s"""${s.local} Facts: ${p.local} "${v.render}""""
    case SameIndividual(a, b)       => s"${a.local} SameAs: ${b.local}"
    case DifferentIndividuals(a, b) => s"${a.local} DifferentFrom: ${b.local}"

object Axiom:
  given Order[Axiom] = Order.by(_.id.value)
  given Ordering[Axiom] = Order[Axiom].toOrdering
