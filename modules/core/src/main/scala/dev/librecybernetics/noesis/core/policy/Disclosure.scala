package dev.librecybernetics.noesis.core.policy

import java.util.Locale

import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.core.projection.KbState
import dev.librecybernetics.noesis.reasoner.{Closure, Justification, Support}

/** What an external party is allowed to see (SPEC §3.3.1, §9).
  *
  * One policy per LLM provider, MCP agent, export or sync target. The default is `public` only:
  * §9 makes that the starting grant for every agent, so a misconfigured or newly-authorized agent
  * sees the least, not the most.
  */
final case class DisclosurePolicy(
    name: String,
    maxLevel: Sensitivity = Sensitivity.Public,
    /** Granted `internal(scope)` knowledge scopes — an `org:acme` grant exposes that org only. */
    grantedScopes: Set[Iri] = Set.empty,
    /** True for the owner's own UI and local models, where no system boundary is crossed at all.
      *
      * This is not "max level = sensitive": §3.3.1 says `sensitive` never leaves the device, so no
      * amount of granting can disclose it. The distinction is whether egress happens, not how much
      * was permitted.
      */
    local: Boolean = false
):
  /** Does this policy permit a single fact at `level` scoped to `scopes`? */
  def permits(level: Sensitivity, scopes: Set[Iri]): Boolean =
    if local then true
    else
      level match
        // Never leaves the device unencrypted, never to a remote LLM, never over MCP (SPEC §3.3.1).
        case Sensitivity.Sensitive => false
        case Sensitivity.Internal =>
          maxLevel.rank >= Sensitivity.Internal.rank && scopes.nonEmpty &&
            scopes.subsetOf(grantedScopes)
        case other => maxLevel.rank >= other.rank

object DisclosurePolicy:
  /** The default for a freshly authorized agent (SPEC §9). */
  def publicOnly(name: String): DisclosurePolicy = DisclosurePolicy(name)

  /** The owner's own UI or a local model: nothing crosses the boundary. */
  def localOwner(name: String): DisclosurePolicy =
    DisclosurePolicy(name, Sensitivity.Sensitive, Set.empty, local = true)

  /** An agent granted `personal` access — an explicit per-agent grant (SPEC §3.3.1). */
  def personal(name: String): DisclosurePolicy =
    DisclosurePolicy(name, Sensitivity.Personal)

  /** An agent granted `internal` access to specific knowledge scopes only. */
  def internal(name: String, scopes: Set[Iri]): DisclosurePolicy =
    DisclosurePolicy(name, Sensitivity.Internal, scopes)

/** The disclosure level a fact effectively carries, and why. */
final case class EffectiveDisclosure(
    level: Sensitivity,
    scopes: Set[Iri],
    /** The justification that produced this level — the most disclosable derivation path. */
    via: Justification
)

/** The outcome of filtering one fact. */
enum DisclosureDecision:
  case Disclose(effective: EffectiveDisclosure)

  /** Withheld. `marker` is what §9 requires be shown in place of the value. */
  case Redact(reason: String)

  def isDisclosed: Boolean = this match
    case Disclose(_) => true
    case Redact(_)   => false

  def marker: String = this match
    case Disclose(_)      => ""
    case Redact(_)        => "[redacted]"

/** Resolves the sensitivity of asserted axioms and fluent-backed triples.
  *
  * Fluents carry their own annotations, so a materialized triple is governed by the fluent's label
  * rather than defaulting to the floor — otherwise closing and reopening a state would silently
  * change its sensitivity.
  */
final class SupportResolver(state: KbState, book: PolicyBook):
  def levelOf(support: Support): (Sensitivity, Set[Iri]) = support match
    case Support.Asserted(id) =>
      state
        .axiom(id)
        .map: record =>
          val effective = PolicyCascade.resolve(record, book)
          (effective.sensitivity, effective.knowledgeScope)
        // An unknown premise is treated as maximally sensitive: failing closed is the only safe
        // default when the thing being classified cannot be found.
        .getOrElse((Sensitivity.Sensitive, Set.empty))

    case Support.FromFluent(id) =>
      state
        .fluent(id)
        .map: fluent =>
          (
            fluent.annotations.sensitivity.getOrElse(Sensitivity.Personal),
            fluent.annotations.knowledgeScope
          )
        .getOrElse((Sensitivity.Sensitive, Set.empty))

/** The derived-fact disclosure rule (SPEC §3.3.1).
  *
  * > Derived facts: disclosable under a policy iff at least one justification is *fully*
  * > disclosable under it; effective level = `min over justifications (max over axioms)`, internal
  * > scopes unioning within the chosen justification. A conclusion derivable from public facts
  * > alone is public, whatever other derivation paths exist.
  *
  * The asymmetry is the point: `max` within a justification because you need *all* its premises, so
  * the worst one governs; `min` across justifications because you need only *one* path, so the best
  * one governs.
  */
object Disclosure:

  /** Restricts a closure to the derivation paths a policy may receive.
    *
    * Keeping only the passing justifications matters as much as filtering conclusions: support
    * identifiers are provenance, and handing an exporter a permitted fact together with a
    * non-permitted derivation would violate the same boundary through metadata (DESIGN data
    * minimization).
    */
  def restrict(
      closure: Closure,
      resolver: SupportResolver,
      policy: DisclosurePolicy
  ): Closure =
    val facts = closure.facts.flatMap: (axiom, justifications) =>
      val permitted = justifications.filter(_.complete).filter: justification =>
        val effective = effectiveFor(justification, resolver)
        policy.permits(effective.level, effective.scopes)
      Option.when(permitted.nonEmpty)(axiom -> permitted)
    closure.copy(
      facts = facts,
      inheritedIncompleteReasons = closure.incompleteReasons
    )

  /** The effective level of `axiom` given its justifications, or `None` if it has none. */
  def effectiveLevel(
      justifications: Set[Justification],
      resolver: SupportResolver
  ): Option[EffectiveDisclosure] =
    val perJustification = justifications.toList.map(effectiveFor(_, resolver))

    // min over justifications, preferring the narrower scope set when levels tie
    perJustification.minByOption(d => (d.level.rank, d.scopes.size, d.via.size))

  private def effectiveFor(
      justification: Justification,
      resolver: SupportResolver
  ): EffectiveDisclosure =
    val premises = justification.premises.toList.map(resolver.levelOf)
    val level = premises.map(_._1).foldLeft(Sensitivity.Public)(Sensitivity.max)
    // Scopes union only within the chosen justification: an `internal` conclusion is internal to
    // every org that contributed a premise to *that* derivation.
    val scopes = premises.filter(_._1 == Sensitivity.Internal).flatMap(_._2).toSet
    EffectiveDisclosure(level, scopes, justification)

  /** Decides disclosure for one axiom under one policy. */
  def decide(
      axiom: Axiom,
      closure: Closure,
      resolver: SupportResolver,
      policy: DisclosurePolicy
  ): DisclosureDecision =
    val justifications = closure.justificationsFor(axiom)
    val complete = justifications.filter(_.complete)
    if complete.isEmpty && closure.contains(axiom) then
      DisclosureDecision.Redact("provenance incomplete")
    else if complete.isEmpty then DisclosureDecision.Redact("not entailed")
    else
      // "at least one justification is fully disclosable": test every path, not just the minimal
      // one, because a policy's scope grants can make a higher-level path pass where the
      // nominally-minimal one fails.
      val passing = complete.toList.flatMap: justification =>
        effectiveLevel(Set(justification), resolver).filter(d => policy.permits(d.level, d.scopes))

      passing.minByOption(d => (d.level.rank, d.scopes.size, d.via.size)) match
        case Some(effective) => DisclosureDecision.Disclose(effective)
        case None =>
          // Guarded by `justifications.nonEmpty` above, so an effective level necessarily exists.
          // Keeping an unreachable fallback here obscures a privacy-sensitive invariant.
          val actual = effectiveLevel(complete, resolver).toList
            .map: d =>
              s"requires ${d.level.toString.toLowerCase(Locale.ROOT)}" +
                (if d.scopes.nonEmpty then
                   d.scopes.map(_.value).toList.sorted.mkString("(", ", ", ")")
                 else "")
            .mkString
          DisclosureDecision.Redact(
            actual
          )

  /** Filters a set of axioms down to what `policy` may see.
    *
    * Every read surface routes through this — including SPARQL results and justifications (SPEC §9
    * invariant 2) — so there is no path that filters entities but forgets to filter explanations.
    */
  def filter(
      axioms: Iterable[Axiom],
      closure: Closure,
      resolver: SupportResolver,
      policy: DisclosurePolicy
  ): List[(Axiom, EffectiveDisclosure)] =
    axioms.toList.flatMap: axiom =>
      decide(axiom, closure, resolver, policy) match
        case DisclosureDecision.Disclose(effective) => Some(axiom -> effective)
        case DisclosureDecision.Redact(_)           => None

  /** Partitions into disclosable facts and redaction markers, for surfaces that must show that
    * something was withheld rather than silently shortening the list.
    */
  def partition(
      axioms: Iterable[Axiom],
      closure: Closure,
      resolver: SupportResolver,
      policy: DisclosurePolicy
  ): (List[(Axiom, EffectiveDisclosure)], List[(Axiom, String)]) =
    val decisions = axioms.toList.map(a => a -> decide(a, closure, resolver, policy))
    val disclosed = decisions.collect { case (a, DisclosureDecision.Disclose(e)) => a -> e }
    val redacted = decisions.collect { case (a, DisclosureDecision.Redact(reason)) => a -> reason }
    (disclosed, redacted)
