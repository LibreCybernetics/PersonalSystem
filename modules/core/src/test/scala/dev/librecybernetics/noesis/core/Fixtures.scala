package dev.librecybernetics.noesis.core

import cats.effect.IO
import cats.effect.std.UUIDGen
import dev.librecybernetics.noesis.journal.InMemoryJournal
import dev.librecybernetics.noesis.core.kb.{KbConfig, KnowledgeBase}
import dev.librecybernetics.noesis.logic.*

/** Shared vocabulary and builders for the core tests.
  *
  * The IRIs mirror the modules from SPEC §7 and §8 so the tests exercise the shapes the spec
  * actually calls for — a time-varying `worksAt`, a symmetric `knows`, the `colleagueOf` chain —
  * rather than abstract `p`/`q` placeholders that could pass while the real vocabulary breaks.
  */
object Fixtures:
  given cats.effect.std.SecureRandom[IO] = cats.effect.std.SecureRandom.javaSecuritySecureRandom[IO].unsafeRunSync()(using cats.effect.unsafe.implicits.global)
  given UUIDGen[IO] = UUIDGen.fromSecureRandom[IO]

  // ── Vocabulary ─────────────────────────────────────────────────────────────
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
  val hasName: Iri = Iri("crm:hasName")
  val ancestorOf: Iri = Iri("crm:ancestorOf")
  val salary: Iri = Iri("vf:salary")

  // ── Individuals (readable stand-ins for opaque UUIDs) ──────────────────────
  val alice: Iri = Iri("noesis:e/alice")
  val marco: Iri = Iri("noesis:e/marco")
  val sarah: Iri = Iri("noesis:e/sarah")
  val lia: Iri = Iri("noesis:e/lia")
  val acme: Iri = Iri("noesis:e/acme")
  val molina: Iri = Iri("noesis:e/molina")

  val orgAcme: Iri = Iri("org:acme")

  /** The schema the relationship module would install (SPEC §7.1). */
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
    // The spec's RBox default: worksAt ∘ worksAt⁻ ⊑ colleagueOf
    Axiom.PropertyChain(List(ChainStep(worksAt), ChainStep(worksAt, inverse = true)), colleagueOf)
  )

  def kb(config: KbConfig = KbConfig.default): IO[KnowledgeBase[IO]] =
    InMemoryJournal.create[IO].flatMap(KnowledgeBase[IO](_, config))

  extension (record: projection.AxiomRecord) def axiomOf: Axiom = record.axiom
