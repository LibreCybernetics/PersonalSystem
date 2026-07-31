package dev.librecybernetics.noesis.vocab

import dev.librecybernetics.noesis.core.kb.{KbConfig, StateValidator}
import dev.librecybernetics.noesis.core.module.{AgendaProducer, DocumentExporter, DocumentImporter}
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.core.policy.PolicyBook
import dev.librecybernetics.noesis.reasoner.Rule
import dev.librecybernetics.noesis.core.verbalize.{Naming, Templates}
import dev.librecybernetics.noesis.lms.ItemPolicyBook

/** The module contract (SPEC §5.1).
  *
  * A module is a *value*, not a plugin with lifecycle hooks: it declares an ontology fragment, rules,
  * annotation policies, item policies and verbalization templates, and installing it is merging
  * those into one configuration. Module facts are full citizens — same journal, same annotations,
  * same reasoning, same belief — so specialization is vocabulary plus generators, never a parallel
  * store.
  */
trait Module:
  /** The namespace prefix this module owns, e.g. `crm`. */
  def prefix: String

  def version: String

  /** The TBox/RBox fragment this module installs. Shown as a diff for approval (SPEC §5.1). */
  def ontology: List[Axiom]

  /** Domain inference rules beyond the core RDFS set. */
  def rules: List[Rule] = Nil

  /** Sensitivity and utility defaults (SPEC §3.3). */
  def policies: PolicyBook = PolicyBook.empty

  /** Which axioms become learning items (SPEC §4.1). */
  def itemPolicies: ItemPolicyBook = ItemPolicyBook.empty

  /** Natural-language templates (SPEC §5.2). */
  def templates: Templates = Templates.empty

  /** Structured naming paths followed by the generic verbalizer (SPEC §5.1, §7.2). */
  def namingSchemes: List[Naming.Scheme] = Nil

  /** Domain record-shape checks run against the pre-commit scratch projection. */
  def validators: List[StateValidator] = Nil

  /** Reviewable document import adapters. */
  def importers: List[DocumentImporter] = Nil

  /** Disclosure-aware document export adapters. */
  def exporters: List[DocumentExporter] = Nil

  /** Agenda projections; their entries are never persisted as duplicate facts. */
  def agendaProducers: List[AgendaProducer] = Nil

  def iri(local: String): Iri = Iri(s"$prefix:$local")

/** Installs modules into a single configuration.
  *
  * The merged TBox must stay consistent (SPEC §5.1), which is checked by committing the ontology
  * through the ordinary commit path rather than by a separate validation step — the same consistency
  * pre-flight that guards every other write.
  */
object Modules:
  /** Folds a module's declarations into a knowledge base configuration. */
  def configure(base: KbConfig, modules: List[Module]): KbConfig =
    modules.foldLeft(base): (config, module) =>
      config
        .withRules(module.rules)
        .withPolicies(module.policies)
        .withTemplates(module.templates)
        .withNamingSchemes(module.namingSchemes)
        .withValidators(module.validators)

  /** The combined item policies of a set of modules. */
  def itemPolicies(modules: List[Module]): ItemPolicyBook =
    modules.foldLeft(ItemPolicyBook.empty)((book, module) => book ++ module.itemPolicies)

  /** The combined ontology, in installation order. */
  def ontology(modules: List[Module]): List[Axiom] = modules.flatMap(_.ontology)

  def importers(modules: List[Module]): List[DocumentImporter] = modules.flatMap(_.importers)

  def exporters(modules: List[Module]): List[DocumentExporter] = modules.flatMap(_.exporters)

  def agendaProducers(modules: List[Module]): List[AgendaProducer] =
    modules.flatMap(_.agendaProducers)

  /** Every module the MVP ships. */
  val all: List[Module] = List(CoreModule, RelationshipsModule, LanguageModule, ResourcesModule)
