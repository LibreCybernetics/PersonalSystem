package noesis.vocab

import noesis.core.kb.KbConfig
import noesis.core.model.*
import noesis.core.policy.PolicyBook
import noesis.core.reason.Rule
import noesis.core.verbalize.Templates
import noesis.lms.ItemPolicyBook

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

  /** The combined item policies of a set of modules. */
  def itemPolicies(modules: List[Module]): ItemPolicyBook =
    modules.foldLeft(ItemPolicyBook.empty)((book, module) => book ++ module.itemPolicies)

  /** The combined ontology, in installation order. */
  def ontology(modules: List[Module]): List[Axiom] = modules.flatMap(_.ontology)

  /** Every module the MVP ships. */
  val all: List[Module] = List(CoreModule, RelationshipsModule, LanguageModule, ResourcesModule)
