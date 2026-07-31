package dev.librecybernetics.noesis.cli

import java.util.Locale

import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.journal.Turtle
import dev.librecybernetics.noesis.core.module.AgendaEntry
import dev.librecybernetics.noesis.core.policy.DisclosureDecision
import dev.librecybernetics.noesis.core.projection.KbState
import dev.librecybernetics.noesis.core.verbalize.Verbalizer
import dev.librecybernetics.noesis.lms.{Item, QueueEntry}
import dev.librecybernetics.noesis.vocab.{ContactCard, FollowUpDue, ReminderDue}

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
          if effective.scopes.isEmpty then ""
          else effective.scopes.map(_.value).toList.sorted.mkString(" (", ", ", ")")
        s"  ✓ ${verbalizer.verbalize(a)}  [${effective.level.toString.toLowerCase(Locale.ROOT)}$scopes]"
      case DisclosureDecision.Redact(reason) =>
        s"  ✗ ${decision.marker} — $reason"

  /** Turtle export of the current semantic graph (SPEC §10 interoperability).
    *
    * The serialization itself lives in `noesis-journal`, which owns reading and writing alike; the
    * CLI's job is only to choose what to export. The prefix block used to be maintained here by
    * hand and had drifted from the bindings it claimed to mirror — declaring `core:` and `vf:` as
    * namespaces those prefixes no longer meant, and never declaring `xsd:` at all.
    */
  def turtle(state: KbState): String =
    Turtle.write(dev.librecybernetics.noesis.core.projection.Projections.current(state).triples.toList)

  def contactCard(card: ContactCard, verbalizer: Verbalizer): String =
    val heading =
      s"${card.displayName}  <${card.contact.value}>  [${card.completeness.toString.toLowerCase(Locale.ROOT)}]"
    val birthday = card.birthday.toList.map(date => s"  birthday: ${date.render}")
    val methods =
      if card.methods.isEmpty then List("  contact methods: (none)")
      else
        "  contact methods:" :: card.methods.map: method =>
          val metadata = List(
            method.label,
            method.purpose,
            method.rank.map(rank => s"rank $rank")
          ).flatten
          val suffix = if metadata.isEmpty then "" else metadata.mkString(" [", ", ", "]")
          s"    ${method.kind}: ${method.value}$suffix"
    val employments =
      if card.employments.isEmpty then Nil
      else
        "  employment:" :: card.employments.map: employment =>
          val role = employment.title.fold("")(title => s" — $title")
          s"    ${verbalizer.label(employment.organization)}$role"
    val interactions =
      if card.recentInteractions.isEmpty then Nil
      else
        "  recent interactions:" :: card.recentInteractions.map: interaction =>
          val summary = interaction.summary.fold("")(value => s" — $value")
          s"    ${interaction.occurred.render} · ${interaction.channel}$summary"
    (heading :: birthday ++ methods ++ employments ++ interactions).mkString("\n")

  def contactAgenda(
      followUps: List[FollowUpDue],
      reminders: List[ReminderDue],
      verbalizer: Verbalizer
  ): String =
    val followUpLines =
      if followUps.isEmpty then List("  (none)")
      else followUps.map: entry =>
        val marker = if entry.overdue then "!" else " "
        s"  $marker ${entry.due}  follow up with ${verbalizer.label(entry.contact)}"
    val reminderLines =
      if reminders.isEmpty then List("  (none)")
      else reminders.map: entry =>
        s"    ${entry.due.render}  ${entry.occasion} — ${verbalizer.label(entry.contact)}"
    ("follow-ups:" :: followUpLines) ++ ("" :: "reminders:" :: reminderLines) mkString "\n"

  def agenda(entries: List[AgendaEntry], verbalizer: Verbalizer): String =
    if entries.isEmpty then "agenda:\n  (nothing due)"
    else
      val lines = entries.map: entry =>
        val marker = if entry.overdue then "!" else " "
        s"  $marker ${entry.due}  ${entry.summary} — ${verbalizer.label(entry.subject)}"
      "agenda:\n" + lines.mkString("\n")
