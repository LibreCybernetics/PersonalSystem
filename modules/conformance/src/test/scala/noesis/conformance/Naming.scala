package noesis.conformance

import noesis.logic.*
import noesis.vocab.Modules

/** What a name in Noesis names, in ISO/IEC 11179-5's terms.
  *
  * §9.5 and §9.6 make naming rules role-dependent — a class and a property are named differently —
  * so conformance cannot be checked without knowing which role a term is declared in. The roles are
  * derived from the axioms themselves rather than from a hand-kept list, because a list is a second
  * place to forget a term.
  */
enum Role:
  case Class, Property, Individual, Datatype

object Naming:

  /** Every term the shipped vocabularies declare, with the role each is declared in.
    *
    * A term declared in two roles appears twice; that is a punning error and the suite says so
    * rather than picking one.
    *
    * All four seams of the module contract are read, not just the ontology: a term that a module
    * only ever mentions in a policy, an item policy or a template is still a name this project
    * assigned, and naming it badly is not excused by its absence from an axiom.
    */
  def declared: List[(Iri, Role)] =
    Modules.all.flatMap: module =>
      module.ontology.flatMap(roles)
        ++ keyed(module.policies.byClass.keys, module.policies.byProperty.keys)
        ++ keyed(module.itemPolicies.byClass.keys, module.itemPolicies.byProperty.keys)
        ++ keyed(module.templates.byClass.keys, module.templates.byProperty.keys)
    ++ logicDeclared

  private def keyed(classes: Iterable[Iri], properties: Iterable[Iri]): List[(Iri, Role)] =
    classes.map(_ -> Role.Class).toList ++ properties.map(_ -> Role.Property).toList

  /** The `core:` and W3C terms `noesis-logic` declares as constants rather than as axioms.
    *
    * Listed by hand because they are `val`s, not data — the exhaustive match in [[roles]] cannot
    * reach them. The compiler cannot catch an omission here, so anything added to `Vocab`,
    * `CoreDatatype`, `Xsd` or `Rdf` belongs in this list too.
    */
  private def logicDeclared: List[(Iri, Role)] =
    List(
      Vocab.Agent -> Role.Class,
      Vocab.Person -> Role.Class,
      Vocab.Organization -> Role.Class,
      Vocab.Fluent -> Role.Class,
      Vocab.me -> Role.Individual,
      Vocab.timeVarying -> Role.Property,
      Vocab.rdfType -> Role.Property,
      Vocab.subClassOf -> Role.Property,
      Vocab.subPropertyOf -> Role.Property,
      Vocab.label -> Role.Property,
      Rdf.langString -> Role.Datatype,
      Xsd.string -> Role.Datatype,
      Xsd.boolean -> Role.Datatype,
      Xsd.decimal -> Role.Datatype,
      Xsd.integer -> Role.Datatype,
      Xsd.date -> Role.Datatype,
      Xsd.dateTime -> Role.Datatype,
      Xsd.gYear -> Role.Datatype,
      Xsd.gYearMonth -> Role.Datatype,
      Xsd.gMonthDay -> Role.Datatype,
      Xsd.gMonth -> Role.Datatype,
      Xsd.gDay -> Role.Datatype
    )

  /** The role each position of an axiom declares. Exhaustive on purpose: a new axiom case has to
    * say what its terms are before it can be named.
    */
  private def roles(axiom: Axiom): List[(Iri, Role)] = axiom match
    case Axiom.SubClassOf(a, b)            => List(a -> Role.Class, b -> Role.Class)
    case Axiom.DisjointClasses(a, b)       => List(a -> Role.Class, b -> Role.Class)
    case Axiom.SubPropertyOf(a, b)         => List(a -> Role.Property, b -> Role.Property)
    case Axiom.InverseProperties(a, b)     => List(a -> Role.Property, b -> Role.Property)
    case Axiom.SymmetricProperty(p)        => List(p -> Role.Property)
    case Axiom.TransitiveProperty(p)       => List(p -> Role.Property)
    case Axiom.IrreflexiveProperty(p)      => List(p -> Role.Property)
    case Axiom.PropertyChain(chain, sup)   => chain.map(_.property -> Role.Property) :+ (sup -> Role.Property)
    case Axiom.PropertyDomain(p, c)        => List(p -> Role.Property, c -> Role.Class)
    case Axiom.PropertyRange(p, c)         => List(p -> Role.Property, c -> Role.Class)
    case Axiom.TimeVarying(p)              => List(p -> Role.Property)
    case Axiom.ClassAssertion(i, c)        => List(i -> Role.Individual, c -> Role.Class)
    case Axiom.ObjectAssertion(s, p, o)    => List(s -> Role.Individual, p -> Role.Property, o -> Role.Individual)
    case Axiom.DataAssertion(s, p, _)      => List(s -> Role.Individual, p -> Role.Property)
    case Axiom.SameIndividual(a, b)        => List(a -> Role.Individual, b -> Role.Individual)
    case Axiom.DifferentIndividuals(a, b)  => List(a -> Role.Individual, b -> Role.Individual)

  /** The pattern a convention documents for a role, or `None` if it documents none — which means
    * the namespace admits no term in that role at all.
    */
  def pattern(convention: NamingConvention, role: Role): Option[String] = role match
    case Role.Class      => convention.classPattern
    case Role.Property   => convention.propertyPattern
    case Role.Individual => convention.individualPattern
    case Role.Datatype   => convention.datatypePattern
