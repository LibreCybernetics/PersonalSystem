package noesis.vocab

import java.time.LocalDate

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.std.{SecureRandom, UUIDGen}
import cats.syntax.all.*
import munit.CatsEffectSuite
import noesis.core.capture.Intent
import noesis.core.kb.{CommitRejected, KbConfig, KnowledgeBase}
import noesis.core.module.{ExportContext, ExportOptions}
import noesis.core.policy.{DisclosurePolicy, PolicyCascade}
import noesis.core.projection.AxiomRecord
import noesis.journal.InMemoryJournal
import noesis.logic.*

class PrmSuite extends CatsEffectSuite:
  given SecureRandom[IO] =
    SecureRandom.javaSecuritySecureRandom[IO].unsafeRunSync()(using
      cats.effect.unsafe.implicits.global
    )
  given UUIDGen[IO] = UUIDGen.fromSecureRandom[IO]

  private val modules = Modules.all
  private val config = Modules.configure(KbConfig.default, modules)
  private val lia = Iri("noesis:e/lia")
  private val marco = Iri("noesis:e/marco")
  private val acme = Iri("noesis:e/acme")

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

  private def commit(
      base: KnowledgeBase[IO],
      intents: Either[List[String], NonEmptyList[Intent]]
  ): IO[Unit] =
    intents match
      case Left(problems) => IO.raiseError(new AssertionError(problems.mkString(", ")))
      case Right(found) =>
        base.commit(found).flatMap(result =>
          IO.raiseWhen(result.isLeft)(new AssertionError(result.left.toOption.map(_.render).getOrElse("")))
        )

  test("structured contact capture supports concurrent methods and current-name verbalization"):
    val phone = Iri("noesis:e/lia-phone")
    val email = Iri("noesis:e/lia-email")
    for
      base <- installed
      _ <- commit(base, PrmCapture.contact(ContactInput(lia, "Lía García")))
      _ <- commit(
        base,
        PrmCapture.method(
          ContactMethodInput(phone, lia, ContactKind.Phone, "+52 55 1234", Some("mobile"), rank = Some(1))
        )
      )
      _ <- commit(
        base,
        PrmCapture.method(
          ContactMethodInput(email, lia, ContactKind.Email, "lia@Example.COM", Some("personal"))
        )
      )
      state <- base.state
      verbalizer <- base.verbalizer
      card = Prm.contactCard(state, lia)
    yield
      assertEquals(verbalizer.label(lia), "Lía García")
      assertEquals(card.methods.map(_.id).toSet, Set(phone, email))
      assertEquals(card.methods.find(_.id == email).map(_.normalizedValue), Some("lia@example.com"))
      assertEquals(card.completeness, ContactCompleteness.Reachable)

  test("structured capture rejects every malformed boundary with a specific problem"):
    def problems[A](result: Either[List[String], A]): List[String] =
      result.fold(identity, _ => fail("expected malformed input to be rejected"))

    assertEquals(
      ContactKind.values.toList.map(kind => ContactKind.parse(kind.value)),
      ContactKind.values.toList.map(Right(_))
    )
    assertEquals(ContactKind.parse("unknown"), Left("unknown contact kind: unknown"))
    assertEquals(
      PrmIds.record("contact", "seed"),
      Iri("noesis:e/prm-contact-19b25856e1c150ca834c")
    )
    assertEquals(
      PrmIds.child(lia, "email", "seed"),
      Iri("noesis:e/prm-email-46badd99c4a87fbb84df")
    )

    assertEquals(problems(PrmCapture.contact(ContactInput(lia, " "))), List("display name must not be blank"))
    assertEquals(
      problems(
        PrmCapture.method(
          ContactMethodInput(Iri("noesis:e/m"), lia, ContactKind.Email, "", rank = Some(-1))
        )
      ),
      List(
        "contact value must not be blank",
        "preference rank must not be negative",
        "email address must contain one non-edge @"
      )
    )
    List("@example.com", "lia@", "lia@@example.com").foreach: value =>
      assertEquals(
        problems(
          PrmCapture.method(
            ContactMethodInput(Iri("noesis:e/m"), lia, ContactKind.Email, value)
          )
        ),
        List("email address must contain one non-edge @")
      )
    assert(PrmCapture.method(
      ContactMethodInput(Iri("noesis:e/m"), lia, ContactKind.Email, "lia@example.com", rank = Some(0))
    ).isRight)
    assertEquals(
      problems(
        PrmCapture.address(
          PostalAddressInput(Iri("noesis:e/a"), lia, "", countryCode = Some("MEX"))
        )
      ),
      List(
        "formatted address must not be blank",
        "country code must be two ASCII letters"
      )
    )
    assertEquals(
      problems(
        PrmCapture.interaction(
          InteractionInput(Iri("noesis:e/i"), Nil, PartialDate.of(2026, 7, 1), "")
        )
      ),
      List(
        "an interaction needs at least one participant",
        "interaction channel must not be blank"
      )
    )
    assertEquals(
      problems(
        PrmCapture.relationship(
          RelationshipInput(Iri("noesis:e/r"), List(lia), "")
        )
      ),
      List(
        "a relationship needs at least two participants",
        "relationship kind must not be blank"
      )
    )
    assertEquals(problems(PrmCapture.note(NoteInput(Iri("noesis:e/n"), lia, ""))), List("note body must not be blank"))
    assertEquals(
      problems(
        PrmCapture.preference(
          PreferenceInput(Iri("noesis:e/p"), lia, "maybe", "")
        )
      ),
      List(
        "preference polarity must be one of allergy, dislikes, likes, topic-to-avoid",
        "preference text must not be blank"
      )
    )
    assertEquals(
      problems(PrmCapture.followUp(FollowUpInput(Iri("noesis:e/f"), lia, 0))),
      List("follow-up cadence must be positive")
    )
    assertEquals(
      problems(
        PrmCapture.reminder(
          ReminderInput(Iri("noesis:e/rem"), lia, Literal.anniversary(5, 12), "")
        )
      ),
      List("reminder occasion must not be blank")
    )
    assertEquals(
      problems(
        PrmCapture.companionAnimal(
          CompanionAnimalInput(Iri("noesis:e/pet"), "", Nil)
        )
      ),
      List(
        "companion animal name must not be blank",
        "a companion animal needs at least one associated contact"
      )
    )
    assertEquals(
      problems(PrmCapture.circle(CircleInput(Iri("noesis:e/circle"), "", Nil))),
      List("circle name must not be blank")
    )
    assertEquals(
      problems(PrmCapture.gift(GiftInput(Iri("noesis:e/gift"), "", status = "lost"))),
      List(
        "gift description must not be blank",
        "a gift needs a recipient or giver",
        "gift status must be one of given, idea, planned, received"
      )
    )

  test("an incomplete contact-method record is rejected before journal append"):
    val method = Iri("noesis:e/incomplete-phone")
    for
      base <- installed
      before <- base.state
      result <- base.assert(Axiom.ClassAssertion(method, RelationshipsModule.TelephoneNumber))
      after <- base.state
    yield
      assert(result.isLeft)
      assertEquals(after.seq, before.seq, "validation failure must not append")

  test("the validator reports missing shape fields for every reified PRM record"):
    val cases = List(
      RelationshipsModule.Name ->
        List("name value"),
      RelationshipsModule.ContactMethod ->
        List("contact owner", "contact kind", "current contact value", "contact status"),
      RelationshipsModule.Employment ->
        List("employee", "employer", "employment status"),
      RelationshipsModule.Relationship ->
        List("relationship participants", "relationship kind", "relationship status"),
      RelationshipsModule.Interaction ->
        List("interaction participant", "interaction date", "interaction channel"),
      RelationshipsModule.FollowUpPlan ->
        List("follow-up contact", "follow-up cadence", "follow-up pause state"),
      RelationshipsModule.Reminder ->
        List("reminder contact", "reminder due date", "reminder occasion"),
      RelationshipsModule.ContactNote ->
        List("note subject", "note body", "note kind"),
      RelationshipsModule.Preference ->
        List("preference subject", "preference polarity", "preference text"),
      RelationshipsModule.ExternalIdentifier ->
        List("identifier owner", "identifier scheme", "identifier value"),
      RelationshipsModule.CompanionAnimal ->
        List("associated contact", "current name"),
      RelationshipsModule.Gift ->
        List("gift recipient or giver", "gift description", "gift status")
    )
    cases.zipWithIndex.traverse_(
      (entry: ((Iri, List[String]), Int)) =>
        val ((cls, fragments), index) = entry
        val record = Iri(s"noesis:e/invalid-$index")
        installed.flatMap: base =>
          base.assert(Axiom.ClassAssertion(record, cls)).map:
            case Left(CommitRejected.Invalid(found)) =>
              fragments.foreach(fragment =>
                assert(found.exists(_.contains(fragment)), s"missing '$fragment' in $found")
              )
            case other => fail(s"expected invalid ${cls.display} record, got $other")
    )

  test("the validator rejects unsupported states and both follow-up cadence boundaries"):
    val method = Iri("noesis:e/method-invalid-state")
    val employment = Iri("noesis:e/employment-invalid-state")
    val relationship = Iri("noesis:e/relationship-invalid-state")
    val preference = Iri("noesis:e/preference-invalid-polarity")
    val gift = Iri("noesis:e/gift-invalid-state")
    val zeroPlan = Iri("noesis:e/follow-up-zero")
    val negativePlan = Iri("noesis:e/follow-up-negative")
    val intents = NonEmptyList.fromListUnsafe(
      List(
        Intent.Assert(Axiom.ClassAssertion(method, RelationshipsModule.ContactMethod)),
        Intent.Assert(Axiom.ObjectAssertion(method, RelationshipsModule.contactFor, lia)),
        Intent.Assert(
          Axiom.DataAssertion(method, RelationshipsModule.contactKind, Literal.string("custom"))
        ),
        Intent.OpenState(method, RelationshipsModule.contactValue, Node.Lit(Literal.string("value"))),
        Intent.OpenState(method, RelationshipsModule.contactStatus, Node.Lit(Literal.string("bogus"))),
        Intent.Assert(Axiom.ClassAssertion(employment, RelationshipsModule.Employment)),
        Intent.Assert(
          Axiom.ObjectAssertion(employment, RelationshipsModule.employmentFor, lia)
        ),
        Intent.Assert(Axiom.ObjectAssertion(employment, RelationshipsModule.employer, acme)),
        Intent.OpenState(
          employment,
          RelationshipsModule.employmentStatus,
          Node.Lit(Literal.string("bogus"))
        ),
        Intent.Assert(Axiom.ClassAssertion(relationship, RelationshipsModule.Relationship)),
        Intent.Assert(
          Axiom.ObjectAssertion(
            relationship,
            RelationshipsModule.relationshipParticipant,
            lia
          )
        ),
        Intent.Assert(
          Axiom.ObjectAssertion(
            relationship,
            RelationshipsModule.relationshipParticipant,
            marco
          )
        ),
        Intent.Assert(
          Axiom.DataAssertion(
            relationship,
            RelationshipsModule.relationshipKind,
            Literal.string("friendship")
          )
        ),
        Intent.OpenState(
          relationship,
          RelationshipsModule.relationshipStatus,
          Node.Lit(Literal.string("bogus"))
        ),
        Intent.Assert(Axiom.ClassAssertion(preference, RelationshipsModule.Preference)),
        Intent.Assert(Axiom.ObjectAssertion(preference, RelationshipsModule.about, lia)),
        Intent.Assert(
          Axiom.DataAssertion(
            preference,
            RelationshipsModule.preferencePolarity,
            Literal.string("bogus")
          )
        ),
        Intent.Assert(
          Axiom.DataAssertion(
            preference,
            RelationshipsModule.preferenceText,
            Literal.string("text")
          )
        ),
        Intent.Assert(Axiom.ClassAssertion(gift, RelationshipsModule.Gift)),
        Intent.Assert(Axiom.ObjectAssertion(gift, RelationshipsModule.giftTo, lia)),
        Intent.Assert(
          Axiom.DataAssertion(
            gift,
            RelationshipsModule.giftDescription,
            Literal.string("description")
          )
        ),
        Intent.Assert(
          Axiom.DataAssertion(gift, RelationshipsModule.giftStatus, Literal.string("bogus"))
        )
      ) ++ List(zeroPlan -> 0, negativePlan -> -1).flatMap: (plan, cadence) =>
        List(
          Intent.Assert(Axiom.ClassAssertion(plan, RelationshipsModule.FollowUpPlan)),
          Intent.Assert(Axiom.ObjectAssertion(plan, RelationshipsModule.followUpWith, lia)),
          Intent.Assert(
            Axiom.DataAssertion(
              plan,
              RelationshipsModule.cadenceDays,
              Literal.integer(BigInt(cadence))
            )
          ),
          Intent.OpenState(
            plan,
            RelationshipsModule.paused,
            Node.Lit(Literal.boolean(false))
          )
        )
    )
    for
      base <- installed
      result <- base.commit(intents)
    yield result match
      case Left(CommitRejected.Invalid(found)) =>
        List(
          "unsupported contact status 'bogus'",
          "unsupported employment status 'bogus'",
          "unsupported relationship status 'bogus'",
          "unsupported preference polarity 'bogus'",
          "unsupported gift status 'bogus'"
        ).foreach(fragment =>
          assert(found.exists(_.contains(fragment)), s"missing '$fragment' in $found")
        )
        assertEquals(found.count(_.contains("non-positive follow-up cadence")), 2)
      case other => fail(s"expected state/value validation errors, got $other")

  test("postal components fail closed as sensitive"):
    val address = Iri("noesis:e/lia-home")
    for
      base <- installed
      _ <- commit(base, PrmCapture.contact(ContactInput(lia, "Lía García")))
      _ <- commit(
        base,
        PrmCapture.address(
          PostalAddressInput(
            address,
            lia,
            "Calle Reforma 1, CDMX",
            street = Some("Calle Reforma 1"),
            locality = Some("Ciudad de México"),
            countryCode = Some("MX")
          )
        )
      )
      state <- base.state
    yield
      val street = state.activeAxioms.collectFirst:
        case record @ AxiomRecord(
              _,
              Axiom.DataAssertion(`address`, property, _),
              _,
              _,
              _
            ) if property == RelationshipsModule.streetAddress =>
          record
      val record = street.getOrElse(fail("street assertion missing"))
      assertEquals(PolicyCascade.sensitivity(record, config.policies), Sensitivity.Sensitive)

  test("ending an Employment removes derived worksAt while retaining employment history"):
    val employment = Iri("noesis:e/lia-acme-employment")
    val works = Axiom.ObjectAssertion(lia, RelationshipsModule.worksAt, acme)
    for
      base <- installed
      _ <- base.commit(PrmCapture.employment(EmploymentInput(employment, lia, acme)))
      active <- base.entails(works)
      ended <- base.commit(
        NonEmptyList.one(
          Intent.Supersede(
            employment,
            RelationshipsModule.employmentStatus,
            Node.Lit(Literal.string("ended"))
          )
        )
      )
      _ = ended.fold(rejected => fail(rejected.render), identity)
      inactive <- base.entails(works)
      state <- base.state
    yield
      assert(active)
      assert(!inactive)
      assertEquals(state.closedFluentsFor(employment, RelationshipsModule.employmentStatus).size, 1)

  test("follow-up due dates derive from the latest qualifying interaction"):
    val interaction = Iri("noesis:e/interaction-1")
    val phoneInteraction = Iri("noesis:e/interaction-2")
    val plan = Iri("noesis:e/follow-up-lia")
    val anyChannelPlan = Iri("noesis:e/follow-up-lia-any")
    for
      base <- installed
      _ <- commit(
        base,
        PrmCapture.interaction(
          InteractionInput(
            interaction,
            List(lia, marco),
            PartialDate.of(2026, 7, 1),
            "message"
          )
        )
      )
      _ <- commit(
        base,
        PrmCapture.interaction(
          InteractionInput(
            phoneInteraction,
            List(lia),
            PartialDate.of(2026, 7, 20),
            "phone"
          )
        )
      )
      _ <- commit(base, PrmCapture.followUp(FollowUpInput(plan, lia, 30, Some("message"))))
      _ <- commit(base, PrmCapture.followUp(FollowUpInput(anyChannelPlan, lia, 30)))
      state <- base.state
    yield
      val due = Prm.dueFollowUps(state, LocalDate.of(2026, 8, 15))
      assertEquals(
        due.map(entry => entry.plan -> entry.due),
        List(
          plan -> LocalDate.of(2026, 7, 31),
          anyChannelPlan -> LocalDate.of(2026, 8, 19)
        )
      )
      assert(due.headOption.exists(_.overdue))
      assert(!due.lastOption.exists(_.overdue))

  test("PRM normalization, completeness, interaction limits and reminder due filtering are projections"):
    val interaction1 = Iri("noesis:e/timeline-1")
    val interaction2 = Iri("noesis:e/timeline-2")
    val past = Iri("noesis:e/reminder-past")
    val future = Iri("noesis:e/reminder-future")
    val annual = Iri("noesis:e/reminder-annual")
    for
      base <- installed
      _ <- commit(base, PrmCapture.contact(ContactInput(lia, "Lía García")))
      nameOnly <- base.state.map(Prm.contactCard(_, lia))
      _ <- commit(
        base,
        PrmCapture.method(
          ContactMethodInput(Iri("noesis:e/phone"), lia, ContactKind.Phone, "55 (12)-34")
        )
      )
      _ <- commit(
        base,
        PrmCapture.interaction(
          InteractionInput(interaction1, List(lia), PartialDate.of(2026, 7, 1), "message")
        )
      )
      _ <- commit(
        base,
        PrmCapture.interaction(
          InteractionInput(interaction2, List(lia), PartialDate.of(2026, 7, 2), "phone")
        )
      )
      _ <- commit(
        base,
        PrmCapture.reminder(
          ReminderInput(past, lia, Literal.date(PartialDate.of(2026, 7, 1)), "past")
        )
      )
      _ <- commit(
        base,
        PrmCapture.reminder(
          ReminderInput(future, lia, Literal.date(PartialDate.of(2026, 9, 1)), "future")
        )
      )
      _ <- commit(
        base,
        PrmCapture.reminder(
          ReminderInput(annual, lia, Literal.anniversary(8, 15), "annual", Some("yearly"))
        )
      )
      state <- base.state
    yield
      assertEquals(nameOnly.completeness, ContactCompleteness.NameOnly)
      assertEquals(Prm.normalizeEmail(" Lia@Example.COM "), "Lia@example.com")
      assertEquals(Prm.normalizeEmail("not-an-email"), "not-an-email")
      assertEquals(Prm.normalizePhone("+52 (55) 12-34"), "+52551234")
      assertEquals(Prm.normalizePhone("55 (12)-34"), "551234")
      val card = Prm.contactCard(state, lia, interactionLimit = 1)
      assertEquals(card.completeness, ContactCompleteness.Complete)
      assertEquals(card.methods.map(_.normalizedValue), List("551234"))
      assertEquals(card.recentInteractions.map(_.id), List(interaction2))
      assertEquals(Prm.contactCard(state, lia, interactionLimit = -1).recentInteractions, Nil)
      assertEquals(
        Prm.remindersDue(state, LocalDate.of(2026, 8, 15)).map(_.reminder).toSet,
        Set(past, annual)
      )
      // The recurring one is due on its day and on no other, while a dated one stays due once
      // passed. A due value with no recurring day at all — a month, here — is neither.
      assertEquals(
        Prm.remindersDue(state, LocalDate.of(2026, 8, 14)).map(_.reminder).toSet,
        Set(past)
      )
      assert(!Prm.fallsBy(Literal.date(PartialDate.Month(2026, 9)), LocalDate.of(2026, 8, 15)))
      assert(Prm.fallsBy(Literal.date(PartialDate.Month(2026, 7)), LocalDate.of(2026, 8, 15)))
      assert(!Prm.fallsBy(Literal.string("someday"), LocalDate.of(2026, 8, 15)))

  test("vCard imports typed fields atomically and round-trips its supported subset"):
    val document =
      """BEGIN:VCARD
        |VERSION:4.0
        |UID:urn:uuid:lia
        |FN:Lía García
        |N:García\, Molina;Lía;;;
        |ANNIVERSARY:06-18
        |BDAY:05-12
        |EMAIL;TYPE=home:lia@example.com
        |TEL;TYPE=cell:+52551234
        |ADR;LABEL=Casa:;;Calle Reforma 1;Ciudad de México;;01000;MX
        |ORG:Molina Labs
        |TITLE:Researcher
        |NOTE:Met at a conference
        |END:VCARD
        |""".stripMargin
    for
      base <- installed
      batches = VCard.importIntents(document).fold(problems => fail(problems.mkString(", ")), identity)
      _ <- batches.traverse_(batch =>
        val (_, intents) = batch
        base.commit(intents).map(result => result.fold(rejected => fail(rejected.render), identity))
      )
      state <- base.state
      closure <- base.closure
      verbalizer <- base.verbalizer
      contact = batches.headOption.map(_._1).getOrElse(fail("contact missing"))
      card = Prm.contactCard(state, contact)
      rendered = VCard.write(card, verbalizer.label)
      reparsed = VCard.parse(rendered).fold(problems => fail(problems.mkString(", ")), identity)
      exported = VCardExporter.render(
        ExportContext.restricted(
          state,
          closure,
          config.policies,
          DisclosurePolicy.personal("test export"),
          config.namingProperties,
          config.namingSchemes
        ),
        contact,
        ExportOptions()
      ).fold(problems => fail(problems.mkString(", ")), identity)
    yield
      assertEquals(card.methods.map(_.kind).toSet, Set("email", "phone", "postal"))
      assertEquals(card.birthday, Some(Literal.anniversary(5, 12)))
      assertEquals(card.structuredName.flatMap(_.family), Some("García, Molina"))
      assertEquals(card.structuredName.flatMap(_.givenName), Some("Lía"))
      assertEquals(
        Prm.reminders(state).map(reminder => reminder.occasion -> reminder.due),
        List("anniversary" -> Literal.anniversary(6, 18))
      )
      assertEquals(
        Prm.occasions(state, LocalDate.of(2026, 5, 1)).map(_.occasion),
        List("birthday")
      )
      assertEquals(reparsed.headOption.map(_.formattedName), Some("Lía García"))
      assert(rendered.contains("""N:García\, Molina;Lía;;;"""))
      assert(!exported.contains("ADR"), "sensitive postal addresses must not cross the export boundary")
      assert(rendered.contains("\r\n"), "vCard output must use CRLF")

  test("vCard parsing preserves its complete supported field and escaping contract"):
    val document =
      """BEGIN:VCARD
        |VERSION:4.0
        |UID:urn:uuid:complete
        |FN:Dr. Lía\, García
        |N:García;Lía;María;Dr.;PhD
        |NICKNAME:Lili,Li\, Li
        |BDAY:1988-05-12
        |ANNIVERSARY:06-18
        |EMAIL;TYPE=home,work;LABEL=Primary:lia@example.com
        |TEL;TYPE=cell:+52551234
        |IMPP;TYPE=home:matrix:@lia:example.org
        |URL;TYPE=work:https://example.org/lia
        |ADR;LABEL=Casa:PO;Apartment 2;Calle 1;CDMX;CDMX;01000;MX
        |ORG:Molina Labs;Research
        |TITLE:Researcher
        |ROLE:Scientist
        |RELATED;TYPE=spouse:marco
        |NOTE:Line 1\nLine 2
        |CATEGORIES:Friends,Work
        |END:VCARD
        |""".stripMargin
    val cards = VCard.parse(document).fold(found => fail(found.mkString(", ")), identity)
    assertEquals(
      cards,
      List(
        VCardContact(
          Some("urn:uuid:complete"),
          "Dr. Lía, García",
          List("García", "Lía", "María", "Dr.", "PhD"),
          List("Lili", "Li, Li"),
          Some(Literal.date(PartialDate.of(1988, 5, 12))),
          Some(Literal.anniversary(6, 18)),
          List(VCardField("lia@example.com", Some("Primary"), Set("home", "work"))),
          List(VCardField("+52551234", None, Set("cell"))),
          List(VCardField("matrix:@lia:example.org", None, Set("home"))),
          List(VCardField("https://example.org/lia", None, Set("work"))),
          List(
            VCardAddress(
              Some("Casa"),
              Some("Calle 1"),
              Some("Apartment 2"),
              Some("CDMX"),
              Some("CDMX"),
              Some("01000"),
              Some("MX"),
              Some("Casa")
            )
          ),
          Some("Molina Labs Research"),
          Some("Researcher"),
          Some("Scientist"),
          List(VCardField("marco", None, Set("spouse"))),
          Some("Line 1\nLine 2"),
          List("Friends", "Work")
        )
      )
    )

  test("vCard rejects malformed framing, version, required fields and content lines"):
    assertEquals(
      VCard.parse(""),
      Left(List("no BEGIN:VCARD … END:VCARD block found"))
    )
    assertEquals(
      VCard.parse("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Alice\r\nEND:VCARD\r\n"),
      Left(List("card 1: VERSION must be 4.0"))
    )
    assertEquals(
      VCard.parse("BEGIN:VCARD\r\nVERSION:4.0\r\nEND:VCARD\r\n"),
      Left(List("card 1: FN is required"))
    )
    assertEquals(
      VCard.parse("BEGIN:VCARD\r\nVERSION:4.0\r\nFN\r\nEND:VCARD\r\n"),
      Left(List("card 1: content line has no ':' separator: FN"))
    )

  test("vCard writing maps every current contact method and obeys UTF-8 line folding"):
    val address = PostalAddressView(
      "Calle 1, CDMX",
      Some("Calle 1"),
      Some("Apartment 2"),
      Some("CDMX"),
      Some("CDMX"),
      Some("01000"),
      Some("MX")
    )
    val card = ContactCard(
      lia,
      "Lía García",
      Some(StructuredNameView(Some("García"), Some("Lía"), None, None, None)),
      organization = false,
      Some(Literal.anniversary(5, 12)),
      List(
        ContactMethodView(Iri("noesis:e/email"), "email", "lia@example.com", Some("home"), None, "active", None, None),
        ContactMethodView(Iri("noesis:e/phone"), "phone", "+52 55", None, None, "active", None, None),
        ContactMethodView(Iri("noesis:e/sms"), "sms", "+52 56", None, None, "active", None, None),
        ContactMethodView(Iri("noesis:e/address"), "postal", address.formatted, None, None, "active", None, Some(address)),
        ContactMethodView(Iri("noesis:e/site"), "website", "https://example.org", Some("work"), None, "active", None, None),
        ContactMethodView(Iri("noesis:e/account"), "matrix", "@lia:example.org", None, None, "active", None, None)
      ),
      List(EmploymentView(Iri("noesis:e/job"), acme, Some("Researcher"), None, None)),
      Nil,
      ContactCompleteness.Complete
    )
    val rendered = VCard.write(card, iri => if iri == acme then "Acme" else iri.display)
    assertEquals(
      rendered,
      "BEGIN:VCARD\r\n" +
        "VERSION:4.0\r\n" +
        "UID:https://noesis.librecybernetics.ws/e/lia\r\n" +
        "FN:Lía García\r\n" +
        "N:García;Lía;;;\r\n" +
        "BDAY:--05-12\r\n" +
        "EMAIL;LABEL=home:lia@example.com\r\n" +
        "TEL:+52 55\r\n" +
        "TEL:+52 56\r\n" +
        "ADR;LABEL=Calle 1\\, CDMX:;Apartment 2;Calle 1;CDMX;CDMX;01000;MX\r\n" +
        "URL;LABEL=work:https://example.org\r\n" +
        "IMPP:@lia:example.org\r\n" +
        "ORG:Acme\r\n" +
        "TITLE:Researcher\r\n" +
        "END:VCARD\r\n"
    )
    val longCard = card.copy(displayName = "Lía " + ("á" * 90), structuredName = None)
    val folded = VCard.write(longCard, _ => "Acme")
    assert(folded.contains("\r\n "), "a long UTF-8 content line must be folded")
    folded.split("\r\n").filterNot(_.isEmpty).foreach: line =>
      assert(
        line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 75,
        s"overlong folded line: $line"
      )

  test("duplicate candidates use names and normalized contact methods without merging"):
    val email = Iri("noesis:e/lia-email")
    for
      base <- installed
      _ <- commit(base, PrmCapture.contact(ContactInput(lia, "Lía García")))
      _ <- commit(
        base,
        PrmCapture.method(
          ContactMethodInput(email, lia, ContactKind.Email, "lia@Example.COM")
        )
      )
      state <- base.state
    yield
      val candidates = Prm.duplicateCandidates(
        state,
        Some("LÍA GARCÍA"),
        List("email" -> "lia@example.com")
      )
      assertEquals(candidates.map(_.contact), List(lia))
      assertEquals(candidates.headOption.map(_.reasons.size), Some(2))
      assert(!state.activeAxioms.exists:
        _.axiom match
          case Axiom.SameIndividual(_, _) => true
          case _                          => false
      )

  test("FOAF import preserves external identity evidence without merging people"):
    val document =
      """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        |@prefix foaf: <http://xmlns.com/foaf/0.1/> .
        |@prefix ex: <https://example.test/> .
        |
        |ex:lia rdf:type foaf:Person .
        |ex:lia foaf:name "Lía García" .
        |ex:lia foaf:mbox <mailto:lia@example.com> .
        |ex:lia foaf:knows ex:acme .
        |ex:acme rdf:type foaf:Organization .
        |ex:acme foaf:name "Acme" .
        |""".stripMargin
    for
      base <- installed
      batches = Foaf.importIntents(document).fold(problems => fail(problems.mkString(", ")), identity)
      _ <- batches.traverse_(batch =>
        val (_, intents) = batch
        base.commit(intents).map(result => result.fold(rejected => fail(rejected.render), identity))
      )
      closure <- base.closure
      state <- base.state
      contacts = batches.map(_._1)
      acmeContact = contacts.find(contact =>
        Prm.contactCard(state, contact).displayName == "Acme"
      ).getOrElse(fail("Acme contact missing"))
    yield
      assert(!closure.axioms.exists {
        case Axiom.SameIndividual(_, _) => true
        case _                          => false
      })
      assert(closure.contains(Axiom.ClassAssertion(acmeContact, RelationshipsModule.Organization)))
      assert(!closure.contains(Axiom.ClassAssertion(acmeContact, Foaf.Person)))
      assert(
        state.activeAxioms.exists:
          case AxiomRecord(_, Axiom.DataAssertion(_, property, value), _, _, _) =>
            property == RelationshipsModule.identifierValue &&
              value.text == "https://example.test/lia"
          case _ => false
      )

  test("FOAF import maps accounts, sites, nicknames, birthdays, groups and membership"):
    val document =
      """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        |@prefix foaf: <http://xmlns.com/foaf/0.1/> .
        |@prefix ex: <https://example.test/> .
        |
        |ex:lia rdf:type foaf:Person .
        |ex:lia foaf:name "Lía García" .
        |ex:lia foaf:nick "Lili" .
        |ex:lia foaf:mbox <mailto:lia@example.com> .
        |ex:lia foaf:phone <tel:+52551234> .
        |ex:lia foaf:account ex:liaMatrix .
        |ex:liaMatrix foaf:accountName "@lia:example.org" .
        |ex:liaMatrix foaf:accountServiceHomepage <https://matrix.org/> .
        |ex:lia foaf:homepage <https://lia.example/> .
        |ex:lia foaf:page <https://example.test/about-lia> .
        |ex:lia foaf:birthday "05-12" .
        |ex:lia foaf:knows ex:marco .
        |ex:marco rdf:type foaf:Person .
        |ex:marco foaf:name "Marco" .
        |ex:friends rdf:type foaf:Group .
        |ex:friends foaf:name "Friends" .
        |ex:friends foaf:member ex:lia .
        |""".stripMargin
    for
      base <- installed
      batches = Foaf.importIntents(document).fold(found => fail(found.mkString(", ")), identity)
      _ <- batches.traverse_(batch =>
        val (_, intents) = batch
        base.commit(intents).map(_.fold(rejected => fail(rejected.render), identity))
      )
      state <- base.state
      closure <- base.closure
    yield
      val liaContact = batches
        .map(_._1)
        .find(contact => Prm.contactCard(state, contact).displayName == "Lía García")
        .getOrElse(fail("Lía contact missing"))
      val card = Prm.contactCard(state, liaContact)
      assertEquals(card.methods.map(_.kind).toSet, Set("email", "phone", "social", "website"))
      assertEquals(card.methods.count(_.kind == "website"), 2)
      assertEquals(card.birthday, Some(Literal.anniversary(5, 12)))
      assert(state.activeAxioms.exists:
        case AxiomRecord(
              _,
              Axiom.DataAssertion(_, property, value),
              annotations,
              _,
              _
            ) =>
          property == RelationshipsModule.nameValue &&
            value.text == "Lili" &&
            annotations.truthConfidence.contains(0.7)
        case _ => false
      )
      assert(closure.axioms.exists:
        case Axiom.ObjectAssertion(_, property, `liaContact`) =>
          property == RelationshipsModule.member
        case _ => false
      )

  test("FOAF export is private by default and its Turtle is accepted by the importer"):
    val phone = Iri("noesis:e/lia-phone")
    for
      base <- installed
      _ <- commit(base, PrmCapture.contact(ContactInput(lia, "Lía García")))
      _ <- commit(
        base,
        PrmCapture.method(
          ContactMethodInput(phone, lia, ContactKind.Phone, "+52 55 1234")
        )
      )
      state <- base.state
    yield
      val card = Prm.contactCard(state, lia)
      val privateExport = Foaf.write(card, List(marco))
      assert(!privateExport.contains("foaf:phone"))
      assert(!privateExport.contains("foaf:knows"))

      val shared = Foaf.write(
        card,
        List(marco),
        FoafExportOptions(includeContactData = true, includeSocialGraph = true)
      )
      assert(shared.contains("foaf:phone"))
      assert(shared.contains("foaf:knows"))
      assert(Foaf.parseRdf(shared).exists(_.nonEmpty))

  test("FOAF export maps every allowed current value to the exact RDF graph"):
    val site = Iri("noesis:e/site")
    val page = Iri("noesis:e/page")
    val account = Iri("noesis:e/account")
    val card = ContactCard(
      lia,
      "Lía García",
      None,
      organization = false,
      Some(Literal.anniversary(5, 12)),
      List(
        ContactMethodView(Iri("noesis:e/email"), "email", "lia@example.com", None, None, "active", None, None),
        ContactMethodView(Iri("noesis:e/phone"), "phone", "+52 55 1234", None, None, "active", None, None),
        ContactMethodView(site, "website", "https://lia.example/", None, Some("homepage"), "active", None, None),
        ContactMethodView(page, "website", "https://example.test/lia", None, None, "active", None, None),
        ContactMethodView(account, "matrix", "@lia:example.org", Some("https://matrix.org/"), None, "active", None, None),
        ContactMethodView(Iri("noesis:e/address"), "postal", "private", None, None, "active", None, None)
      ),
      Nil,
      Nil,
      ContactCompleteness.Complete
    )
    val document = Foaf.write(
      card,
      List(marco, marco),
      FoafExportOptions(includeContactData = true, includeSocialGraph = true)
    )
    val triples = Foaf.parseRdf(document).fold(found => fail(found.mkString(", ")), _.toSet)
    assertEquals(
      triples,
      Set(
        Triple(lia, Vocab.rdfType, Node.Ref(Foaf.Person)),
        Triple(lia, Foaf.name, Node.Lit(Literal.string("Lía García"))),
        Triple(lia, Foaf.birthday, Node.Lit(Literal.string("05-12"))),
        Triple(lia, Foaf.mbox, Node.Ref(Iri.absolute("mailto:lia@example.com"))),
        Triple(lia, Foaf.phone, Node.Ref(Iri.absolute("tel:+52551234"))),
        Triple(lia, Foaf.homepage, Node.Ref(Iri.absolute("https://lia.example/"))),
        Triple(lia, Foaf.page, Node.Ref(Iri.absolute("https://example.test/lia"))),
        Triple(lia, Foaf.account, Node.Ref(account)),
        Triple(account, Vocab.rdfType, Node.Ref(Foaf.OnlineAccount)),
        Triple(account, Foaf.accountName, Node.Lit(Literal.string("@lia:example.org"))),
        Triple(
          account,
          Foaf.accountServiceHomepage,
          Node.Ref(Iri.absolute("https://matrix.org/"))
        ),
        Triple(lia, Foaf.knows, Node.Ref(marco))
      )
    )
    val organization = card.copy(organization = true)
    val organizationTriples = Foaf
      .parseRdf(
        Foaf.write(
          organization,
          List(marco),
          FoafExportOptions(includeContactData = false, includeSocialGraph = true)
        )
      )
      .fold(found => fail(found.mkString(", ")), _.toSet)
    assert(organizationTriples.contains(Triple(lia, Vocab.rdfType, Node.Ref(Foaf.Organization))))
    assert(!organizationTriples.exists(_.property == Foaf.knows))

  test("the FOAF Turtle subset preserves typed and language literals and rejects malformed syntax"):
    val valid =
      """@prefix ex: <https://example.test/> .
        |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        |ex:s ex:p "hello"@en .
        |ex:s ex:q "7"^^xsd:integer .
        |""".stripMargin
    val triples = Foaf.parseRdf(valid).fold(found => fail(found.mkString(", ")), identity)
    assertEquals(triples.map(_.obj), List(
      Node.Lit(Literal.tagged("hello", "en")),
      Node.Lit(Literal.integer(BigInt(7)))
    ))
    List(
      "@prefix ex: <https://example.test/> .\nex:s missing:p ex:o .",
      "@prefix ex: <https://example.test/> .\nex:s ex:p \"unterminated .",
      "@prefix ex: <https://example.test/> .\n<https://example.test/s ex:p ex:o .",
      "@prefix ex: <https://example.test/> .\nex:s ex:p ex:o . extra"
    ).foreach(document =>
      assert(Foaf.parseRdf(document).isLeft, s"malformed Turtle was accepted: $document")
    )

  test("relationship validation rejects a one-participant record"):
    val relationship = Iri("noesis:e/relationship-invalid")
    for
      base <- installed
      result <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(relationship, RelationshipsModule.Relationship)),
          Intent.Assert(
            Axiom.ObjectAssertion(
              relationship,
              RelationshipsModule.relationshipParticipant,
              lia
            )
          )
        )
      )
    yield assert(result.isLeft)

  test("active relationship anniversaries project one agenda occasion per participant"):
    val relationship = Iri("noesis:e/lia-marco-friends")
    for
      base <- installed
      _ <- commit(base, PrmCapture.contact(ContactInput(lia, "Lía García")))
      _ <- commit(base, PrmCapture.contact(ContactInput(marco, "Marco")))
      _ <- commit(
        base,
        PrmCapture.relationship(
          RelationshipInput(
            relationship,
            List(lia, marco),
            "friendship",
            anniversary = Some(Literal.anniversary(8, 2))
          )
        )
      )
      state <- base.state
    yield
      val occasions = Prm.occasions(state, LocalDate.of(2026, 7, 30))
      assertEquals(occasions.map(_.contact).toSet, Set(lia, marco))
      assertEquals(occasions.map(_.due).distinct, List(LocalDate.of(2026, 8, 2)))
      assertEquals(occasions.map(_.occasion).distinct, List("relationship anniversary"))

  test("module exporters enforce existence, disclosure, contact type, and social options"):
    val email = Iri("noesis:e/lia-export-email")
    val job = Iri("noesis:e/lia-export-job")
    val missing = Iri("noesis:e/missing")
    for
      base <- installed
      _ <- commit(
        base,
        PrmCapture.contact(
          ContactInput(
            lia,
            "Lía García",
            familyName = Some("García"),
            givenName = Some("Lía")
          )
        )
      )
      _ <- commit(base, PrmCapture.contact(ContactInput(marco, "Marco")))
      _ <- commit(
        base,
        PrmCapture.contact(
          ContactInput(acme, "Acme", ContactEntityKind.Organization)
        )
      )
      _ <- commit(
        base,
        PrmCapture.method(
          ContactMethodInput(email, lia, ContactKind.Email, "lia@example.test")
        )
      )
      jobResult <- base.commit(
        PrmCapture.employment(EmploymentInput(job, lia, acme, Some("Researcher")))
      )
      _ = assert(jobResult.isRight, jobResult)
      _ <- base.assert(
        Axiom.DataAssertion(lia, RelationshipsModule.birthday, Literal.anniversary(5, 12))
      )
      _ <- base.assert(Axiom.ObjectAssertion(lia, RelationshipsModule.knows, marco))
      _ <- base.assert(Axiom.ObjectAssertion(lia, RelationshipsModule.knows, acme))
      state <- base.state
      closure <- base.closure
      local = ExportContext.restricted(
        state,
        closure,
        config.policies,
        DisclosurePolicy.localOwner("owner"),
        config.namingProperties,
        config.namingSchemes
      )
      public = ExportContext.restricted(
        state,
        closure,
        config.policies,
        DisclosurePolicy.publicOnly("public"),
        config.namingProperties,
        config.namingSchemes
      )
      incomplete = ExportContext.restricted(
        state,
        closure.copy(
          inheritedIncompleteReasons = Set("test resource limit", "test round limit")
        ),
        config.policies,
        DisclosurePolicy.localOwner("owner"),
        config.namingProperties,
        config.namingSchemes
      )
    yield
      assertEquals(
        VCardExporter.render(incomplete, lia, ExportOptions()),
        Left(
          List(
            "reasoning incomplete (test resource limit, test round limit); " +
              "refusing to produce a possibly partial contact export"
          )
        )
      )
      assertEquals(
        FoafExporter.render(incomplete, lia, ExportOptions()),
        Left(
          List(
            "reasoning incomplete (test resource limit, test round limit); " +
              "refusing to produce a possibly partial contact export"
          )
        )
      )
      assertEquals(
        VCardExporter.render(local, missing, ExportOptions()),
        Left(List("no such contact: noesis:e/missing"))
      )
      assertEquals(
        FoafExporter.render(local, missing, ExportOptions()),
        Left(List("no such contact: noesis:e/missing"))
      )
      val minimizedVCard =
        VCardExporter.render(local, lia, ExportOptions()).fold(found => fail(found.mkString), identity)
      assert(!minimizedVCard.contains("EMAIL"))
      val localVCard =
        VCardExporter
          .render(local, lia, ExportOptions(includeContactData = true))
          .fold(found => fail(found.mkString), identity)
      assert(localVCard.contains("BDAY:--05-12"))
      assert(localVCard.contains("EMAIL:lia@example.test"))
      assert(localVCard.contains("ORG:Acme"))
      assertEquals(
        VCardExporter.render(public, lia, ExportOptions()),
        Left(List("no such contact: noesis:e/lia"))
      )

      val localFoaf = FoafExporter
        .render(
          local,
          lia,
          ExportOptions(includeContactData = true, includeSocialGraph = true)
        )
        .fold(found => fail(found.mkString), identity)
      val triples = Foaf.parseRdf(localFoaf).fold(found => fail(found.mkString), _.toSet)
      assert(triples.contains(Triple(lia, Foaf.mbox, Node.Ref(Iri.absolute("mailto:lia@example.test")))))
      assert(triples.contains(Triple(lia, Foaf.knows, Node.Ref(marco))))
      assert(!triples.contains(Triple(lia, Foaf.knows, Node.Ref(acme))))

      assertEquals(
        FoafExporter.render(
          public,
          lia,
          ExportOptions(includeContactData = true, includeSocialGraph = true)
        ),
        Left(List("no such contact: noesis:e/lia"))
      )

  test("the PRM agenda maps exact entry kinds, summaries, dates, and overdue boundaries"):
    val plan = Iri("noesis:e/agenda-plan")
    val dated = Iri("noesis:e/agenda-dated")
    val annualPast = Iri("noesis:e/agenda-annual-past")
    val annualFuture = Iri("noesis:e/agenda-annual-future")
    for
      base <- installed
      _ <- commit(base, PrmCapture.contact(ContactInput(lia, "Lía García")))
      _ <- base.assert(
        Axiom.DataAssertion(
          lia,
          RelationshipsModule.birthday,
          Literal.anniversary(7, 30)
        )
      )
      _ <- commit(base, PrmCapture.followUp(FollowUpInput(plan, lia, 30)))
      _ <- commit(
        base,
        PrmCapture.reminder(
          ReminderInput(dated, lia, Literal.date(PartialDate.of(2026, 7, 29)), "call")
        )
      )
      _ <- commit(
        base,
        PrmCapture.reminder(
          ReminderInput(annualPast, lia, Literal.anniversary(7, 1), "past annual")
        )
      )
      _ <- commit(
        base,
        PrmCapture.reminder(
          ReminderInput(annualFuture, lia, Literal.anniversary(8, 1), "future annual")
        )
      )
      state <- base.state
    yield
      assertEquals(
        PrmAgenda.entries(state, LocalDate.of(2026, 7, 30)),
        List(
          noesis.core.module.AgendaEntry(
            dated,
            lia,
            LocalDate.of(2026, 7, 29),
            "reminder",
            "call",
            overdue = true
          ),
          noesis.core.module.AgendaEntry(
            plan,
            lia,
            LocalDate.of(2026, 7, 30),
            "follow-up",
            "follow up",
            overdue = true
          ),
          noesis.core.module.AgendaEntry(
            PrmIds.child(lia, "occasion", "birthday"),
            lia,
            LocalDate.of(2026, 7, 30),
            "occasion",
            "birthday",
            overdue = true
          ),
          noesis.core.module.AgendaEntry(
            annualFuture,
            lia,
            LocalDate.of(2026, 8, 1),
            "reminder",
            "future annual",
            overdue = false
          ),
          noesis.core.module.AgendaEntry(
            annualPast,
            lia,
            LocalDate.of(2027, 7, 1),
            "reminder",
            "past annual",
            overdue = false
          )
        )
      )

  test("valid PRM enum values and exact cardinality boundaries pass validation"):
    val method = Iri("noesis:e/valid-method")
    val job = Iri("noesis:e/valid-job")
    val relationship = Iri("noesis:e/valid-relationship")
    for
      base <- installed
      _ <- commit(base, PrmCapture.contact(ContactInput(lia, "Lía")))
      _ <- commit(base, PrmCapture.contact(ContactInput(marco, "Marco")))
      _ <- commit(
        base,
        PrmCapture.method(ContactMethodInput(method, lia, ContactKind.Phone, "+52 55"))
      )
      _ <- List("retired", "invalid").traverse_(status =>
        base
          .commit(
            NonEmptyList.one(
              Intent.Supersede(
                method,
                RelationshipsModule.contactStatus,
                Node.Lit(Literal.string(status))
              )
            )
          )
          .map(result => assert(result.isRight, result))
      )
      jobResult <- base.commit(PrmCapture.employment(EmploymentInput(job, lia, acme)))
      _ = assert(jobResult.isRight, jobResult)
      ended <- base.commit(
        NonEmptyList.one(
          Intent.Supersede(
            job,
            RelationshipsModule.employmentStatus,
            Node.Lit(Literal.string("ended"))
          )
        )
      )
      _ = assert(ended.isRight, ended)
      _ <- commit(
        base,
        PrmCapture.relationship(
          RelationshipInput(relationship, List(lia, marco), "friendship")
        )
      )
      endedRelationship <- base.commit(
        NonEmptyList.one(
          Intent.Supersede(
            relationship,
            RelationshipsModule.relationshipStatus,
            Node.Lit(Literal.string("ended"))
          )
        )
      )
      _ = assert(endedRelationship.isRight, endedRelationship)
      _ <- List("likes", "dislikes", "allergy", "topic-to-avoid").zipWithIndex.traverse_ {
        entry =>
          val (polarity, index) = entry
          commit(
            base,
            PrmCapture.preference(
              PreferenceInput(Iri(s"noesis:e/valid-preference-$index"), lia, polarity, "value")
            )
          )
      }
      _ <- List("idea", "planned", "given", "received").zipWithIndex.traverse_ { entry =>
        val (status, index) = entry
        commit(
          base,
          PrmCapture.gift(
            GiftInput(Iri(s"noesis:e/valid-gift-$index"), "Book", Some(lia), status = status)
          )
        )
      }
      _ <- commit(
        base,
        PrmCapture.companionAnimal(
          CompanionAnimalInput(Iri("noesis:e/valid-cat"), "Michi", List(lia))
        )
      )
    yield ()

  test("a non-numeric follow-up cadence is rejected rather than bypassing positivity validation"):
    val plan = Iri("noesis:e/non-numeric-cadence")
    for
      base <- installed
      result <- base.commit(
        NonEmptyList.of(
          Intent.Assert(Axiom.ClassAssertion(plan, RelationshipsModule.FollowUpPlan)),
          Intent.Assert(Axiom.ObjectAssertion(plan, RelationshipsModule.followUpWith, lia)),
          Intent.Assert(
            Axiom.DataAssertion(
              plan,
              RelationshipsModule.cadenceDays,
              Literal.string("weekly")
            )
          ),
          Intent.OpenState(
            plan,
            RelationshipsModule.paused,
            Node.Lit(Literal.boolean(false))
          )
        )
      )
    yield result match
      case Left(CommitRejected.Invalid(found)) =>
        assertEquals(
          found,
          List("crm records: noesis:e/non-numeric-cadence has a non-positive follow-up cadence")
        )
      case other => fail(s"expected cadence validation failure, got $other")

  test("projection edge cases distinguish annual dates, active records, names, and duplicate reasons"):
    val email = Iri("noesis:e/edge-email")
    val marcoPhone = Iri("noesis:e/marco-edge-phone")
    val activeJob = Iri("noesis:e/active-job")
    val endedJob = Iri("noesis:e/ended-job")
    val annual = Iri("noesis:e/annual")
    for
      base <- installed
      _ <- commit(
        base,
        PrmCapture.contact(
          ContactInput(
            lia,
            "Lía García",
            familyName = Some("García"),
            givenName = Some("Lía")
          )
        )
      )
      _ <- commit(
        base,
        PrmCapture.method(
          ContactMethodInput(email, lia, ContactKind.Email, "lia@Example.TEST")
        )
      )
      _ <- commit(base, PrmCapture.contact(ContactInput(marco, "Marco")))
      _ <- commit(
        base,
        PrmCapture.method(
          ContactMethodInput(marcoPhone, marco, ContactKind.Phone, "+52 56")
        )
      )
      _ <- base.assert(
        Axiom.DataAssertion(
          marco,
          RelationshipsModule.birthday,
          Literal.anniversary(9, 1)
        )
      )
      _ <- base.assert(
        Axiom.DataAssertion(lia, RelationshipsModule.birthday, Literal.anniversary(5, 12))
      )
      activeJobResult <- base.commit(
        PrmCapture.employment(EmploymentInput(activeJob, lia, acme))
      )
      _ = assert(activeJobResult.isRight, activeJobResult)
      endedJobResult <- base.commit(
        PrmCapture.employment(EmploymentInput(endedJob, lia, Iri("noesis:e/old")))
      )
      _ = assert(endedJobResult.isRight, endedJobResult)
      _ <- base.commit(
        NonEmptyList.one(
          Intent.Supersede(
            endedJob,
            RelationshipsModule.employmentStatus,
            Node.Lit(Literal.string("ended"))
          )
        )
      )
      _ <- commit(
        base,
        PrmCapture.reminder(
          ReminderInput(annual, lia, Literal.anniversary(8, 1), "annual")
        )
      )
      state <- base.state
    yield
      val card = Prm.contactCard(state, lia)
      assertEquals(card.completeness, ContactCompleteness.Complete)
      assertEquals(
        Prm.contactCard(state, marco).completeness,
        ContactCompleteness.Complete
      )
      assertEquals(
        card.structuredName,
        Some(StructuredNameView(Some("García"), Some("Lía"), None, None, None))
      )
      assertEquals(card.employments.map(_.id), List(activeJob))
      assertEquals(Prm.remindersDue(state, LocalDate.of(2026, 7, 30)), Nil)
      assertEquals(
        Prm
          .occasions(state, LocalDate.of(2026, 6, 1))
          .filter(_.contact == lia)
          .map(entry => (entry.source, entry.occasion, entry.due)),
        List(
          (
            PrmIds.child(lia, "occasion", "birthday"),
            "birthday",
            LocalDate.of(2027, 5, 12)
          )
        )
      )
      assertEquals(
        Prm.duplicateCandidates(
          state,
          Some("LÍA GARCÍA"),
          List("email" -> "lia@example.test")
        ),
        List(
          DuplicateCandidate(
            lia,
            List("same name: LÍA GARCÍA", "same normalized email: lia@example.test")
          )
        )
      )
