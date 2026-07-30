package noesis.core.module

import java.time.LocalDate

import cats.data.NonEmptyList
import noesis.core.capture.Intent
import noesis.core.policy.{Disclosure, DisclosurePolicy, PolicyBook, SupportResolver}
import noesis.core.projection.KbState
import noesis.core.verbalize.NamingContext
import noesis.logic.{Axiom, Iri}
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

final case class ExportContext(
    state: KbState,
    closure: Closure,
    naming: NamingContext,
    policies: PolicyBook,
    disclosure: DisclosurePolicy
):
  private lazy val resolver = SupportResolver(state, policies)

  def permits(axiom: Axiom): Boolean =
    Disclosure.decide(axiom, closure, resolver, disclosure).isDisclosed

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
