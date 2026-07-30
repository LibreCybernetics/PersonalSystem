package noesis.core.verbalize

import java.util.Locale

import noesis.logic.*
import noesis.core.projection.KbState

/** Natural-language templates contributed by modules (SPEC §5.1, §5.2).
  *
  * Placeholders are `{s}`, `{o}` and `{p}`. Keeping templates as data rather than code means a
  * module declares them in its manifest and the verbalizer needs no module-specific branches.
  */
final case class Templates(
    byProperty: Map[Iri, String] = Map.empty,
    byClass: Map[Iri, String] = Map.empty
):
  def withProperty(property: Iri, template: String): Templates =
    copy(byProperty = byProperty.updated(property, template))

  def withClass(cls: Iri, template: String): Templates =
    copy(byClass = byClass.updated(cls, template))

  def ++(other: Templates): Templates =
    Templates(byProperty ++ other.byProperty, byClass ++ other.byClass)

object Templates:
  val empty: Templates = Templates()

/** Resolves entities to the names they should be shown under (SPEC §7.2).
  *
  * The rule the spec is emphatic about: always the *current* name, including when talking about past
  * periods ("Alice worked at Acme in early 2026"). That is why naming reads ongoing fluents rather
  * than whatever name was attached when a fact was recorded — and why former names, which default to
  * `sensitive`, never reach this map.
  */
final case class NamingContext(labels: Map[Iri, String]):
  def label(iri: Iri): String = labels.getOrElse(iri, fallback(iri))

  /** Opaque entity IRIs have no readable form, so an unnamed one shows a short handle. */
  private def fallback(iri: Iri): String =
    if iri.isOpaque then s"⟨${iri.local.take(8)}⟩" else Naming.humanize(iri.local)

object Naming:
  /** Properties whose ongoing fluent value is the entity's display name, most preferred first. */
  val defaultNamingProperties: List[Iri] = List(Iri("crm:hasName"), Vocab.label)

  /** Builds a naming context from the current state.
    *
    * Ongoing fluents win over plain label assertions: a rename is a supersession (§3.6), so the open
    * fluent is by construction the current name while the closed one is a former name.
    */
  def from(
      state: KbState,
      namingProperties: List[Iri] = defaultNamingProperties
  ): NamingContext =
    val fromLabels =
      state.activeAxioms.collect:
        case r @ noesis.core.projection.AxiomRecord(_, Axiom.DataAssertion(s, p, v), _, _, _)
            if namingProperties.contains(p) =>
          val priority = namingProperties.indexOf(p)
          (s, (priority + 100, v.text, r.assertedAt))

    val fromFluents =
      state.ongoingFluents.collect:
        case f if namingProperties.contains(f.statedProperty) =>
          val priority = namingProperties.indexOf(f.statedProperty)
          (f.statedSubject, (priority, f.statedValue.render, 0L))

    // Lower priority number wins; fluent-backed names outrank plain assertions.
    val preference = Ordering.by[(Int, String, Long), (Int, Long, String)]: candidate =>
      (candidate._1, -candidate._3, candidate._2)
    val best = (fromFluents ++ fromLabels)
      .groupMapReduce(_._1)(_._2)(preference.min)

    NamingContext(best.view.mapValues(_._2).toMap)

  /** `worksAt` → `works at`, `hasName` → `has name`, `falseFriendOf` → `false friend of`. */
  def humanize(camel: String): String =
    camel
      .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
      .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
      .toLowerCase(Locale.ROOT)

/** Axiom → natural language (SPEC §5.2).
  *
  * Template-first with no LLM: §5.2 specifies "template-first with LLM fallback", and the MVP
  * implements the template half. Every axiom form has a readable rendering, so confirmation,
  * browsing and quiz text all work without a model in the loop — which also means the privacy gate
  * has nothing to gate here.
  */
final class Verbalizer(naming: NamingContext, templates: Templates = Templates.empty):

  def apply(axiom: Axiom): String = verbalize(axiom)

  /** The name this entity should be shown under — always the current one (SPEC §7.2).
    *
    * Exposed because quiz prompts and queue listings need entity names without a whole axiom to
    * render, and they must not reach for the IRI, which would leak opaque UUIDs and former names.
    */
  def label(iri: Iri): String = naming.label(iri)

  def verbalize(axiom: Axiom): String = axiom match
    case Axiom.ObjectAssertion(s, p, o) =>
      fill(p, naming.label(s), naming.label(o))

    case Axiom.DataAssertion(s, p, v) =>
      fill(p, naming.label(s), v.text)

    case Axiom.ClassAssertion(individual, cls) =>
      templates.byClass.get(cls) match
        case Some(template) => template.replace("{s}", naming.label(individual))
        case None           => s"${naming.label(individual)} is ${article(cls)} ${naming.label(cls)}"

    case Axiom.SubClassOf(sub, sup) =>
      s"every ${naming.label(sub)} is ${article(sup)} ${naming.label(sup)}"

    case Axiom.DisjointClasses(a, b) =>
      s"nothing is both ${article(a)} ${naming.label(a)} and ${article(b)} ${naming.label(b)}"

    case Axiom.SubPropertyOf(sub, sup) =>
      s"${Naming.humanize(sub.local)} implies ${Naming.humanize(sup.local)}"

    case Axiom.InverseProperties(a, b) =>
      s"${Naming.humanize(a.local)} is the inverse of ${Naming.humanize(b.local)}"

    case Axiom.SymmetricProperty(p) =>
      s"${Naming.humanize(p.local)} goes both ways"

    case Axiom.TransitiveProperty(p) =>
      s"${Naming.humanize(p.local)} chains transitively"

    case Axiom.IrreflexiveProperty(p) =>
      s"nothing ${Naming.humanize(p.local)} itself"

    case Axiom.PropertyChain(steps, sup) =>
      val path = steps
        .map(s => Naming.humanize(s.property.local) + (if s.inverse then " (reversed)" else ""))
        .mkString(", then ")
      s"$path implies ${Naming.humanize(sup.local)}"

    case Axiom.PropertyDomain(p, cls) =>
      s"only ${plural(naming.label(cls))} can ${Naming.humanize(p.local)}"

    case Axiom.PropertyRange(p, cls) =>
      s"${Naming.humanize(p.local)} always points at ${article(cls)} ${naming.label(cls)}"

    case Axiom.TimeVarying(p) =>
      s"${Naming.humanize(p.local)} changes over time"

    case Axiom.SameIndividual(a, b) =>
      s"${naming.label(a)} is the same as ${naming.label(b)}"

    case Axiom.DifferentIndividuals(a, b) =>
      s"${naming.label(a)} is not ${naming.label(b)}"

  /** Verbalizes a fluent with its temporal boundaries, as the confirmation UI shows them. */
  def verbalize(fluent: Fluent): String =
    val core = fill(
      fluent.statedProperty,
      naming.label(fluent.statedSubject),
      fluent.statedValue match
        case Node.Ref(iri) => naming.label(iri)
        case Node.Lit(lit) => lit.text
    )
    (fluent.validFrom.map(_.render), fluent.validTo.map(_.render)) match
      case (Some(from), Some(to)) => s"$core (from $from to $to)"
      case (Some(from), None)     => s"$core (since $from)"
      case (None, Some(to))       => s"$core (until $to)"
      case (None, None)           => core

  private def fill(property: Iri, subject: String, obj: String): String =
    templates.byProperty.get(property) match
      case Some(template) =>
        template.replace("{s}", subject).replace("{o}", obj).replace("{p}", property.local)
      case None => s"$subject ${Naming.humanize(property.local)} $obj"

  private def article(cls: Iri): String =
    val label = naming.label(cls)
    if label.nonEmpty && "aeiou".contains(label.toLowerCase(Locale.ROOT).head) then "an" else "a"

  private def plural(label: String): String =
    if label.endsWith("s") then label else s"${label}s"
