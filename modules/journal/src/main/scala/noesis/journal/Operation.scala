package noesis.journal

import java.time.Instant

import io.circe.derivation.ConfiguredCodec
import noesis.logic.*
import noesis.logic.given

/** An operation on the knowledge base (SPEC §3.2).
  *
  * The journal records *operations*, not end states, because capture emits operations (§3.5): a job
  * change is one `SupersedeFluent`, not a delete plus an insert whose relationship has been lost. A
  * log of states would make time-travel and change-item generation guesswork.
  */
enum Operation derives ConfiguredCodec:
  case Assert(
      axiomId: AxiomId,
      axiom: Axiom,
      annotations: AxiomAnnotations = AxiomAnnotations.empty
  )
  case Retract(axiomId: AxiomId, reason: Option[String] = None)
  case Annotate(axiomId: AxiomId, patch: AnnotationPatch)

  /** Reclassify sensitivity and knowledge scope — split out from `Annotate` because SPEC §12.8
    * calls for a reviewable reclassification queue, and that needs its own journal signal.
    */
  case Reclassify(axiomId: AxiomId, sensitivity: Sensitivity, knowledgeScope: Set[Iri] = Set.empty)

  /** Mark as disputed: excluded from reasoning without choosing a side (SPEC §3.4). */
  case Dispute(axiomId: AxiomId, note: Option[String] = None)
  case Undispute(axiomId: AxiomId)

  case OpenFluent(fluent: Fluent)
  case CloseFluent(
      fluentId: FluentId,
      validTo: Option[PartialDate],
      endReason: EndReason = EndReason.Ended
  )

  /** Close one fluent and open its replacement, linked — the spec's single confirmable
    * supersession (§3.6). Kept atomic so `state.changed` can carry both sides, which is what lets
    * the learning engine raise a change item for the *new* value at elevated priority.
    */
  case SupersedeFluent(oldFluentId: FluentId, replacement: Fluent, at: Option[PartialDate])

  /** The axiom this operation acts on, if it acts on one. */
  def targetAxiom: Option[AxiomId] = this match
    case Assert(id, _, _)     => Some(id)
    case Retract(id, _)       => Some(id)
    case Annotate(id, _)      => Some(id)
    case Reclassify(id, _, _) => Some(id)
    case Dispute(id, _)       => Some(id)
    case Undispute(id)        => Some(id)
    case _                    => None

  /** The fluent this operation acts on, if it acts on one. */
  def targetFluent: Option[FluentId] = this match
    case OpenFluent(f)             => Some(f.id)
    case CloseFluent(id, _, _)     => Some(id)
    case SupersedeFluent(id, _, _) => Some(id)
    case _                         => None

/** A journaled operation: monotonic sequence number, wall-clock time, and the operation itself.
  *
  * `seq` rather than the timestamp is the ordering authority — clocks move backwards, and
  * projections must replay identically forever (SPEC §10 reliability).
  */
final case class JournalEntry(seq: Long, at: Instant, operation: Operation) derives ConfiguredCodec

/** A group of operations committed atomically (SPEC §3.5.6).
  *
  * Capture produces bundles — "lunch with Sarah and Marco" is one interaction, one `spouseOf`, one
  * `worksAt` fluent — and either all of it lands or none does.
  */
final case class Commit(entries: List[JournalEntry]):
  def seqRange: Option[(Long, Long)] =
    entries.headOption.zip(entries.lastOption).map((first, last) => (first.seq, last.seq))
