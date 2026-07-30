package noesis.cli

import java.util.Locale

import noesis.logic.*
import noesis.core.policy.DisclosureDecision
import noesis.core.projection.KbState
import noesis.core.verbalize.Verbalizer
import noesis.lms.{Item, QueueEntry}

/** Terminal rendering.
  *
  * Everything the owner sees goes through the verbalizer rather than showing raw IRIs, which is what
  * makes the naming policy (§7.2 — always the current name) apply to the CLI as much as to a UI.
  */
object Render:

  def axiom(verbalizer: Verbalizer, a: Axiom): String = verbalizer.verbalize(a)

  /** The three views SPEC §3.5.5 requires at confirmation: NL, structure, raw Manchester. */
  def confirmable(verbalizer: Verbalizer, a: Axiom): String =
    s"""  ${verbalizer.verbalize(a)}
       |    id:         ${a.id.value}
       |    manchester: ${a.manchester}""".stripMargin

  def fluent(verbalizer: Verbalizer, f: Fluent): String =
    val marker = if f.isOngoing then "●" else "○"
    s"  $marker ${verbalizer.verbalize(f)}"

  /** An entity page: current facts, states, and their belief tint (SPEC §7.4). */
  def entity(
      verbalizer: Verbalizer,
      state: KbState,
      target: Iri,
      beliefs: Map[AxiomId, Double]
  ): String =
    val (records, fluents) = state.about(target)
    val heading = s"${verbalizer.label(target)}  <${target.value}>"

    val factLines =
      if records.isEmpty then List("  (no asserted facts)")
      else
        records.sortBy(_.assertedAt).map: record =>
          val tint = beliefs.get(record.id).fold("     ")(b => f"[${b}%.2f]")
          s"  $tint ${verbalizer.verbalize(record.axiom)}"

    val stateLines =
      if fluents.isEmpty then Nil
      else "" :: "  states:" :: fluents.toList.sortBy(_.id.value).map(fluent(verbalizer, _))

    (heading :: "" :: factLines) ++ stateLines mkString "\n"

  def queueEntry(index: Int, entry: QueueEntry): String =
    f"""  ${index + 1}%2d. [${entry.mode}%-11s] w=${entry.weight}%.3f b=${entry.belief}%.2f u=${entry.utility}%.2f
       |      ${entry.item.prompt}
       |      why: ${entry.reason}""".stripMargin

  def item(i: Item): String =
    val status = if i.suspended then "suspended" else "active"
    f"""  ${i.id.value}  [$status, ${i.kind}, ${i.origin}]
       |    belief=${i.belief}%.2f stability=${i.stability}%.1fd reviews=${i.reviewCount} lapses=${i.lapseCount}
       |    ${i.prompt}""".stripMargin

  def disclosure(
      verbalizer: Verbalizer,
      a: Axiom,
      decision: DisclosureDecision
  ): String =
    decision match
      case DisclosureDecision.Disclose(effective) =>
        val scopes =
          if effective.scopes.isEmpty then "" else effective.scopes.map(_.value).mkString(" (", ", ", ")")
        s"  ✓ ${verbalizer.verbalize(a)}  [${effective.level.toString.toLowerCase(Locale.ROOT)}$scopes]"
      case DisclosureDecision.Redact(reason) =>
        s"  ✗ ${decision.marker} — $reason"

  /** Turtle export (SPEC §10: full export anytime, no lock-in). */
  def turtle(state: KbState): String =
    val prefixes = List(
      "@prefix rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
      "@prefix rdfs:  <http://www.w3.org/2000/01/rdf-schema#> .",
      "@prefix owl:   <http://www.w3.org/2002/07/owl#> .",
      "@prefix core:  <https://noesis.local/core#> .",
      "@prefix crm:   <https://noesis.local/crm#> .",
      "@prefix ll:    <https://noesis.local/ll#> .",
      "@prefix vf:    <https://w3id.org/valueflows#> .",
      "@prefix noesis: <https://noesis.local/> ."
    )

    val triples = noesis.core.projection.Projections
      .current(state)
      .triples
      .toList
      .sortBy(t => (t.subject.value, t.property.value, t.obj.render))
      .map(t => s"${term(t.subject)} ${term(t.property)} ${node(t.obj)} .")

    (prefixes ++ List("") ++ triples).mkString("\n")

  private def term(iri: Iri): String =
    if iri.value.startsWith("noesis:e/") then s"<${iri.value}>" else iri.value

  private def node(n: Node): String = n match
    case Node.Ref(iri) => term(iri)
    case Node.Lit(Literal.Str(v, None))       => s"\"${escape(v)}\""
    case Node.Lit(Literal.Str(v, Some(lang))) => s"\"${escape(v)}\"@$lang"
    case Node.Lit(Literal.Num(v))             => v.toString
    case Node.Lit(Literal.Bool(v))            => v.toString
    case Node.Lit(Literal.Date(v))            => s"\"${v.render}\"^^<http://www.w3.org/2001/XMLSchema#date>"
    case Node.Lit(Literal.Time(v))            => s"\"$v\"^^<http://www.w3.org/2001/XMLSchema#dateTime>"

  private def escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
