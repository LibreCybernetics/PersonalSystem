package noesis.core.model

import cats.Order
import io.circe.derivation.ConfiguredCodec

/** Why something is in a graph — the atomic unit of a justification.
  *
  * Both variants trace back to the journal, which is what keeps SPEC §4 (the journal is the truth)
  * intact for derived facts: an entailment's support is always a set of journal-backed premises,
  * never an opaque "the reasoner said so".
  */
enum Support derives ConfiguredCodec:
  /** A directly asserted axiom. */
  case Asserted(axiom: AxiomId)

  /** A triple materialized from an ongoing fluent by the current-graph projection (SPEC §3.6). */
  case FromFluent(fluent: FluentId)

  def render: String = this match
    case Asserted(id)  => id.value
    case FromFluent(f) => f.value

object Support:
  given Order[Support] = Order.by(_.render)
  given Ordering[Support] = Order[Support].toOrdering

/** A minimal set of premises sufficient to derive a fact (SPEC §3.4).
  *
  * Justifications are the shared currency of three otherwise unrelated features: disclosure
  * filtering (§3.3.1), belief in derived facts (§4.4), and contradiction UX (§3.4). Computing them
  * once, in the reasoner, is why those three stay consistent with each other.
  */
final case class Justification(premises: Set[Support]) derives ConfiguredCodec:
  def size: Int = premises.size
  def isEmpty: Boolean = premises.isEmpty

  /** Is this justification strictly weaker (a superset of premises) than `other`? */
  def subsumedBy(other: Justification): Boolean =
    other.premises.subsetOf(premises) && other.premises.size < premises.size

  def merge(other: Justification): Justification = Justification(premises ++ other.premises)

  def axiomIds: Set[AxiomId] = premises.collect { case Support.Asserted(id) => id }

object Justification:
  val empty: Justification = Justification(Set.empty)

  def asserted(id: AxiomId): Justification = Justification(Set(Support.Asserted(id)))

  def of(premises: Support*): Justification = Justification(premises.toSet)

  given Order[Justification] = Order.by(j => (j.size, j.premises.toList.sorted.map(_.render)))
  given Ordering[Justification] = Order[Justification].toOrdering

  /** Drops justifications that are supersets of a smaller one, keeping only minimal explanations. */
  def minimal(candidates: Set[Justification]): Set[Justification] =
    candidates.filterNot(c => candidates.exists(other => c.subsumedBy(other)))

/** A fact together with every minimal justification the reasoner found for it. */
final case class Explanation(axiom: Axiom, justifications: Set[Justification])
    derives ConfiguredCodec:
  def isAsserted: Boolean = justifications.exists(j => j.premises == Set(Support.Asserted(axiom.id)))
  def isDerived: Boolean = !isAsserted
