package noesis.vocab

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.std.{SecureRandom, UUIDGen}
import cats.syntax.all.*
import munit.CatsEffectSuite
import noesis.core.capture.Intent
import noesis.journal.InMemoryJournal
import noesis.core.kb.{KbConfig, KnowledgeBase}
import noesis.logic.*
import noesis.core.policy.{DisclosurePolicy, PolicyCascade}
import noesis.core.projection.AxiomRecord
import noesis.reasoner.query.PatternSyntax
import noesis.lms.{ItemOrigin, ItemPolicy, LearningEngine, QueueMode}

/** Module tests (SPEC §5–§8).
  *
  * These are integration tests on purpose: a module's whole claim is that its vocabulary works with
  * the *unmodified* core — same journal, same reasoning, same annotations, same belief — so testing
  * the declarations in isolation would prove nothing about the thing that matters.
  */
class ModuleSuite extends CatsEffectSuite:

  given SecureRandom[IO] = SecureRandom.javaSecuritySecureRandom[IO].unsafeRunSync()(using cats.effect.unsafe.implicits.global)
  given UUIDGen[IO] = UUIDGen.fromSecureRandom[IO]

  private val alice = Iri("noesis:e/alice")
  private val marco = Iri("noesis:e/marco")
  private val sarah = Iri("noesis:e/sarah")
  private val lia = Iri("noesis:e/lia")
  private val acme = Iri("noesis:e/acme")
  private val molina = Iri("noesis:e/molina")
  private val dogConcept = Iri("noesis:e/c-dog")
  private val esPerro = Iri("noesis:e/lex-perro")
  private val enDog = Iri("noesis:e/lex-dog")
  private val ruSobaka = Iri("noesis:e/lex-sobaka")
  private val ruMagazin = Iri("noesis:e/lex-magazin")
  private val enMagazine = Iri("noesis:e/lex-magazine")
  private val drill = Iri("noesis:e/drill")
  private val lendEvent = Iri("noesis:e/ev-lend")
  private val returnEvent = Iri("noesis:e/ev-return")
  private val me = CoreModule.me
  private val oldName = Iri("noesis:e/name-adam")
  private val newName = Iri("noesis:e/name-alice")

  private val modules = Modules.all
  private val config = Modules.configure(KbConfig.default, modules)

  /** A knowledge base with every module's ontology installed, as a real install would leave it. */
  private def installed: IO[KnowledgeBase[IO]] =
    for
      journal <- InMemoryJournal.create[IO]
      base <- KnowledgeBase[IO](journal, config)
      ontology = Modules.ontology(modules).distinct
      result <- base.commit(NonEmptyList.fromListUnsafe(ontology.map(Intent.Assert(_))))
      _ <- IO.raiseWhen(result.isLeft)(
        new AssertionError(s"module ontology failed to install: $result")
      )
    yield base

  private def engineFor(base: KnowledgeBase[IO]): IO[LearningEngine[IO]] =
    LearningEngine[IO](base, Modules.itemPolicies(modules), config.policies)

  // ── The module contract (SPEC §5.1) ───────────────────────────────────────

  test("every module's ontology installs, and the merged TBox is consistent"):
    installed.flatMap: base =>
      base.inconsistencies.map: problems =>
        assertEquals(problems, Nil, s"the merged module TBox is inconsistent: $problems")

  test("modules contribute rules, policies, naming, validation, interchange and agenda"):
    assert(config.rules.length > noesis.reasoner.RdfsRules.all.length, "no module rules merged")
    assert(config.policies.modules.keySet == Set("core", "crm", "ll", "vf"), config.policies.modules.keySet.toString)
    assert(config.templates.byProperty.contains(RelationshipsModule.birthday))
    assert(config.namingSchemes.nonEmpty, "structured naming scheme was not merged")
    assert(config.validators.contains(PrmValidation), "PRM validator was not merged")
    assert(Modules.importers(modules).flatMap(_.formats).contains("vcard"))
    assert(Modules.exporters(modules).flatMap(_.formats).contains("foaf"))
    assert(Modules.agendaProducers(modules).contains(PrmAgenda))

  test("module prefixes are distinct, so namespaces cannot collide"):
    assertEquals(modules.map(_.prefix).distinct.length, modules.length)

  // ── Relationships (SPEC §7) ───────────────────────────────────────────────

  test("crm:Person aligns with core:Person, so modules share one notion of a person"):
    installed.flatMap: base =>
      base.entails(Axiom.SubClassOf(RelationshipsModule.Person, CoreModule.Person)).map(assert(_))

  test("vf:Agent and core:Agent are equivalent, so the vf Marco is the crm Marco"):
    installed.flatMap: base =>
      for
        forward <- base.entails(Axiom.SubClassOf(ResourcesModule.Agent, CoreModule.Agent))
        backward <- base.entails(Axiom.SubClassOf(CoreModule.Agent, ResourcesModule.Agent))
      yield
        assert(forward, "vf:Agent ⊑ core:Agent missing")
        assert(backward, "core:Agent ⊑ vf:Agent missing")

  test("spouseOf implies partnerOf implies knows, through the module's RBox"):
    for
      base <- installed
      _ <- base.assert(Axiom.ObjectAssertion(sarah, RelationshipsModule.spouseOf, marco))
      partner <- base.entails(Axiom.ObjectAssertion(sarah, RelationshipsModule.partnerOf, marco))
      knows <- base.entails(Axiom.ObjectAssertion(sarah, RelationshipsModule.knows, marco))
      symmetric <- base.entails(Axiom.ObjectAssertion(marco, RelationshipsModule.knows, sarah))
    yield
      assert(partner, "spouseOf ⊑ partnerOf did not fire")
      assert(knows, "partnerOf ⊑ knows did not fire")
      assert(symmetric, "knows should be symmetric")

  test("the social relationship properties declare a range, so they are typed as object properties"):
    // Without a declared range there is nothing marking these as relating entities rather than
    // literals, and a caller resolving `spouseOf marco` has to guess — it guessed wrong, storing the
    // string "marco" instead of a reference to Marco.
    installed.flatMap: base =>
      base.closure.map: closure =>
        val ranges = closure.view.ranges
        val social = List(
          RelationshipsModule.knows,
          RelationshipsModule.friendOf,
          RelationshipsModule.partnerOf,
          RelationshipsModule.spouseOf,
          RelationshipsModule.siblingOf,
          RelationshipsModule.chosenFamilyOf,
          RelationshipsModule.parentOf,
          RelationshipsModule.childOf,
          RelationshipsModule.colleagueOf,
          RelationshipsModule.metamourOf
        )
        val undeclared = social.filterNot(ranges.contains)
        assertEquals(undeclared.map(_.local), Nil, "these would be mistaken for data properties")

  test("knowing an organization is consistent, since the social properties range over Agent"):
    // A narrower `Person` range would make this an inconsistency via the core Person/Organization
    // disjointness, which is why the range is Agent.
    for
      base <- installed
      _ <- base.assert(Axiom.ClassAssertion(acme, RelationshipsModule.Organization))
      result <- base.assert(Axiom.ObjectAssertion(alice, RelationshipsModule.knows, acme))
      problems <- base.inconsistencies
    yield
      assert(result.isRight, s"knowing an organization was rejected: $result")
      assertEquals(problems, Nil)

  test("partnerOf is cardinality-free: concurrent partners are consistent, not an error"):
    // SPEC §7.1 is explicit that this must not be modeled as functional.
    for
      base <- installed
      result <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ObjectAssertion(alice, RelationshipsModule.partnerOf, marco)),
          Intent.Assert(Axiom.ObjectAssertion(alice, RelationshipsModule.partnerOf, sarah))
        )
      )
      problems <- base.inconsistencies
    yield
      assert(result.isRight, s"concurrent partners were rejected: $result")
      assertEquals(problems, Nil)

  test("relationship kinds are not disjoint: a co-parent can also be a friend and an ex"):
    for
      base <- installed
      result <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ObjectAssertion(alice, RelationshipsModule.friendOf, marco)),
          Intent.Assert(Axiom.ObjectAssertion(alice, RelationshipsModule.parentOf, lia)),
          Intent.Assert(Axiom.ObjectAssertion(marco, RelationshipsModule.parentOf, lia))
        )
      )
      problems <- base.inconsistencies
    yield
      assert(result.isRight, result.toString)
      assertEquals(problems, Nil)

  test("parentOf and childOf are inverses with no gender or arity assumption"):
    for
      base <- installed
      _ <- base.assert(Axiom.ObjectAssertion(sarah, RelationshipsModule.parentOf, lia))
      _ <- base.assert(Axiom.ObjectAssertion(marco, RelationshipsModule.parentOf, lia))
      childOfSarah <- base.entails(Axiom.ObjectAssertion(lia, RelationshipsModule.childOf, sarah))
      childOfMarco <- base.entails(Axiom.ObjectAssertion(lia, RelationshipsModule.childOf, marco))
    yield
      assert(childOfSarah && childOfMarco, "two parents should both be inferred")

  test("the metamour rule derives a partner's partner, excluding self and direct partners"):
    // SPEC §7.1: metamourOf ← partnerOf ∘ partnerOf − (partnerOf ∪ identity)
    for
      base <- installed
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ObjectAssertion(alice, RelationshipsModule.partnerOf, marco)),
          Intent.Assert(Axiom.ObjectAssertion(marco, RelationshipsModule.partnerOf, sarah))
        )
      )
      metamour <- base.entails(Axiom.ObjectAssertion(alice, RelationshipsModule.metamourOf, sarah))
      notSelf <- base.entails(Axiom.ObjectAssertion(alice, RelationshipsModule.metamourOf, alice))
      notDirect <- base.entails(Axiom.ObjectAssertion(alice, RelationshipsModule.metamourOf, marco))
    yield
      assert(metamour, "alice should be sarah's metamour through marco")
      assert(!notSelf, "nobody is their own metamour")
      assert(!notDirect, "a direct partner is not a metamour")

  test("colleagueOf is derived from a shared employer and nobody is their own colleague"):
    for
      base <- installed
      _ <- base.assert(Axiom.ObjectAssertion(alice, RelationshipsModule.worksAt, acme))
      _ <- base.assert(Axiom.ObjectAssertion(marco, RelationshipsModule.worksAt, acme))
      colleagues <- base.entails(Axiom.ObjectAssertion(alice, RelationshipsModule.colleagueOf, marco))
      selfLoop <- base.entails(Axiom.ObjectAssertion(alice, RelationshipsModule.colleagueOf, alice))
      problems <- base.inconsistencies
    yield
      assert(colleagues)
      assert(!selfLoop)
      assertEquals(problems, Nil, "the chain must not create an irreflexivity violation")

  test("active Employment derives worksAt with journal-backed premises"):
    val employment = Iri("noesis:e/employment-alice-acme")
    for
      base <- installed
      result <- base.commit(
        PrmCapture.employment(EmploymentInput(employment, alice, acme))
      )
      _ = result.fold(rejected => fail(rejected.render), identity)
      works <- base.entails(Axiom.ObjectAssertion(alice, RelationshipsModule.worksAt, acme))
      explanation <- base.explain(Axiom.ObjectAssertion(alice, RelationshipsModule.worksAt, acme))
      state <- base.state
    yield
      assert(works)
      assertEquals(state.ongoingFluents.size, 1, "employment status should be the only fluent")
      val premises = explanation.toList.flatMap(_.justifications).flatMap(_.premises)
      assertEquals(premises.size, 3, "employee, employer, and active status must justify worksAt")

  test("a person asserted to work at a person is rejected via range disjointness"):
    // worksAt range is Organization, Person and Organization are disjoint in core.
    for
      base <- installed
      _ <- base.assert(Axiom.ClassAssertion(marco, RelationshipsModule.Person))
      rejected <- base.assert(Axiom.ObjectAssertion(alice, RelationshipsModule.worksAt, marco))
    yield assert(rejected.isLeft, s"expected rejection, got $rejected")

  test("crm defaults sensitivity to personal and utility high"):
    val axiom = Axiom.ObjectAssertion(alice, RelationshipsModule.friendOf, marco)
    val record = AxiomRecord(axiom.id, axiom, AxiomAnnotations.empty, AxiomStatus.Active, 1L)

    assertEquals(PolicyCascade.sensitivity(record, config.policies), Sensitivity.Personal)
    assert(PolicyCascade.recallUtility(record, config.policies) >= 0.85)

  test("health notes auto-escalate to sensitive and never leave the device"):
    val axiom =
      Axiom.DataAssertion(marco, RelationshipsModule.healthNote, Literal.string("allergic to nuts"))
    val record = AxiomRecord(axiom.id, axiom, AxiomAnnotations.empty, AxiomStatus.Active, 1L)

    assertEquals(PolicyCascade.sensitivity(record, config.policies), Sensitivity.Sensitive)

    for
      base <- installed
      _ <- base.assert(axiom)
      decision <- base.disclosureOf(axiom, DisclosurePolicy.personal("agent"))
    yield assert(!decision.isDisclosed, "a health note was disclosable to an agent")

  test("contact data is below the suspend threshold, so it is stored but not quizzed"):
    val method = Iri("noesis:e/marco-phone")
    val axiom =
      Axiom.DataAssertion(method, RelationshipsModule.contactValue, Literal.string("+52 555 1234"))
    val record = AxiomRecord(axiom.id, axiom, AxiomAnnotations.empty, AxiomStatus.Active, 1L)

    assert(
      PolicyCascade.recallUtility(record, config.policies) < PolicyCascade.suspendThreshold,
      "phone numbers should not be memory material"
    )
    assertEquals(Modules.itemPolicies(modules).policyFor(axiom), ItemPolicy.Ignore)

  test("birthdays are auto-activated as learning items and verbalize readably"):
    val axiom =
      Axiom.DataAssertion(lia, RelationshipsModule.birthday, Literal.date(PartialDate.monthDay(5, 12)))
    assertEquals(Modules.itemPolicies(modules).policyFor(axiom), ItemPolicy.AutoActivate)

    for
      base <- installed
      _ <- base.assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Lía")))
      text <- base.verbalize(axiom)
    yield assertEquals(text, "Lía's birthday is --05-12")

  // ── The §7.3 capture scenario, end to end ─────────────────────────────────

  test("the spec's lunch-with-Sarah bundle commits as one confirmable unit"):
    // "Had lunch with Sarah and her husband Marco; she just started at Molina Labs;
    //  their daughter Lía turns 5 on May 12"  (SPEC §7.3)
    for
      base <- installed
      engine <- engineFor(base)
      employment = Iri("noesis:e/employment-sarah-molina")
      employmentIntents = PrmCapture.employment(
        EmploymentInput(employment, sarah, molina)
      ).toList
      result <- base.commit(
        NonEmptyList.fromListUnsafe(List(
          Intent.Assert(Axiom.ClassAssertion(sarah, RelationshipsModule.Person)),
          Intent.Assert(Axiom.ClassAssertion(marco, RelationshipsModule.Person)),
          Intent.Assert(Axiom.ClassAssertion(lia, RelationshipsModule.Person)),
          Intent.Assert(Axiom.ClassAssertion(molina, RelationshipsModule.Organization)),
          Intent.Assert(Axiom.ObjectAssertion(sarah, RelationshipsModule.spouseOf, marco)),
          Intent.Assert(Axiom.ObjectAssertion(sarah, RelationshipsModule.parentOf, lia)),
          Intent.Assert(Axiom.ObjectAssertion(marco, RelationshipsModule.parentOf, lia)),
          Intent.Assert(
            Axiom.DataAssertion(
              lia,
              RelationshipsModule.birthday,
              Literal.date(PartialDate.monthDay(5, 12))
            )
          )
        ) ++ employmentIntents)
      )
      commit = result.fold(r => fail(r.render), identity)
      _ <- engine.handle(commit.events)
      state <- base.state
      items <- engine.items
      // Marco is Lía's parent and Sarah's spouse, so he is derivable as knowing Sarah
      knows <- base.entails(Axiom.ObjectAssertion(marco, RelationshipsModule.knows, sarah))
    yield
      assertEquals(
        state.ongoingFluents.size,
        1,
        "the active employment-status fluent should have been opened"
      )
      assert(knows)
      assert(items.exists(_.prompt.contains("birthday")), items.map(_.prompt).toString)
      assert(items.exists(!_.suspended), "auto-activated items should be active")

  // ── Renames (SPEC §7.2) ───────────────────────────────────────────────────

  test("a rename is one supersession and the highest-priority change item"):
    for
      base <- installed
      engine <- engineFor(base)
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(oldName, RelationshipsModule.Name)),
          Intent.Assert(
            Axiom.DataAssertion(oldName, RelationshipsModule.nameValue, Literal.string("Adam"))
          ),
          Intent.OpenState(alice, RelationshipsModule.hasName, Node.Ref(oldName))
        )
      )
      renamed <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(newName, RelationshipsModule.Name)),
          Intent.Assert(
            Axiom.DataAssertion(newName, RelationshipsModule.nameValue, Literal.string("Alice"))
          ),
          Intent.Supersede(
            alice,
            RelationshipsModule.hasName,
            Node.Ref(newName),
            Some(PartialDate.of(2026, 5, 1))
          )
        )
      )
      commit = renamed.fold(r => fail(r.render), identity)
      items <- engine.handle(commit.events)
      state <- base.state
    yield
      assertEquals(state.ongoingFluents.size, 1, "only the new name should be current")
      val changeItems = items.filter(_.origin == ItemOrigin.StateChange)
      assertEquals(changeItems.length, 1)
      val change = changeItems.headOption.getOrElse(fail("expected a rename change item"))
      assertEquals(change.priorityBoost, 1.0, "a rename must be top priority (§7.2)")

  test("a fluent-backed change item is scheduled at its property's utility, not a default"):
    // hasName and pronouns are time-varying, so their facts live in fluents and have no AxiomRecord.
    // If the cascade cannot see them, a rename lands at a neutral 0.5 rather than the top priority
    // §7.2 requires.
    for
      base <- installed
      engine <- engineFor(base)
      renamed <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(oldName, RelationshipsModule.Name)),
          Intent.Assert(
            Axiom.DataAssertion(oldName, RelationshipsModule.nameValue, Literal.string("Adam"))
          ),
          Intent.Assert(Axiom.ClassAssertion(newName, RelationshipsModule.Name)),
          Intent.Assert(
            Axiom.DataAssertion(newName, RelationshipsModule.nameValue, Literal.string("Alice"))
          ),
          Intent.OpenState(alice, RelationshipsModule.hasName, Node.Ref(oldName)),
          Intent.Supersede(
            alice,
            RelationshipsModule.hasName,
            Node.Ref(newName),
            Some(PartialDate.of(2026, 5, 1))
          )
        )
      )
      _ <- engine.handle(renamed.fold(r => fail(r.render), _.events))
      queue <- engine.queue(QueueMode.Mixed, limit = 10)
      nameEntry = queue.find(_.item.origin == ItemOrigin.StateChange)
    yield
      val entry = nameEntry.getOrElse(fail(s"no change item queued: ${queue.map(_.item.prompt)}"))
      assertEquals(entry.utility, 1.0, "hasName should resolve to the crm policy's utility of 1.0")

  test("after a rename the verbalizer uses the new name, including about the past"):
    for
      base <- installed
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(oldName, RelationshipsModule.Name)),
          Intent.Assert(
            Axiom.DataAssertion(oldName, RelationshipsModule.nameValue, Literal.string("Adam"))
          ),
          Intent.OpenState(alice, RelationshipsModule.hasName, Node.Ref(oldName))
        )
      )
      _ <- base.assert(Axiom.ObjectAssertion(alice, RelationshipsModule.worksAt, acme))
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(newName, RelationshipsModule.Name)),
          Intent.Assert(
            Axiom.DataAssertion(newName, RelationshipsModule.nameValue, Literal.string("Alice"))
          ),
          Intent.Supersede(
            alice,
            RelationshipsModule.hasName,
            Node.Ref(newName),
            Some(PartialDate.of(2026, 5, 1))
          )
        )
      )
      text <- base.verbalize(Axiom.ObjectAssertion(alice, RelationshipsModule.worksAt, acme))
    yield
      assert(text.startsWith("Alice works at"), s"expected the current name, got: $text")
      assert(!text.contains("Adam"), s"a former name leaked: $text")

  // ── Language learning (SPEC §6) ───────────────────────────────────────────

  test("translation is a traversal through the concept, not a word-to-word edge"):
    // c:DOG ← es:perro, en:dog, ru:собака  (SPEC §6)
    for
      base <- installed
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(dogConcept, LanguageModule.Concept)),
          Intent.Assert(Axiom.ObjectAssertion(esPerro, LanguageModule.lexicalizes, dogConcept)),
          Intent.Assert(Axiom.ObjectAssertion(enDog, LanguageModule.lexicalizes, dogConcept)),
          Intent.Assert(Axiom.ObjectAssertion(ruSobaka, LanguageModule.lexicalizes, dogConcept))
        )
      )
      esToRu <- base.entails(Axiom.ObjectAssertion(esPerro, LanguageModule.translationOf, ruSobaka))
      ruToEn <- base.entails(Axiom.ObjectAssertion(ruSobaka, LanguageModule.translationOf, enDog))
      selfTranslation <- base.entails(
        Axiom.ObjectAssertion(esPerro, LanguageModule.translationOf, esPerro)
      )
    yield
      // Any base language reaches any target without a per-pair edge having been asserted.
      assert(esToRu, "es→ru translation should be derivable through the shared concept")
      assert(ruToEn, "ru→en translation should be derivable through the shared concept")
      assert(!selfTranslation, "a lexeme should not translate itself")

  test("adding a fourth language needs one edge, not one per existing pair"):
    for
      base <- installed
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ObjectAssertion(esPerro, LanguageModule.lexicalizes, dogConcept)),
          Intent.Assert(Axiom.ObjectAssertion(enDog, LanguageModule.lexicalizes, dogConcept)),
          Intent.Assert(Axiom.ObjectAssertion(ruSobaka, LanguageModule.lexicalizes, dogConcept))
        )
      )
      frChien = Iri("noesis:e/lex-chien")
      // One assertion...
      _ <- base.assert(Axiom.ObjectAssertion(frChien, LanguageModule.lexicalizes, dogConcept))
      // Written the way a person writes a query — compact names, which the term parser expands.
      bgp = PatternSyntax
        .parse(s"${frChien.display} ${LanguageModule.translationOf.display} ?other")
        .fold(fail(_), identity)
      // ...and it translates to all three others.
      solutions <- base.query(bgp)
    yield assertEquals(
      solutions.flatMap(_.get("other")).toSet,
      Set(Node.Ref(esPerro), Node.Ref(enDog), Node.Ref(ruSobaka))
    )

  test("false friends are symmetric, high-utility and low-prior"):
    // ru:магазин falseFriendOf en:magazine  (SPEC §6)
    val axiom = Axiom.ObjectAssertion(ruMagazin, LanguageModule.falseFriendOf, enMagazine)
    for
      base <- installed
      _ <- base.assert(axiom)
      reverse <- base.entails(Axiom.ObjectAssertion(enMagazine, LanguageModule.falseFriendOf, ruMagazin))
      text <- base.verbalize(axiom)
    yield
      assert(reverse, "falseFriendOf should be symmetric")
      assert(text.contains("false friend"), text)

      val record = AxiomRecord(axiom.id, axiom, AxiomAnnotations.empty, AxiomStatus.Active, 1L)
      assert(PolicyCascade.recallUtility(record, config.policies) >= 0.9, "false friends are high-value")
      assertEquals(LanguageModule.priorFor(axiom), Some(0.15), "false friends should start low")

  test("false-friend concepts derive only the other lexeme as confusable"):
    val misleading = Iri("noesis:e/lex-misleading")
    val actual = Iri("noesis:e/concept-actual")
    val sibling = Iri("noesis:e/lex-sibling")
    val unrelated = Iri("noesis:e/lex-unrelated")
    for
      base <- installed
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ObjectAssertion(ruMagazin, LanguageModule.falseFriendOf, misleading)),
          Intent.Assert(Axiom.ObjectAssertion(misleading, LanguageModule.lexicalizes, actual)),
          Intent.Assert(Axiom.ObjectAssertion(sibling, LanguageModule.lexicalizes, actual)),
          Intent.Assert(Axiom.ObjectAssertion(ruMagazin, LanguageModule.lexicalizes, actual)),
          Intent.Assert(Axiom.ObjectAssertion(unrelated, LanguageModule.lexicalizes, dogConcept))
        )
      )
      siblingIsConfusable <- base.entails(
        Axiom.ObjectAssertion(ruMagazin, LanguageModule.confusableWith, sibling)
      )
      selfIsConfusable <- base.entails(
        Axiom.ObjectAssertion(ruMagazin, LanguageModule.confusableWith, ruMagazin)
      )
      falseFriendIsConfusable <- base.entails(
        Axiom.ObjectAssertion(ruMagazin, LanguageModule.confusableWith, misleading)
      )
      unrelatedIsConfusable <- base.entails(
        Axiom.ObjectAssertion(ruMagazin, LanguageModule.confusableWith, unrelated)
      )
    yield
      assert(siblingIsConfusable)
      assert(!selfIsConfusable)
      assert(!falseFriendIsConfusable)
      assert(!unrelatedIsConfusable)

  test("cognates start with a high belief prior, since they are nearly free"):
    val axiom = Axiom.ObjectAssertion(
      Iri("noesis:e/lex-constitucion"),
      LanguageModule.cognateOf,
      Iri("noesis:e/lex-konstituciya")
    )
    assertEquals(LanguageModule.priorFor(axiom), Some(0.8))
    assertEquals(
      LanguageModule.priorFor(
        Axiom.ObjectAssertion(esPerro, LanguageModule.confusableWith, enDog)
      ),
      Some(0.2)
    )
    assertEquals(
      LanguageModule.priorFor(Axiom.ObjectAssertion(esPerro, LanguageModule.lexicalizes, dogConcept)),
      None
    )

  test("the belief tensor key distinguishes direction and skill"):
    val es = Iri("ll:es")
    val ru = Iri("ll:ru")
    val esToRu = LanguageModule.MasteryKey(dogConcept, es, ru, LanguageModule.Skill.Production)
    val ruToEs = LanguageModule.MasteryKey(dogConcept, ru, es, LanguageModule.Skill.Production)
    val recognition = LanguageModule.MasteryKey(dogConcept, es, ru, LanguageModule.Skill.Recognition)

    assertNotEquals(esToRu, ruToEs, "producing from Spanish is not producing from Russian")
    assertNotEquals(esToRu, recognition, "production is not recognition")
    assertEquals(esToRu.render, "e/c-dog:es→ru:Production")

  test("ll defaults sensitivity to public, since vocabulary is not personal"):
    val axiom = Axiom.ObjectAssertion(esPerro, LanguageModule.lexicalizes, dogConcept)
    val record = AxiomRecord(axiom.id, axiom, AxiomAnnotations.empty, AxiomStatus.Active, 1L)
    assertEquals(PolicyCascade.sensitivity(record, config.policies), Sensitivity.Public)

  // ── Resources & accounting (SPEC §8) ──────────────────────────────────────

  /** "Lent my drill to Marco yesterday, back in two weeks" (SPEC §8). */
  private def lendDrill(base: KnowledgeBase[IO]) =
    base.commit(
      NonEmptyList.of(
        Intent.Assert(Axiom.ClassAssertion(drill, ResourcesModule.EconomicResource)),
        Intent.Assert(Axiom.ObjectAssertion(drill, ResourcesModule.primaryAccountable, me)),
        Intent.Assert(Axiom.ClassAssertion(lendEvent, ResourcesModule.EconomicEvent)),
        Intent.Assert(
          Axiom.DataAssertion(
            lendEvent,
            ResourcesModule.action,
            Literal.string(ResourcesModule.Action.transferCustody)
          )
        ),
        Intent.Assert(Axiom.ObjectAssertion(lendEvent, ResourcesModule.provider, me)),
        Intent.Assert(Axiom.ObjectAssertion(lendEvent, ResourcesModule.receiver, marco)),
        Intent.Assert(Axiom.ObjectAssertion(lendEvent, ResourcesModule.resourceInventoriedAs, drill))
      )
    )

  test("lending moves custody while accountability stays, so the drill is out on loan"):
    for
      base <- installed
      result <- lendDrill(base)
      state <- base.state
    yield
      assert(result.isRight, result.toString)
      assertEquals(Ledger.custody(state).get(drill), Some(marco), "custody should have moved")
      assertEquals(
        Ledger.outOnLoan(state, me),
        List(drill -> marco),
        "primaryAccountable=me ∧ custodian≠me should be derivable"
      )
      assertEquals(Ledger.borrowed(state, me), Nil)

  test("a return event ends the loan, derived from the events alone"):
    for
      base <- installed
      _ <- lendDrill(base)
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(returnEvent, ResourcesModule.EconomicEvent)),
          Intent.Assert(
            Axiom.DataAssertion(
              returnEvent,
              ResourcesModule.action,
              Literal.string(ResourcesModule.Action.transferCustody)
            )
          ),
          Intent.Assert(Axiom.ObjectAssertion(returnEvent, ResourcesModule.provider, marco)),
          Intent.Assert(Axiom.ObjectAssertion(returnEvent, ResourcesModule.receiver, me)),
          Intent.Assert(Axiom.ObjectAssertion(returnEvent, ResourcesModule.resourceInventoriedAs, drill))
        )
      )
      state <- base.state
    yield
      assertEquals(Ledger.custody(state).get(drill), Some(me))
      assertEquals(Ledger.outOnLoan(state, me), Nil, "the loan should be closed by the return event")

  test("borrowing is the mirror of lending"):
    val book = Iri("noesis:e/book")
    val event = Iri("noesis:e/ev-borrow")
    for
      base <- installed
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(book, ResourcesModule.EconomicResource)),
          Intent.Assert(Axiom.ObjectAssertion(book, ResourcesModule.primaryAccountable, sarah)),
          Intent.Assert(Axiom.ClassAssertion(event, ResourcesModule.EconomicEvent)),
          Intent.Assert(
            Axiom.DataAssertion(
              event,
              ResourcesModule.action,
              Literal.string(ResourcesModule.Action.transferCustody)
            )
          ),
          Intent.Assert(Axiom.ObjectAssertion(event, ResourcesModule.provider, sarah)),
          Intent.Assert(Axiom.ObjectAssertion(event, ResourcesModule.receiver, me)),
          Intent.Assert(Axiom.ObjectAssertion(event, ResourcesModule.resourceInventoriedAs, book))
        )
      )
      state <- base.state
    yield
      assertEquals(Ledger.borrowed(state, me), List(book -> sarah))
      assertEquals(Ledger.outOnLoan(state, me), Nil)

  test("missing transfer actions and one-sided borrowing predicates do not move custody"):
    val owned = Iri("noesis:e/owned")
    val other = Iri("noesis:e/other")
    val event = Iri("noesis:e/ev-no-action")
    for
      base <- installed
      _ <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(event, ResourcesModule.EconomicEvent)),
          Intent.Assert(Axiom.ObjectAssertion(event, ResourcesModule.receiver, me)),
          Intent.Assert(Axiom.ObjectAssertion(event, ResourcesModule.resourceInventoriedAs, other)),
          Intent.Assert(Axiom.ObjectAssertion(owned, ResourcesModule.primaryAccountable, me)),
          Intent.Assert(Axiom.ObjectAssertion(other, ResourcesModule.primaryAccountable, sarah))
        )
      )
      state <- base.state
    yield
      assertEquals(Ledger.transfers(state).map(_.action), List(""))
      assertEquals(Ledger.custody(state), Map.empty)
      assertEquals(Ledger.borrowed(state, me), Nil)

  test("a balance is a fold over raise and lower events, never stored state"):
    val account = Iri("noesis:e/account")
    def event(id: String, action: String, amount: Int) =
      List(
        Intent.Assert(Axiom.ClassAssertion(Iri(id), ResourcesModule.EconomicEvent)),
        Intent.Assert(Axiom.DataAssertion(Iri(id), ResourcesModule.action, Literal.string(action))),
        Intent.Assert(
          Axiom.DataAssertion(Iri(id), ResourcesModule.quantity, Literal.decimal(BigDecimal(amount)))
        ),
        Intent.Assert(Axiom.ObjectAssertion(Iri(id), ResourcesModule.resourceInventoriedAs, account))
      )

    for
      base <- installed
      _ <- base.commit(
        NonEmptyList.fromListUnsafe(
          Intent.Assert(Axiom.ClassAssertion(account, ResourcesModule.EconomicResource)) ::
            event("noesis:e/ev-1", ResourcesModule.Action.raise, 100) ++
            event("noesis:e/ev-2", ResourcesModule.Action.raise, 50) ++
            event("noesis:e/ev-3", ResourcesModule.Action.lower, 30)
        )
      )
      state <- base.state
    yield assertEquals(Ledger.quantityOf(state, account), BigDecimal(120))

  test("monetary quantities are sensitive, so a balance never crosses the boundary"):
    val axiom =
      Axiom.DataAssertion(Iri("noesis:e/ev-1"), ResourcesModule.quantity, Literal.decimal(BigDecimal(100)))
    val record = AxiomRecord(axiom.id, axiom, AxiomAnnotations.empty, AxiomStatus.Active, 1L)

    assertEquals(PolicyCascade.sensitivity(record, config.policies), Sensitivity.Sensitive)

    for
      base <- installed
      _ <- base.assert(axiom)
      decision <- base.disclosureOf(axiom, DisclosurePolicy("greedy", Sensitivity.Sensitive))
    yield assert(!decision.isDisclosed, "vf_balances must be effectively undisclosable (§8)")

  test("vf's module utility is low, so ledger entries are not quizzed"):
    val axiom = Axiom.ObjectAssertion(lendEvent, ResourcesModule.provider, me)
    val record = AxiomRecord(axiom.id, axiom, AxiomAnnotations.empty, AxiomStatus.Active, 1L)

    assert(PolicyCascade.recallUtility(record, config.policies) <= 0.25, "a ledger is lookup data")
    assertEquals(
      Modules.itemPolicies(modules).policyFor(Axiom.ClassAssertion(lendEvent, ResourcesModule.EconomicEvent)),
      ItemPolicy.Ignore
    )

  test("open commitments keep medium utility, because they matter in daily social life"):
    val commitment = Iri("noesis:e/commitment")
    val axiom = Axiom.DataAssertion(commitment, ResourcesModule.due, Literal.date(2026, 8, 12))
    val record = AxiomRecord(axiom.id, axiom, AxiomAnnotations.empty, AxiomStatus.Active, 1L)
    val utility = PolicyCascade.recallUtility(record, config.policies)

    assert(utility > PolicyCascade.suspendThreshold, s"open loans should be schedulable, got $utility")
    assert(utility < 0.85, s"but below relationship facts, got $utility")

  // ── The learning engine over module vocabulary ────────────────────────────

  test("module item policies decide what is drafted, and the queue reflects utility"):
    for
      base <- installed
      engine <- engineFor(base)
      phone = PrmCapture.method(
        ContactMethodInput(
          Iri("noesis:e/marco-phone"),
          marco,
          ContactKind.Phone,
          "+52 555"
        )
      ).fold(problems => fail(problems.mkString(", ")), identity)
      commit <- base.commit(
        NonEmptyList.fromListUnsafe(List(
          Intent.Assert(
            Axiom.DataAssertion(
              lia,
              RelationshipsModule.birthday,
              Literal.date(PartialDate.monthDay(5, 12))
            )
          )
        ) ++ phone.toList)
      )
      result = commit.fold(r => fail(r.render), identity)
      _ <- engine.handle(result.events)
      items <- engine.items
      queue <- engine.queue(QueueMode.Mixed, limit = 10)
    yield
      assert(items.exists(_.prompt.contains("birthday")), "the birthday item was not drafted")
      assert(
        !items.exists(_.prompt.contains("+52")),
        "an ignored property produced an item anyway"
      )
      assert(queue.nonEmpty, "the birthday item should be schedulable")
      assert(queue.forall(_.utility >= PolicyCascade.suspendThreshold))

  test("a review through the engine moves belief and logs the outcome"):
    for
      base <- installed
      engine <- engineFor(base)
      commit <- base.assert(
        Axiom.DataAssertion(lia, RelationshipsModule.birthday, Literal.date(PartialDate.monthDay(5, 12)))
      )
      result = commit.fold(r => fail(r.render), identity)
      items <- engine.handle(result.events)
      item = items.headOption.getOrElse(fail("expected a learning item for the birthday"))
      outcome <- engine.review(item.id, grade = 1.0, latencyMs = 1200)
      log <- engine.reviewLog
    yield
      val reviewed = outcome.getOrElse(fail("no review outcome"))
      assert(reviewed.item.belief > item.belief, "a correct answer should raise belief")
      assertEquals(reviewed.item.reviewCount, 1)
      assertEquals(log.length, 1, "every review must be logged (§12.3)")
      assert(reviewed.events.map(_.name).contains("belief.updated"))

  test("grading a generated question end-to-end works without an LLM"):
    for
      base <- installed
      engine <- engineFor(base)
      _ <- base.assert(Axiom.DataAssertion(lia, Vocab.label, Literal.string("Lía")))
      commit <- base.assert(
        Axiom.DataAssertion(lia, RelationshipsModule.birthday, Literal.date(PartialDate.monthDay(5, 12)))
      )
      result = commit.fold(r => fail(r.render), identity)
      items <- engine.handle(result.events)
      birthdayItem = items.find(_.prompt.contains("birthday")).getOrElse(fail("no birthday item"))
      queueEntry <- engine.queue(QueueMode.Mixed, limit = 10).map(_.find(_.item.id == birthdayItem.id))
      question <- queueEntry.flatTraverse(engine.nextQuestion)
      correct <- question.flatTraverse(q => engine.answer(q, "--05-12", 900))
      wrong <- question.flatTraverse(q => engine.answer(q, "--01-01", 900))
    yield
      assert(question.isDefined, "a question should have been generated")
      assert(correct.exists(_.review.grade == 1.0), correct.toString)
      assert(wrong.exists(_.review.grade == 0.0), wrong.toString)

  test("derived belief over module inference reflects belief in the premises"):
    for
      base <- installed
      engine <- engineFor(base)
      commit <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ObjectAssertion(alice, RelationshipsModule.partnerOf, marco)),
          Intent.Assert(Axiom.ObjectAssertion(marco, RelationshipsModule.partnerOf, sarah))
        )
      )
      result = commit.fold(r => fail(r.render), identity)
      _ <- engine.handle(result.events)
      derived <- engine.derivedBelief(Axiom.ObjectAssertion(alice, RelationshipsModule.metamourOf, sarah))
    yield assert(
      derived.exists(b => b > 0.0 && b <= 1.0),
      s"a derived metamour link should carry belief from its premises, got $derived"
    )

  test("retracting an axiom retires its learning items"):
    val axiom =
      Axiom.DataAssertion(lia, RelationshipsModule.birthday, Literal.date(PartialDate.monthDay(5, 12)))
    for
      base <- installed
      engine <- engineFor(base)
      added <- base.assert(axiom)
      _ <- engine.handle(added.fold(r => fail(r.render), _.events))
      retracted <- base.commit(NonEmptyList.one(Intent.Retract(axiom.id)))
      _ <- engine.handle(retracted.fold(r => fail(r.render), _.events))
      items <- engine.items
    yield assert(items.forall(_.suspended), "items for a retracted axiom should be retired")
