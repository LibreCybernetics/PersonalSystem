package dev.librecybernetics.noesis.core.capture

import cats.data.NonEmptyList
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import dev.librecybernetics.noesis.journal.Operation
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.core.projection.KbState

/** What the owner intends, before it is turned into journal operations.
  *
  * Capture emits *operations*, not just assertions (SPEC §3.5), so intent is modeled explicitly:
  * "stopped working at Acme" is a boundary edit on one existing state, and only by naming that
  * intent can the system find and close the right fluent instead of asserting a contradictory fact.
  */
enum Intent:
  /** Assert an axiom. On a `core:timeVarying` property this silently opens an ongoing fluent —
    * the sugar from §3.6 that keeps the common case from ever mentioning fluents.
    */
  case Assert(axiom: Axiom, annotations: AxiomAnnotations = AxiomAnnotations.ownerConfirmed)

  case Retract(axiomId: AxiomId, reason: Option[String] = None)

  /** Close the open state matching subject/property (and value, when given). */
  case CloseState(
      subject: Iri,
      property: Iri,
      value: Option[Node] = None,
      validTo: Option[PartialDate] = None,
      reason: EndReason = EndReason.Ended
  )

  /** Replace the open state's value: one confirmable supersession (SPEC §3.6). */
  case Supersede(
      subject: Iri,
      property: Iri,
      newValue: Node,
      at: Option[PartialDate] = None,
      annotations: AxiomAnnotations = AxiomAnnotations.ownerConfirmed
  )

  /** Open a fluent explicitly, with a known start date. */
  case OpenState(
      subject: Iri,
      property: Iri,
      value: Node,
      validFrom: Option[PartialDate] = None,
      annotations: AxiomAnnotations = AxiomAnnotations.ownerConfirmed
  )

  case Annotate(axiomId: AxiomId, patch: AnnotationPatch)
  case Reclassify(axiomId: AxiomId, sensitivity: Sensitivity, scope: Set[Iri] = Set.empty)
  case Dispute(axiomId: AxiomId, note: Option[String] = None)
  case Undispute(axiomId: AxiomId)

/** Why an intent could not be turned into operations. Surfaced to the owner, never swallowed. */
final case class CaptureProblem(intent: Intent, detail: String)

/** Translates intents into journal operations against a given state.
  *
  * Pure and state-in/operations-out: the same function serves the CLI, a future HTTP capture
  * session, and the MCP propose-only path (SPEC §9), so all three necessarily agree on what a
  * "close this state" means. Nothing here writes — committing is [[dev.librecybernetics.noesis.core.kb.KnowledgeBase]]'s
  * job, which is what preserves the invariant that validation happens before the journal is touched.
  */
object Capture:

  def plan[F[_]: {UUIDGen, cats.Monad}](
      state: KbState,
      intents: NonEmptyList[Intent]
  ): F[Either[NonEmptyList[CaptureProblem], List[Operation]]] =
    intents.toList
      .foldLeftM((state, List.empty[Operation], List.empty[CaptureProblem])):
        case ((current, operations, problems), intent) =>
          planOne(current, intent).map:
            case Right(ops) =>
              // Thread the state forward so later intents in a bundle see earlier ones — closing a
              // state and reopening it in one capture has to work.
              val advanced = ops.foldLeft(current)(applyProvisionally)
              (advanced, operations ++ ops, problems)
            case Left(problem) => (current, operations, problems :+ problem)
      .map: (_, operations, problems) =>
        NonEmptyList.fromList(problems).toLeft(operations)

  private def planOne[F[_]: {UUIDGen, cats.Monad}](
      state: KbState,
      intent: Intent
  ): F[Either[CaptureProblem, List[Operation]]] =
    intent match
      case Intent.Assert(axiom, annotations) =>
        assertion(state, axiom, annotations).map(Right(_))

      case Intent.Retract(id, reason) =>
        val result =
          if state.axioms.contains(id) then Right(List(Operation.Retract(id, reason)))
          else Left(CaptureProblem(intent, s"no such axiom: ${id.value}"))
        result.pure[F].widen

      case Intent.OpenState(subject, property, value, validFrom, annotations) =>
        FluentId
          .fresh[F]
          .map: id =>
            Right(
              List(
                Operation.OpenFluent(
                  Fluent(id, subject, property, value, validFrom, annotations = annotations)
                )
              )
            )

      case close @ Intent.CloseState(subject, property, value, validTo, reason) =>
        val result = state.openFluentsFor(subject, property, value) match
          case fluent :: _ => Right(List(Operation.CloseFluent(fluent.id, validTo, reason)))
          case Nil =>
            // No open state. §3.6 says to offer an already-closed historical fluent rather than
            // inventing one, so this is a problem for the owner to resolve, not a silent no-op.
            val closed = state.closedFluentsFor(subject, property, value)
            Left(
              CaptureProblem(
                close,
                if closed.isEmpty then
                  s"no state to close for ${subject.display} ${property.local}"
                else
                  s"no open state for ${subject.display} ${property.local}; " +
                    s"${closed.size} closed one(s) exist: ${closed.map(_.describe).mkString("; ")}"
              )
            )
        result.pure[F].widen

      case supersede @ Intent.Supersede(subject, property, newValue, at, annotations) =>
        FluentId
          .fresh[F]
          .map: id =>
            state.openFluentsFor(subject, property) match
              case old :: _ =>
                val replacement =
                  Fluent(id, subject, property, newValue, at, annotations = annotations)
                Right(List(Operation.SupersedeFluent(old.id, replacement, at)))
              case Nil =>
                Left(
                  CaptureProblem(
                    supersede,
                    s"no open state to supersede for ${subject.display} ${property.local}"
                  )
                )

      case Intent.Annotate(id, patch) =>
        val result =
          if patch.isEmpty then Left(CaptureProblem(intent, "empty annotation patch"))
          else if !state.axioms.contains(id) then
            Left(CaptureProblem(intent, s"no such axiom: ${id.value}"))
          else Right(List(Operation.Annotate(id, patch)))
        result.pure[F].widen

      case Intent.Reclassify(id, sensitivity, scope) =>
        val result =
          if state.axioms.contains(id) then Right(List(Operation.Reclassify(id, sensitivity, scope)))
          else Left(CaptureProblem(intent, s"no such axiom: ${id.value}"))
        result.pure[F].widen

      case Intent.Dispute(id, note) =>
        val result =
          if state.axioms.contains(id) then Right(List(Operation.Dispute(id, note)))
          else Left(CaptureProblem(intent, s"no such axiom: ${id.value}"))
        result.pure[F].widen

      case Intent.Undispute(id) =>
        val result = state.axiom(id) match
          case Some(record) if record.status == AxiomStatus.Disputed =>
            Right(List(Operation.Undispute(id)))
          case Some(_) => Left(CaptureProblem(intent, s"axiom is not disputed: ${id.value}"))
          case None    => Left(CaptureProblem(intent, s"no such axiom: ${id.value}"))
        result.pure[F].widen

  /** The fluent sugar (SPEC §3.6): a plain assertion on a time-varying property opens a fluent.
    *
    * Superseding rather than opening a second fluent when one is already open is what stops the KB
    * from accumulating two simultaneous "current" employers from two innocent-looking captures.
    */
  private def assertion[F[_]: {UUIDGen, cats.Monad}](
      state: KbState,
      axiom: Axiom,
      annotations: AxiomAnnotations
  ): F[List[Operation]] =
    timeVaryingTriple(state, axiom) match
      case None => List(Operation.Assert(axiom.id, axiom, annotations)).pure[F]
      case Some(triple) =>
        FluentId.fresh[F].map: id =>
          val fresh = Fluent(id, triple.subject, triple.property, triple.obj, annotations = annotations)
          state.openFluentsFor(triple.subject, triple.property) match
            case Nil                                             => List(Operation.OpenFluent(fresh))
            case existing :: _ if existing.statedValue == triple.obj => Nil // already current
            case existing :: _ => List(Operation.SupersedeFluent(existing.id, fresh, None))

  /** The triple to fluentize, if this axiom asserts a `core:timeVarying` property. */
  private def timeVaryingTriple(state: KbState, axiom: Axiom): Option[Triple] =
    val timeVarying = state.activeAxioms.collect { case r =>
      r.axiom
    }.collect { case Axiom.TimeVarying(p) => p }.toSet

    axiom match
      case Axiom.ObjectAssertion(s, p, o) if timeVarying.contains(p) =>
        Some(Triple(s, p, Node.Ref(o)))
      case Axiom.DataAssertion(s, p, v) if timeVarying.contains(p) =>
        Some(Triple(s, p, Node.Lit(v)))
      case _ => None

  /** Applies an operation to a state without journaling it, so a bundle can be planned as a whole. */
  private def applyProvisionally(state: KbState, operation: Operation): KbState =
    KbState.step(
      state,
      dev.librecybernetics.noesis.journal.JournalEntry(state.seq, java.time.Instant.EPOCH, operation)
    )
