package dev.librecybernetics.noesis.vocab

import java.util.Locale

import dev.librecybernetics.noesis.core.policy.TermPolicy
import dev.librecybernetics.noesis.logic.*

/** The shipped vocabulary, as something the owner can look through (SPEC §5.1, PRODUCT.md F1).
  *
  * §1.2 assumed an LLM would translate what the owner said into terms, so no browsing surface was
  * ever specified for the structured path — and the structured path is the one that exists. The
  * result is a system that asks the owner to know `crm:birthday` and will not tell them what it is.
  *
  * Everything here is read back out of the module contract rather than transcribed. A module that
  * declares a term therefore documents it by declaring it, and there is no second list to forget.
  */
object Vocabulary:

  enum Role:
    case Class, Property

  /** One term, with everything the owner needs to decide whether it is the one they mean. */
  final case class Term(
      iri: Iri,
      module: String,
      role: Role,
      /** What a subject of this property must be, or what this class specializes. */
      domain: List[Iri],
      /** What a value of this property must be. Its absence is why a rangeless object property is
        * mistaken for a data property (the `spouseOf "marco"` trap).
        */
      range: List[Iri],
      /** How the verbalizer reads it back, when the module supplies a template. */
      template: Option[String],
      sensitivity: Sensitivity,
      utility: Double,
      /** Escalation applied on top of the default, e.g. a health note going `sensitive`. */
      escalatesTo: Option[Sensitivity],
      /** Captured as a fluent, so asserting it opens a state rather than adding a fact (§3.6). */
      timeVarying: Boolean
  )

  /** Every term the installed modules declare, sorted so the listing is stable. */
  def of(modules: List[Module]): List[Term] =
    modules.flatMap(terms).sortBy(term => (term.module, term.iri.value))

  /** Every term an axiom mentions, in any position.
    *
    * Exhaustive on purpose: a new axiom case has to say which terms it names before those terms can
    * be listed, rather than silently going undocumented.
    */
  private def named(axiom: Axiom): List[Iri] = axiom match
    case Axiom.SubClassOf(a, b)           => List(a, b)
    case Axiom.DisjointClasses(a, b)      => List(a, b)
    case Axiom.SubPropertyOf(a, b)        => List(a, b)
    case Axiom.InverseProperties(a, b)    => List(a, b)
    case Axiom.SymmetricProperty(p)       => List(p)
    case Axiom.TransitiveProperty(p)      => List(p)
    case Axiom.IrreflexiveProperty(p)     => List(p)
    case Axiom.PropertyChain(chain, up)   => chain.map(_.property) :+ up
    case Axiom.PropertyDomain(p, c)       => List(p, c)
    case Axiom.PropertyRange(p, c)        => List(p, c)
    case Axiom.TimeVarying(p)             => List(p)
    case Axiom.ClassAssertion(i, c)       => List(i, c)
    case Axiom.ObjectAssertion(s, p, o)   => List(s, p, o)
    case Axiom.DataAssertion(s, p, _)     => List(s, p)
    case Axiom.SameIndividual(a, b)       => List(a, b)
    case Axiom.DifferentIndividuals(a, b) => List(a, b)

  private def terms(module: Module): List[Term] =
    // All four seams of the module contract, not just the ontology — the same reason the naming
    // register reads all four. A term a module only ever mentions in a policy or a template is
    // still a term the owner can use, and `crm:healthNote`, whose whole point is that it escalates
    // to `sensitive`, is declared in exactly that way.
    val mentioned = module.ontology.flatMap(named)
      ++ module.policies.byProperty.keys ++ module.policies.byClass.keys
      ++ module.itemPolicies.byProperty.keys ++ module.itemPolicies.byClass.keys
      ++ module.templates.byProperty.keys ++ module.templates.byClass.keys

    // Compacted, because storage is absolute and a module owns a prefix rather than a URL shape.
    val declared = mentioned.toList.distinct.filter(_.display.startsWith(s"${module.prefix}:"))

    declared.map: iri =>
      val policy = module.policies.byProperty
        .get(iri)
        .orElse(module.policies.byClass.get(iri))
        .getOrElse(TermPolicy.empty)
      val defaults = module.policies.modules.get(module.prefix)

      Term(
        iri = iri,
        module = module.prefix,
        role = if isProperty(module.ontology, iri) then Role.Property else Role.Class,
        domain = collected(module.ontology) { case Axiom.PropertyDomain(p, c) if p == iri => c }
          ++ collected(module.ontology) { case Axiom.SubClassOf(s, c) if s == iri => c },
        range = collected(module.ontology) { case Axiom.PropertyRange(p, c) if p == iri => c },
        template = module.templates.byProperty.get(iri).orElse(module.templates.byClass.get(iri)),
        sensitivity = policy.sensitivity
          .orElse(defaults.map(_.sensitivity))
          .getOrElse(Sensitivity.Personal),
        utility = policy.recallUtility.orElse(defaults.map(_.utilityWeight)).getOrElse(0.5),
        escalatesTo = policy.escalateTo,
        timeVarying = module.ontology.contains(Axiom.TimeVarying(iri))
      )

  private def collected(ontology: List[Axiom])(select: PartialFunction[Axiom, Iri]): List[Iri] =
    ontology.collect(select).distinct

  /** A term used in any property position is a property; the rest are classes. */
  private def isProperty(ontology: List[Axiom], iri: Iri): Boolean =
    ontology.exists:
      case Axiom.PropertyDomain(p, _)     => p == iri
      case Axiom.PropertyRange(p, _)      => p == iri
      case Axiom.SubPropertyOf(a, b)      => a == iri || b == iri
      case Axiom.InverseProperties(a, b)  => a == iri || b == iri
      case Axiom.SymmetricProperty(p)     => p == iri
      case Axiom.TransitiveProperty(p)    => p == iri
      case Axiom.IrreflexiveProperty(p)   => p == iri
      case Axiom.TimeVarying(p)           => p == iri
      case Axiom.PropertyChain(chain, up) => up == iri || chain.exists(_.property == iri)
      case _                              => false

  /** Terms whose name or template mentions `query`, most specific match first.
    *
    * Matching the template as well as the name is what makes the search answer the question the
    * owner actually has: they know they want to record a birthday, not that the term contains the
    * substring "birth".
    */
  def search(terms: List[Term], query: String): List[Term] =
    val needle = query.toLowerCase(Locale.ROOT).trim
    if needle.isEmpty then Nil
    else
      terms
        .filter: term =>
          term.iri.local.toLowerCase(Locale.ROOT).contains(needle) ||
            term.template.exists(_.toLowerCase(Locale.ROOT).contains(needle))
        .sortBy: term =>
          val local = term.iri.local.toLowerCase(Locale.ROOT)
          (if local == needle then 0 else if local.contains(needle) then 1 else 2, term.iri.value)

  /** The term written as `crm:birthday` or as a bare local name. */
  def find(terms: List[Term], name: String): Option[Term] =
    val wanted = name.toLowerCase(Locale.ROOT).trim
    terms
      .find(term => term.iri.display.toLowerCase(Locale.ROOT) == wanted)
      .orElse(terms.find(_.iri.local.toLowerCase(Locale.ROOT) == wanted))

  /** How the term is used, so the listing answers "what do I type?" and not only "what is it?".
    *
    * A time-varying property is shown as an assertion rather than as `open`, because §3.6's sugar
    * means asserting one is how a state is opened — showing the explicit form would teach the
    * harder path for no reason.
    */
  def example(term: Term): String = term.role match
    case Role.Class    => s"noesis assert <entity> rdf:type ${term.iri.display}"
    case Role.Property => s"noesis assert <subject> ${term.iri.display} <${valueHint(term)}>"

  private def valueHint(term: Term): String =
    term.range.headOption.map(_.local).getOrElse("value")
