package noesis.cli

import java.util.Locale

import noesis.logic.*
import noesis.journal.Turtle
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

  /** Turtle export (SPEC §10: full export anytime, no lock-in).
    *
    * The serialization itself lives in `noesis-journal`, which owns reading and writing alike; the
    * CLI's job is only to choose what to export. The prefix block used to be maintained here by
    * hand and had drifted from the bindings it claimed to mirror — declaring `core:` and `vf:` as
    * namespaces those prefixes no longer meant, and never declaring `xsd:` at all.
    */
  def turtle(state: KbState): String =
    Turtle.write(noesis.core.projection.Projections.current(state).triples.toList)
