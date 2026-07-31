package noesis.core.kb

import noesis.core.policy.{PolicyBook, PolicyCascade}
import noesis.core.projection.KbState
import noesis.logic.*
import noesis.reasoner.Closure

/** A declarative record-shape check contributed through knowledge-base configuration.
  *
  * Validators inspect the same scratch state and closure used for consistency checking. They run
  * before journal append, so a domain record is never partially persisted (SPEC §3.5.4, §5.1).
  */
trait StateValidator:
  def name: String

  def validate(state: KbState, closure: Closure): List[String]

/** Boundary validation owned by the core rather than by any one vocabulary module.
  *
  * Smart constructors protect cooperative callers, but journals can also be written by importers
  * and future service surfaces. Rechecking the scratch projection here makes valid IRIs and numeric
  * annotation domains a commit invariant instead of a CLI convention (DESIGN Zero Trust).
  */
private[kb] object CoreValidation:
  def validate(state: KbState, policies: PolicyBook): List[String] =
    val axiomProblems = state.activeAxioms.toList.flatMap: record =>
      val iriProblems =
        record.axiom.signature.toList.flatMap(validateIri(record.id.value, _)) ++
          literalDatatype(record.axiom).toList.flatMap(validateIri(record.id.value, _)) ++
          annotationIris(record.annotations).flatMap(validateIri(record.id.value, _))
      iriProblems ++
        annotationProblems(record.id.value, record.annotations) ++
        PolicyCascade.validate(record, policies)

    val fluentProblems = state.ongoingFluents.toList.flatMap: fluent =>
      val subject = List(fluent.statedSubject, fluent.statedProperty)
      val value = fluent.statedValue match
        case Node.Ref(iri) => List(iri)
        case Node.Lit(lit) => List(lit.datatype)
      (subject ++ value ++ annotationIris(fluent.annotations))
        .flatMap(validateIri(fluent.id.value, _)) ++
        annotationProblems(fluent.id.value, fluent.annotations)

    (axiomProblems ++ fluentProblems).distinct.sorted

  private def annotationProblems(label: String, annotations: AxiomAnnotations): List[String] =
    List.concat(
      annotations.truthConfidence.flatMap(value =>
        Option.when(!value.isFinite || value < 0.0 || value > 1.0)(
          s"$label has truthConfidence outside [0,1]"
        )
      ),
      annotations.recallUtility.flatMap(value =>
        Option.when(!value.isFinite || value < 0.0 || value > 1.0)(
          s"$label has recallUtility outside [0,1]"
        )
      )
    )

  private def annotationIris(annotations: AxiomAnnotations): List[Iri] =
    annotations.knowledgeScope.toList ++ annotations.provenance.reference.toList

  private def literalDatatype(axiom: Axiom): Option[Iri] = axiom match
    case Axiom.DataAssertion(_, _, value) => Some(value.datatype)
    case _                                => None

  private def validateIri(label: String, iri: Iri): List[String] =
    Iri.parse(iri.value).left.toOption.toList.map(problem =>
      s"$label contains invalid IRI '${iri.value}': $problem"
    )
