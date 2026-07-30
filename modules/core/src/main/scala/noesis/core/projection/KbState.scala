package noesis.core.projection

import java.time.LocalDate

import noesis.journal.{JournalEntry, Operation}
import noesis.logic.*

/** An asserted axiom together with its annotations and lifecycle status. */
final case class AxiomRecord(
    id: AxiomId,
    axiom: Axiom,
    annotations: AxiomAnnotations,
    status: AxiomStatus,
    /** Journal sequence of the operation that first asserted this axiom. */
    assertedAt: Long
):
  def isActive: Boolean = status == AxiomStatus.Active

  /** Participates in reasoning? Disputed axioms are deliberately excluded (SPEC §3.4). */
  def isReasonable: Boolean = status == AxiomStatus.Active

/** The fold of the journal: everything the log says, with nothing inferred yet (SPEC §3.2).
  *
  * This is the first and cheapest projection. It is a plain immutable value, which is what makes
  * time travel trivial — replaying a prefix of the journal yields the state as of that point, with
  * no special-case code.
  */
final case class KbState(
    seq: Long,
    axioms: Map[AxiomId, AxiomRecord],
    fluents: Map[FluentId, Fluent]
):
  def activeAxioms: Iterable[AxiomRecord] = axioms.values.filter(_.isActive)

  def reasonableAxioms: Iterable[AxiomRecord] = axioms.values.filter(_.isReasonable)

  def axiom(id: AxiomId): Option[AxiomRecord] = axioms.get(id)

  def fluent(id: FluentId): Option[Fluent] = fluents.get(id)

  def ongoingFluents: Iterable[Fluent] = fluents.values.filter(_.isOngoing)

  /** Open fluents matching a subject/property, newest boundary first.
    *
    * This is what a "stopped …" capture consults to propose closing the right state (SPEC §3.6).
    */
  def openFluentsFor(subject: Iri, property: Iri, value: Option[Node] = None): List[Fluent] =
    fluents.values
      .filter(f => f.isOngoing && f.matches(subject, property, value))
      .toList
      .sortBy(_.validFrom.flatMap(_.lowerBound).map(_.toEpochDay).getOrElse(Long.MinValue))
      .reverse

  /** Closed fluents matching a subject/property — the fallback when no open state matches. */
  def closedFluentsFor(subject: Iri, property: Iri, value: Option[Node] = None): List[Fluent] =
    fluents.values.filter(f => !f.isOngoing && f.matches(subject, property, value)).toList

  def fluentsHeldOn(date: LocalDate): Iterable[Fluent] = fluents.values.filter(_.heldOn(date))

  /** Every entity mentioned anywhere — the domain of the entity browser and search index. */
  def entities: Set[Iri] =
    activeAxioms.flatMap(_.axiom.individuals).toSet ++
      fluents.values.flatMap(f => Set(f.statedSubject) ++ f.statedValue.asIri)

  /** Axioms and fluents mentioning `entity`, for the entity page. */
  def about(entity: Iri): (List[AxiomRecord], List[Fluent]) =
    (
      activeAxioms.filter(_.axiom.signature.contains(entity)).toList,
      fluents.values.filter(f => f.statedSubject == entity || f.statedValue.asIri.contains(entity)).toList
    )

object KbState:
  val empty: KbState = KbState(0L, Map.empty, Map.empty)

  /** Applies one journal entry. Total by construction: every operation has a defined effect, and
    * operations naming unknown axioms are no-ops rather than errors, so a journal is always
    * replayable even if it was written by a newer version that knew about more entities.
    */
  def step(state: KbState, entry: JournalEntry): KbState =
    val next = entry.operation match
      case Operation.Assert(id, axiom, annotations) =>
        // Re-asserting a retracted axiom revives it under the same id, and re-asserting an active
        // one refreshes annotations without duplicating: ids are content-derived (§3.1).
        val record = state.axioms.get(id) match
          case Some(existing) =>
            existing.copy(annotations = annotations, status = AxiomStatus.Active)
          case None =>
            AxiomRecord(id, axiom, annotations, AxiomStatus.Active, entry.seq)
        state.copy(axioms = state.axioms.updated(id, record))

      case Operation.Retract(id, _) =>
        state.copy(axioms = state.axioms.updatedWith(id)(_.map(_.copy(status = AxiomStatus.Retracted))))

      case Operation.Annotate(id, patch) =>
        state.copy(axioms =
          state.axioms.updatedWith(id)(_.map(r => r.copy(annotations = patch.applyTo(r.annotations))))
        )

      case Operation.Reclassify(id, sensitivity, scope) =>
        state.copy(axioms =
          state.axioms.updatedWith(id):
            _.map(r =>
              r.copy(annotations = r.annotations.copy(sensitivity = Some(sensitivity), knowledgeScope = scope))
            )
        )

      case Operation.Dispute(id, _) =>
        state.copy(axioms = state.axioms.updatedWith(id)(_.map(_.copy(status = AxiomStatus.Disputed))))

      case Operation.Undispute(id) =>
        state.copy(axioms = state.axioms.updatedWith(id):
          _.map(r => if r.status == AxiomStatus.Disputed then r.copy(status = AxiomStatus.Active) else r)
        )

      case Operation.OpenFluent(fluent) =>
        state.copy(fluents = state.fluents.updated(fluent.id, fluent))

      case Operation.CloseFluent(id, validTo, reason) =>
        state.copy(fluents =
          state.fluents.updatedWith(id)(_.map(_.copy(validTo = validTo, endReason = Some(reason))))
        )

      case Operation.SupersedeFluent(oldId, replacement, at) =>
        val closed = state.fluents.updatedWith(oldId):
          _.map(
            _.copy(
              validTo = at,
              endReason = Some(EndReason.Superseded),
              supersededBy = Some(replacement.id)
            )
          )
        // The replacement inherits the boundary date, so a supersession cannot leave a gap or an
        // overlap between the two states.
        val opened = replacement.copy(validFrom = replacement.validFrom.orElse(at))
        state.copy(fluents = closed.updated(replacement.id, opened))

    next.copy(seq = entry.seq)

  /** Replays entries in order. */
  def replay(entries: IterableOnce[JournalEntry]): KbState =
    entries.iterator.foldLeft(empty)(step)

  /** Replays only entries up to and including `seq` — the time-travel projection (SPEC §3.2). */
  def replayUntil(entries: IterableOnce[JournalEntry], seq: Long): KbState =
    entries.iterator.filter(_.seq <= seq).foldLeft(empty)(step)
