package noesis.vocab

import noesis.core.model.*
import noesis.core.policy.{ModuleDefaults, PolicyBook, TermPolicy}
import noesis.core.reason.{ClosureView, ReasonerConfig, Rule}
import noesis.core.verbalize.Templates
import noesis.lms.{ItemPolicy, ItemPolicyBook}

/** Polyglot language learning (SPEC §6).
  *
  * The load-bearing decision is the interlingual hub-and-spoke: meanings are language-neutral
  * `Concept` nodes and words are per-language `Lexeme`s, so translation is a traversal `Lexeme →
  * Concept → Lexeme` and never a word→word edge. That is what makes the representation scale
  * linearly in the number of languages and makes quizzing from *any* base language trivial rather
  * than requiring a new edge type per pair.
  */
object LanguageModule extends Module:
  val prefix = "ll"
  val version = "0.1.0"

  val Language: Iri = iri("Language")
  val Concept: Iri = iri("Concept")
  val Lexeme: Iri = iri("Lexeme")
  val WordForm: Iri = iri("WordForm")
  val GrammarTopic: Iri = iri("GrammarTopic")
  val GrammarRule: Iri = iri("GrammarRule")
  val ExampleSentence: Iri = iri("ExampleSentence")

  val lexicalizes: Iri = iri("lexicalizes")
  val lexicalizedBy: Iri = iri("lexicalizedBy")
  val inLanguage: Iri = iri("inLanguage")
  val register: Iri = iri("register")
  val hasForm: Iri = iri("hasForm")
  val formValue: Iri = iri("formValue")
  val cognateOf: Iri = iri("cognateOf")
  val falseFriendOf: Iri = iri("falseFriendOf")
  val confusableWith: Iri = iri("confusableWith")
  val derivesFrom: Iri = iri("derivesFrom")
  val contrastsWith: Iri = iri("contrastsWith")
  val appliesTo: Iri = iri("appliesTo")

  /** A concept two lexemes both lexicalize — the interlingual hub. */
  val translationOf: Iri = iri("translationOf")

  val ontology: List[Axiom] = List(
    Axiom.PropertyDomain(lexicalizes, Lexeme),
    Axiom.PropertyRange(lexicalizes, Concept),
    Axiom.InverseProperties(lexicalizes, lexicalizedBy),
    Axiom.PropertyDomain(inLanguage, Lexeme),
    Axiom.PropertyRange(inLanguage, Language),
    Axiom.PropertyDomain(hasForm, Lexeme),
    Axiom.PropertyRange(hasForm, WordForm),

    // Cross-linguistic relations are symmetric: if perro is a cognate of chien, the reverse holds.
    Axiom.SymmetricProperty(cognateOf),
    Axiom.SymmetricProperty(falseFriendOf),
    Axiom.SymmetricProperty(confusableWith),
    Axiom.SymmetricProperty(contrastsWith),
    // Nothing is its own false friend or confusable with itself.
    Axiom.IrreflexiveProperty(falseFriendOf),
    Axiom.IrreflexiveProperty(confusableWith),
    Axiom.IrreflexiveProperty(translationOf),

    // Translation is derived, never asserted directly (see translationRule).
    Axiom.PropertyChain(
      List(ChainStep(lexicalizes), ChainStep(lexicalizes, inverse = true)),
      translationOf
    ),
    Axiom.SymmetricProperty(translationOf),
    Axiom.PropertyDomain(contrastsWith, GrammarTopic)
  )

  /** Wrong answers that land on a neighbouring lexeme create `confusableWith` pairs (SPEC §6).
    *
    * Modeled here as a rule over what the KB already knows: two lexemes in the same language that
    * lexicalize concepts sharing a false-friend link are confusable. The *behavioral* half — pairs
    * created from actual wrong answers — is a capture operation the learning engine emits, not an
    * inference, so it is not part of this rule.
    */
  val confusabilityRule: Rule = new Rule:
    val name = "ll:confusableWith"

    def derive(view: ClosureView)(using ReasonerConfig) =
      for
        ((a, b), j1) <- view.objectByProperty
          .getOrElse(falseFriendOf, Nil)
          .map((s, o, js) => ((s, o), js))
          .iterator
        (concept, j2) <- view.objectBySubjectProperty.getOrElse((b, lexicalizes), Nil).iterator
        (sibling, j3) <- view.objectByProperty
          .getOrElse(lexicalizes, Nil)
          .collect { case (s, o, js) if o == concept && s != b && s != a => (s, js) }
          .iterator
      yield Axiom.ObjectAssertion(a, confusableWith, sibling) ->
        Rule.combineAll(Seq(j1, j2, j3))

  override val rules: List[Rule] = List(confusabilityRule)

  override val policies: PolicyBook = PolicyBook.empty
    // Vocabulary is public knowledge, and the whole point is to hold it in memory.
    .withModule(ModuleDefaults(prefix, Sensitivity.Public, utilityWeight = 0.85))
    .withProperty(lexicalizes, TermPolicy.utility(0.9))
    .withProperty(formValue, TermPolicy.utility(0.8))
    // False friends are high-value precisely because they are actively misleading (SPEC §6).
    .withProperty(falseFriendOf, TermPolicy.utility(0.95))
    .withProperty(confusableWith, TermPolicy.utility(0.9))
    .withProperty(cognateOf, TermPolicy.utility(0.5))

  override val itemPolicies: ItemPolicyBook = ItemPolicyBook.empty
    .withProperty(lexicalizes, ItemPolicy.AutoActivate)
    .withProperty(hasForm, ItemPolicy.AutoActivate)
    .withProperty(formValue, ItemPolicy.AutoActivate)
    .withProperty(falseFriendOf, ItemPolicy.AutoActivate)
    .withProperty(confusableWith, ItemPolicy.AutoActivate)
    // Etymology is context, not something to be drilled.
    .withProperty(derivesFrom, ItemPolicy.Ignore)

  override val templates: Templates = Templates.empty
    .withProperty(lexicalizes, "{s} means {o}")
    .withProperty(inLanguage, "{s} is a word in {o}")
    .withProperty(hasForm, "{s} has the form {o}")
    .withProperty(formValue, "{s} is written {o}")
    .withProperty(cognateOf, "{s} is a cognate of {o}")
    .withProperty(falseFriendOf, "{s} is a false friend of {o} — they look alike but differ in meaning")
    .withProperty(confusableWith, "{s} is easily confused with {o}")
    .withProperty(translationOf, "{s} translates {o}")
    .withProperty(contrastsWith, "{s} contrasts with {o}")
    .withClass(Lexeme, "{s} is a word")
    .withClass(Concept, "{s} is a meaning")

  /** Belief priors from cross-linguistic structure (SPEC §6, §4.2).
    *
    * Cognates start high because they are nearly free; false friends start low *and* high-priority
    * because the learner's instinct is actively wrong, which is the case worth drilling.
    */
  def priorFor(axiom: Axiom): Option[Double] = axiom match
    case Axiom.ObjectAssertion(_, p, _) if p == cognateOf      => Some(0.8)
    case Axiom.ObjectAssertion(_, p, _) if p == falseFriendOf  => Some(0.15)
    case Axiom.ObjectAssertion(_, p, _) if p == confusableWith => Some(0.2)
    case _                                                     => None

  /** The skills a language item is keyed by, per direction (SPEC §6 belief tensor).
    *
    * Producing *собака* from French is not implied by recognizing it from English, so mastery is per
    * (concept, direction, skill) rather than per word.
    */
  enum Skill:
    case Recognition
    case Production
    case FormProduction
    case Listening
    case Spelling

  /** A belief-tensor key: which concept, in which direction, for which skill. */
  final case class MasteryKey(concept: Iri, from: Iri, to: Iri, skill: Skill):
    def render: String = s"${concept.local}:${from.local}→${to.local}:$skill"
