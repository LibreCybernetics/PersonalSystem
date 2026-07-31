package dev.librecybernetics.noesis.journal

import dev.librecybernetics.noesis.logic.*

/** Semantic values used to exercise the persisted operation protocol. */
object JournalFixtures:
  val Person: Iri = Iri("crm:Person")
  val worksAt: Iri = Iri("crm:worksAt")
  val alice: Iri = Iri("noesis:e/alice")
  val acme: Iri = Iri("noesis:e/acme")
  val molina: Iri = Iri("noesis:e/molina")
  val orgAcme: Iri = Iri("org:acme")
