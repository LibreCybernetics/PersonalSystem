package dev.librecybernetics.noesis.reasoner

import dev.librecybernetics.noesis.logic.*

/** Formal vocabulary shared by the reasoner and query contract tests. */
object Fixtures:
  val Person: Iri = Iri("crm:Person")
  val Organization: Iri = Iri("crm:Organization")
  val Agent: Iri = Iri("crm:Agent")

  val worksAt: Iri = Iri("crm:worksAt")
  val colleagueOf: Iri = Iri("crm:colleagueOf")
  val knows: Iri = Iri("crm:knows")
  val friendOf: Iri = Iri("crm:friendOf")
  val partnerOf: Iri = Iri("crm:partnerOf")
  val spouseOf: Iri = Iri("crm:spouseOf")
  val parentOf: Iri = Iri("crm:parentOf")
  val childOf: Iri = Iri("crm:childOf")
  val birthday: Iri = Iri("crm:birthday")
  val ancestorOf: Iri = Iri("crm:ancestorOf")

  val alice: Iri = Iri("noesis:e/alice")
  val marco: Iri = Iri("noesis:e/marco")
  val sarah: Iri = Iri("noesis:e/sarah")
  val lia: Iri = Iri("noesis:e/lia")
  val acme: Iri = Iri("noesis:e/acme")
  val molina: Iri = Iri("noesis:e/molina")

  val crmSchema: List[Axiom] = List(
    Axiom.SubClassOf(Person, Agent),
    Axiom.SubClassOf(Organization, Agent),
    Axiom.TimeVarying(worksAt),
    Axiom.PropertyDomain(worksAt, Person),
    Axiom.PropertyRange(worksAt, Organization),
    Axiom.SymmetricProperty(knows),
    Axiom.SubPropertyOf(friendOf, knows),
    Axiom.SubPropertyOf(spouseOf, partnerOf),
    Axiom.SubPropertyOf(partnerOf, knows),
    Axiom.InverseProperties(parentOf, childOf),
    Axiom.IrreflexiveProperty(colleagueOf),
    Axiom.PropertyChain(
      List(ChainStep(worksAt), ChainStep(worksAt, inverse = true)),
      colleagueOf
    )
  )
