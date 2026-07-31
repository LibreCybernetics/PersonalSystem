package noesis.core.module

import java.time.LocalDate

import cats.data.NonEmptyList
import noesis.core.capture.Intent
import noesis.core.policy.{Disclosure, DisclosurePolicy, PolicyBook, SupportResolver}
import noesis.core.projection.KbState
import noesis.core.verbalize.{Naming, NamingContext}
import noesis.logic.{AxiomAnnotations, Iri}
import noesis.reasoner.Closure

final case class ImportBatch(record: Iri, intents: NonEmptyList[Intent])

/** A document-to-intents adapter contributed by a module (SPEC §5.1). */
trait DocumentImporter:
  def formats: Set[String]

  def parse(document: String): Either[List[String], List[ImportBatch]]

final case class ExportOptions(
    includeContactData: Boolean = false,
    includeSocialGraph: Boolean = false
)

/** The least-authority view passed to a document exporter.
  *
  * The constructor is deliberately private: extension code receives only facts and derivation
  * paths already admitted by the target policy, with annotations removed and unnamed entities
  * pseudonymized. An exporter therefore cannot bypass disclosure by forgetting to call a helper
  * (DESIGN Zero Trust and data minimization).
  */
final class ExportContext private (
    val state: KbState,
    val closure: Closure,
    val naming: NamingContext
)

object ExportContext:
  def restricted(
      state: KbState,
      closure: Closure,
      policies: PolicyBook,
      disclosure: DisclosurePolicy,
      namingProperties: List[Iri] = Naming.defaultNamingProperties,
      namingSchemes: List[Naming.Scheme] = Nil
  ): ExportContext =
    val resolver = SupportResolver(state, policies)
    val disclosedClosure = Disclosure.restrict(closure, resolver, disclosure)
    val disclosedAxioms =
      state.activeAxioms.collect:
        case record if disclosedClosure.contains(record.axiom) =>
          record.id -> record.copy(annotations = AxiomAnnotations.empty)
      .toMap
    val disclosedFluents =
      state.ongoingFluents.collect:
        case fluent if disclosedClosure.contains(fluent.assertion) =>
          fluent.id -> fluent.copy(annotations = AxiomAnnotations.empty)
      .toMap
    val disclosedState = KbState(
      state.seq,
      disclosedAxioms,
      disclosedFluents
    )
    val naming =
      Naming.from(
        disclosedState,
        namingProperties,
        namingSchemes,
        redactUnnamedOpaque = true
      )
    new ExportContext(disclosedState, disclosedClosure, naming)

/** A current projection-to-document adapter contributed by a module (SPEC §5.1). */
trait DocumentExporter:
  def formats: Set[String]

  def render(
      context: ExportContext,
      entity: Iri,
      options: ExportOptions
  ): Either[List[String], String]

final case class AgendaEntry(
    source: Iri,
    subject: Iri,
    due: LocalDate,
    kind: String,
    summary: String,
    overdue: Boolean
)

/** A disposable agenda projection contributed by a module (SPEC §5.1). */
trait AgendaProducer:
  def entries(state: KbState, today: LocalDate): List[AgendaEntry]
