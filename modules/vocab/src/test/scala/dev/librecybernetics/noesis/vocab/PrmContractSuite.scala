package dev.librecybernetics.noesis.vocab

import java.time.Instant

import munit.FunSuite
import dev.librecybernetics.noesis.core.capture.Intent
import dev.librecybernetics.noesis.logic.*

/** Mutation-sensitive contracts for the structured PRM boundaries (SPEC §7.3–§7.4).
  *
  * These assertions deliberately inspect every emitted field. Import and capture are persistence
  * boundaries: merely proving that their bundles can be committed would not protect the meaning of
  * individual fields.
  */
class PrmContractSuite extends FunSuite:
  private val lia = Iri("noesis:e/lia")
  private val marco = Iri("noesis:e/marco")
  private val acme = Iri("noesis:e/acme")

  private def nonBlank(label: String, value: String): NonBlank =
    NonBlank.parse(label, value).fold(fail(_), identity)

  private def positiveDays(value: Int): PositiveDays =
    PositiveDays.from(value).fold(fail(_), identity)

  private def intents(
      result: Either[List[String], cats.data.NonEmptyList[Intent]]
  ): List[Intent] =
    result.fold(problems => fail(problems.mkString(", ")), _.toList)

  private def intents(result: cats.data.NonEmptyList[Intent]): List[Intent] = result.toList

  private def asserted(found: List[Intent]): List[(Axiom, AxiomAnnotations)] =
    found.collect:
      case Intent.Assert(axiom, annotations) => axiom -> annotations

  private def opened(found: List[Intent]): List[(Iri, Iri, Node, AxiomAnnotations)] =
    found.collect:
      case Intent.OpenState(subject, property, value, None, annotations) =>
        (subject, property, value, annotations)

  private def dataValues(found: List[Intent], subject: Iri): Map[Iri, String] =
    asserted(found).collect:
      case (Axiom.DataAssertion(`subject`, property, value), _) => property -> value.text
    .toMap

  test("contact capture preserves entity kind, name kind, and every structured name component"):
    val input = ContactInput(
      lia,
      "Dr. Lía García PhD",
      ContactEntityKind.Organization,
      "professional",
      Some("García"),
      Some("Lía"),
      Some("María"),
      Some("Dr."),
      Some("PhD")
    )
    val found = intents(PrmCapture.contact(input))
    val name = PrmIds.child(lia, "name", "professional\u0000Dr. Lía García PhD")
    assertEquals(found.size, 10)
    assert(asserted(found).exists(_._1 == Axiom.ClassAssertion(lia, RelationshipsModule.Organization)))
    assert(asserted(found).exists(_._1 == Axiom.ClassAssertion(name, RelationshipsModule.Name)))
    assertEquals(
      dataValues(found, name),
      Map(
        RelationshipsModule.nameValue -> "Dr. Lía García PhD",
        RelationshipsModule.nameKind -> "professional",
        RelationshipsModule.familyName -> "García",
        RelationshipsModule.givenName -> "Lía",
        RelationshipsModule.additionalName -> "María",
        RelationshipsModule.honorificPrefix -> "Dr.",
        RelationshipsModule.honorificSuffix -> "PhD"
      )
    )
    assertEquals(
      opened(found).map((subject, property, value, _) => (subject, property, value)),
      List((lia, RelationshipsModule.hasName, Node.Ref(name)))
    )

  test("every contact method kind selects its exact class and preserves optional fields"):
    val expectedClasses = Map(
      ContactKind.Email -> RelationshipsModule.EmailAddress,
      ContactKind.Phone -> RelationshipsModule.TelephoneNumber,
      ContactKind.Sms -> RelationshipsModule.TelephoneNumber,
      ContactKind.WhatsApp -> RelationshipsModule.OnlineAccount,
      ContactKind.Signal -> RelationshipsModule.OnlineAccount,
      ContactKind.Telegram -> RelationshipsModule.OnlineAccount,
      ContactKind.Matrix -> RelationshipsModule.OnlineAccount,
      ContactKind.Website -> RelationshipsModule.ContactMethod,
      ContactKind.Social -> RelationshipsModule.OnlineAccount,
      ContactKind.Other -> RelationshipsModule.ContactMethod
    )
    expectedClasses.zipWithIndex.foreach: (entry, index) =>
      val (kind, cls) = entry
      val id = Iri(s"noesis:e/method-$index")
      val value = if kind == ContactKind.Email then "lia@example.test" else s"value-$index"
      val found = intents(
        PrmCapture.method(
          ContactMethodInput(id, lia, kind, value, Some("primary"), Some("home"), Some(0))
        )
      )
      assertEquals(found.size, 8)
      assert(asserted(found).exists(_._1 == Axiom.ClassAssertion(id, cls)))
      assert(asserted(found).exists(
        _._1 == Axiom.ObjectAssertion(id, RelationshipsModule.contactFor, lia)
      ))
      assertEquals(
        dataValues(found, id),
        Map(
          RelationshipsModule.contactKind -> kind.value,
          RelationshipsModule.contactLabel -> "primary",
          RelationshipsModule.contactPurpose -> "home"
        )
      )
      assertEquals(
        opened(found).map((_, property, value, _) => property -> value),
        List(
          RelationshipsModule.contactValue -> Node.Lit(Literal.string(value)),
          RelationshipsModule.contactStatus -> Node.Lit(Literal.string("active")),
          RelationshipsModule.preferenceRank -> Node.Lit(Literal.integer(BigInt(0)))
        )
      )

  test("postal capture preserves all components and marks address truth sensitive"):
    val address = Iri("noesis:e/address")
    val found = intents(
      PrmCapture.address(
        PostalAddressInput(
          address,
          lia,
          "Apartment 2, Calle 1, CDMX 01000, MX",
          Some("Calle 1"),
          Some("Apartment 2"),
          Some("CDMX"),
          Some("Ciudad de México"),
          Some("01000"),
          Some("mx"),
          Some("home"),
          Some("mailing")
        )
      )
    )
    assertEquals(found.size, 14)
    assertEquals(
      dataValues(found, address),
      Map(
        RelationshipsModule.contactKind -> "postal",
        RelationshipsModule.formattedAddress -> "Apartment 2, Calle 1, CDMX 01000, MX",
        RelationshipsModule.streetAddress -> "Calle 1",
        RelationshipsModule.extendedAddress -> "Apartment 2",
        RelationshipsModule.locality -> "CDMX",
        RelationshipsModule.region -> "Ciudad de México",
        RelationshipsModule.postalCode -> "01000",
        RelationshipsModule.countryCode -> "MX",
        RelationshipsModule.contactLabel -> "home",
        RelationshipsModule.contactPurpose -> "mailing"
      )
    )
    val sensitiveAssertions = asserted(found).collect:
      case (Axiom.DataAssertion(`address`, property, _), annotations)
          if Set(
            RelationshipsModule.formattedAddress,
            RelationshipsModule.streetAddress,
            RelationshipsModule.extendedAddress,
            RelationshipsModule.locality,
            RelationshipsModule.region,
            RelationshipsModule.postalCode,
            RelationshipsModule.countryCode
          ).contains(property) =>
        annotations.sensitivity -> annotations.recallUtility
    assertEquals(sensitiveAssertions.distinct, List(Some(Sensitivity.Sensitive) -> Some(0.0)))
    assert(opened(found).exists:
      case (`address`, property, _, annotations) =>
        property == RelationshipsModule.contactValue &&
          annotations.sensitivity.contains(Sensitivity.Sensitive) &&
          annotations.recallUtility.contains(0.0)
      case _ => false
    )

  test("all remaining structured capture operations preserve their complete payloads"):
    val employment = PrmCapture.employment(
      EmploymentInput(
        Iri("noesis:e/job"),
        lia,
        acme,
        Some("Researcher"),
        Some("R&D"),
        Some("CDMX")
      )
    ).toList
    assertEquals(
      dataValues(employment, Iri("noesis:e/job")),
      Map(
        RelationshipsModule.jobTitle -> "Researcher",
        RelationshipsModule.department -> "R&D",
        RelationshipsModule.workLocation -> "CDMX"
      )
    )
    assert(opened(employment).exists((_, property, value, _) =>
      property == RelationshipsModule.employmentStatus &&
        value == Node.Lit(Literal.string("active"))
    ))

    val interaction = intents(
      PrmCapture.interaction(
        InteractionInput(
          Iri("noesis:e/interaction"),
          List(lia, marco, lia),
          PartialDate.of(2026, 7, 30),
          "in-person",
          Some("meal"),
          Some("outbound"),
          Some("Lunch"),
          Sensitivity.Sensitive
        )
      )
    )
    assertEquals(
      asserted(interaction).collect:
        case (Axiom.ObjectAssertion(_, property, participant), _)
            if property == RelationshipsModule.participant =>
          participant
      ,
      List(lia, marco)
    )
    assertEquals(
      dataValues(interaction, Iri("noesis:e/interaction")),
      Map(
        RelationshipsModule.occurredAt -> "2026-07-30",
        RelationshipsModule.interactionChannel -> "in-person",
        RelationshipsModule.interactionKind -> "meal",
        RelationshipsModule.interactionDirection -> "outbound",
        RelationshipsModule.interactionSummary -> "Lunch"
      )
    )
    assert(asserted(interaction).exists:
      case (
            Axiom.DataAssertion(_, property, _),
            annotations
          ) =>
        property == RelationshipsModule.interactionSummary &&
          annotations.sensitivity.contains(Sensitivity.Sensitive)
      case _ => false
    )

    val relationship = intents(
      PrmCapture.relationship(
        RelationshipInput(
          Iri("noesis:e/relationship"),
          List(lia, marco, lia),
          "friendship",
          Some("met at school"),
          Some(Literal.anniversary(8, 2))
        )
      )
    )
    assertEquals(
      dataValues(relationship, Iri("noesis:e/relationship")),
      Map(
        RelationshipsModule.relationshipKind -> "friendship",
        RelationshipsModule.relationshipDescription -> "met at school",
        RelationshipsModule.anniversary -> "--08-02"
      )
    )
    assertEquals(
      asserted(relationship).collect:
        case (Axiom.ObjectAssertion(_, property, participant), _)
            if property == RelationshipsModule.relationshipParticipant =>
          participant
      ,
      List(lia, marco)
    )

    val note = intents(
      PrmCapture.note(
        NoteInput(
          Iri("noesis:e/note"),
          lia,
          "Private note",
          "meeting",
          Some(Instant.parse("2026-07-30T12:00:00Z")),
          Sensitivity.Sensitive
        )
      )
    )
    assertEquals(
      dataValues(note, Iri("noesis:e/note")),
      Map(
        RelationshipsModule.noteBody -> "Private note",
        RelationshipsModule.noteKind -> "meeting",
        RelationshipsModule.recordedAt -> "2026-07-30T12:00:00Z"
      )
    )

    PreferencePolarity.values.foreach: polarity =>
      val preference = intents(
        PrmCapture.preference(
          PreferenceInput(
            Iri(s"noesis:e/preference-${polarity.value}"),
            lia,
            polarity,
            nonBlank("preference text", "peanuts"),
            Some("food"),
            Sensitivity.Public
          )
        )
      )
      assertEquals(
        dataValues(preference, Iri(s"noesis:e/preference-${polarity.value}")),
        Map(
          RelationshipsModule.preferencePolarity -> polarity.value,
          RelationshipsModule.preferenceText -> "peanuts",
          RelationshipsModule.preferenceContext -> "food"
        )
      )
      val sensitivities = asserted(preference).collect:
        case (Axiom.DataAssertion(_, property, _), annotations)
            if Set(
              RelationshipsModule.preferencePolarity,
              RelationshipsModule.preferenceText,
              RelationshipsModule.preferenceContext
            ).contains(property) =>
          annotations.sensitivity
      val expected =
        if polarity == PreferencePolarity.Allergy then Some(Sensitivity.Sensitive)
        else Some(Sensitivity.Public)
      assertEquals(sensitivities.distinct, List(expected))

  test("plans, reminders, companions, circles, gifts, aliases, and retirement are exact bundles"):
    val followUp = intents(
      PrmCapture.followUp(
        FollowUpInput(Iri("noesis:e/follow"), lia, positiveDays(30), Some("phone"))
      )
    )
    assertEquals(
      dataValues(followUp, Iri("noesis:e/follow")),
      Map(
        RelationshipsModule.cadenceDays -> "30",
        RelationshipsModule.qualifyingChannel -> "phone"
      )
    )
    assert(opened(followUp).exists((_, property, value, _) =>
      property == RelationshipsModule.paused && value == Node.Lit(Literal.boolean(false))
    ))

    val reminder = intents(
      PrmCapture.reminder(
        ReminderInput(
          Iri("noesis:e/reminder"),
          lia,
          Literal.anniversary(5, 12),
          "birthday",
          Some("yearly")
        )
      )
    )
    assertEquals(
      dataValues(reminder, Iri("noesis:e/reminder")),
      Map(
        RelationshipsModule.due -> "--05-12",
        RelationshipsModule.occasion -> "birthday",
        RelationshipsModule.recurrence -> "yearly"
      )
    )

    val companion = intents(
      PrmCapture.companionAnimal(
        CompanionAnimalInput(Iri("noesis:e/cat"), "Michi", List(lia, marco, lia))
      )
    )
    val companionName = PrmIds.child(Iri("noesis:e/cat"), "name", "chosen\u0000Michi")
    assertEquals(
      dataValues(companion, companionName),
      Map(RelationshipsModule.nameValue -> "Michi", RelationshipsModule.nameKind -> "chosen")
    )
    assertEquals(
      asserted(companion).collect:
        case (Axiom.ObjectAssertion(_, property, contact), _)
            if property == RelationshipsModule.companionOf =>
          contact
      ,
      List(lia, marco)
    )

    val circle = intents(
      PrmCapture.circle(CircleInput(Iri("noesis:e/friends"), "Friends", List(lia, marco, lia)))
    )
    assertEquals(dataValues(circle, Iri("noesis:e/friends")), Map(Vocab.label -> "Friends"))
    assertEquals(
      asserted(circle).collect:
        case (Axiom.ObjectAssertion(_, property, contact), _)
            if property == RelationshipsModule.member =>
          contact
      ,
      List(lia, marco)
    )

    GiftStatus.values.foreach: status =>
      val gift = intents(
        PrmCapture.gift(
          GiftInput(
            Iri(s"noesis:e/gift-${status.value}"),
            nonBlank("gift description", "Book"),
            GiftParties.Between(lia, marco),
            status,
            Some("birthday")
          )
        )
      )
      assertEquals(
        dataValues(gift, Iri(s"noesis:e/gift-${status.value}")),
        Map(
          RelationshipsModule.giftDescription -> "Book",
          RelationshipsModule.giftStatus -> status.value,
          RelationshipsModule.giftOccasion -> "birthday"
        )
      )
      assert(asserted(gift).exists(_._1 ==
        Axiom.ObjectAssertion(Iri(s"noesis:e/gift-${status.value}"), RelationshipsModule.giftTo, lia)))
      assert(asserted(gift).exists(_._1 ==
        Axiom.ObjectAssertion(Iri(s"noesis:e/gift-${status.value}"), RelationshipsModule.giftFrom, marco)))

    val alias = intents(PrmCapture.alternativeName(lia, "Lili", "nickname"))
    val aliasName = PrmIds.child(lia, "name", "nickname\u0000Lili")
    assertEquals(
      dataValues(alias, aliasName),
      Map(RelationshipsModule.nameValue -> "Lili", RelationshipsModule.nameKind -> "nickname")
    )
    assert(asserted(alias).exists(_._1 ==
      Axiom.ObjectAssertion(lia, RelationshipsModule.hasAlternativeName, aliasName)))
    assertEquals(
      PrmCapture.retire(Iri("noesis:e/method")).toList,
      List(
        Intent.Supersede(
          Iri("noesis:e/method"),
          RelationshipsModule.contactStatus,
          Node.Lit(Literal.string("retired"))
        )
      )
    )
    assertEquals(PositiveDays.from(-1), Left("follow-up cadence must be positive"))
    assertEquals(GiftParties.parse(None, None), Left("a gift needs a recipient or giver"))
    assertEquals(
      PrmCapture.gift(
        GiftInput(
          Iri("noesis:e/to-only"),
          nonBlank("gift description", "Book"),
          GiftParties.To(lia)
        )
      ).length,
      4
    )
    assertEquals(
      PrmCapture.gift(
        GiftInput(
          Iri("noesis:e/from-only"),
          nonBlank("gift description", "Book"),
          GiftParties.From(lia)
        )
      ).length,
      4
    )

  test("vCard conversion maps every supported field and rejects conversion failures"):
    val card = VCardContact(
      Some("urn:uuid:complete"),
      "Dr. Lía García",
      List("García", "Lía", "María", "Dr.", "PhD"),
      List("Lili", "Lili"),
      Some(Literal.date(PartialDate.of(1988, 5, 12))),
      Some(Literal.anniversary(6, 18)),
      List(VCardField("lia@example.com", Some("Primary"), Set("home", "work"))),
      List(VCardField("+52551234", None, Set("work"))),
      List(
        VCardField("signal:lia", None, Set.empty),
        VCardField("telegram:lia", None, Set.empty),
        VCardField("matrix:@lia:example.org", None, Set.empty),
        VCardField("social:lia", None, Set.empty)
      ),
      List(VCardField("https://example.org/lia", None, Set("home"))),
      List(
        VCardAddress(
          None,
          Some("Calle 1"),
          Some("Apartment 2"),
          Some("CDMX"),
          Some("CDMX"),
          Some("01000"),
          Some("MX"),
          Some("Casa")
        )
      ),
      Some("Molina Labs"),
      None,
      Some("Scientist"),
      List(VCardField("Marco", None, Set.empty)),
      Some("Met at a conference"),
      List("Friends", "Friends")
    )
    val (contact, converted) =
      VCard.intents(card).fold(problems => fail(problems.mkString(", ")), identity)
    assertEquals(contact, Iri("noesis:e/prm-contact-187eb6eb7728d054b41b"))
    val found = converted.toList
    val values = asserted(found).collect:
      case (Axiom.DataAssertion(subject, property, value), _) =>
        (subject, property, value.text)
    assert(values.exists((_, property, value) =>
      property == RelationshipsModule.birthday && value == "1988-05-12"
    ))
    assertEquals(
      values.collect:
        case (_, property, value) if property == RelationshipsModule.contactKind => value
      .toSet,
      Set("email", "phone", "signal", "telegram", "matrix", "social", "website", "postal")
    )
    assert(values.exists((_, property, value) =>
      property == RelationshipsModule.formattedAddress &&
        value == "Calle 1, Apartment 2, CDMX, CDMX, 01000, MX"
    ))
    assert(values.exists((_, property, value) =>
      property == RelationshipsModule.jobTitle && value == "Scientist"
    ))
    assert(values.exists((_, property, value) =>
      property == RelationshipsModule.noteBody && value == "Met at a conference"
    ))
    assert(values.exists((_, property, value) =>
      property == RelationshipsModule.relationshipKind && value == "related"
    ))
    assert(values.exists((_, property, value) =>
      property == RelationshipsModule.occasion && value == "anniversary"
    ))
    assert(values.exists((_, property, value) =>
      property == RelationshipsModule.recurrence && value == "yearly"
    ))
    assertEquals(values.count((_, property, _) => property == Vocab.label), 1)
    assertEquals(values.count((_, property, value) =>
      property == RelationshipsModule.nameValue && value == "Lili"
    ), 1)
    assert(values.exists((_, property, value) =>
      property == RelationshipsModule.identifierValue && value == "urn:uuid:complete"
    ))
    assert(asserted(found).exists(_._1 ==
      Axiom.ObjectAssertion(
        PrmIds.child(contact, "identifier", "vcard\u0000urn:uuid:complete"),
        RelationshipsModule.identifierScheme,
        Iri("noesis:e/identifier-scheme-vcard-uid")
      )))
    assert(values.exists((subject, property, value) =>
      subject == PrmIds.child(
        PrmIds.record("organization", "Molina Labs"),
        "name",
        "professional\u0000Molina Labs"
      ) &&
        property == RelationshipsModule.nameKind &&
        value == "professional"
    ))
    val expectedRecordIds = Set(
      PrmIds.child(contact, "email", "0\u0000lia@example.com"),
      PrmIds.child(contact, "phone", "0\u0000+52551234"),
      PrmIds.child(contact, "account", "0\u0000signal:lia"),
      PrmIds.child(contact, "account", "1\u0000telegram:lia"),
      PrmIds.child(contact, "account", "2\u0000matrix:@lia:example.org"),
      PrmIds.child(contact, "account", "3\u0000social:lia"),
      PrmIds.child(contact, "url", "0\u0000https://example.org/lia"),
      PrmIds.child(
        contact,
        "address",
        "0\u0000Calle 1, Apartment 2, CDMX, CDMX, 01000, MX"
      ),
      PrmIds.child(contact, "reminder", "anniversary\u0000--06-18"),
      PrmIds.child(contact, "employment", PrmIds.record("organization", "Molina Labs").value),
      PrmIds.child(contact, "identifier", "vcard\u0000urn:uuid:complete"),
      PrmIds.child(contact, "note", "Met at a conference"),
      PrmIds.child(contact, "relationship", "0\u0000Marco"),
      PrmIds.record("contact", "vcard-related\u0000Marco"),
      PrmIds.record("circle", "Friends")
    )
    val actualRecordIds = asserted(found).collect:
      case (Axiom.ClassAssertion(record, cls), _)
          if Set(
            RelationshipsModule.ContactMethod,
            RelationshipsModule.EmailAddress,
            RelationshipsModule.TelephoneNumber,
            RelationshipsModule.OnlineAccount,
            RelationshipsModule.PostalAddress,
            RelationshipsModule.Reminder,
            RelationshipsModule.Employment,
            RelationshipsModule.ExternalIdentifier,
            RelationshipsModule.ContactNote,
            RelationshipsModule.Relationship,
            RelationshipsModule.Person,
            RelationshipsModule.Circle
          ).contains(cls) && record != contact =>
        record
    .toSet
    assert(expectedRecordIds.subsetOf(actualRecordIds), clues(expectedRecordIds -- actualRecordIds))

    val invalid = card.copy(
      uid = None,
      formattedName = "No UID",
      emails = List(VCardField("bad")),
      addresses = List(VCardAddress(Some(""), None, None, None, None, None, None, None)),
      nicknames = List(""),
      categories = List(""),
      related = List(VCardField(""))
    )
    val problems = VCard.intents(invalid).left.toOption.getOrElse(fail("invalid card accepted"))
    assertEquals(
      problems,
      List(
        "email address must contain one non-edge @",
        "formatted address must not be blank",
        "alternative name must not be blank",
        "display name must not be blank",
        "circle name must not be blank"
      )
    )
    val shortName = card.copy(
      uid = None,
      formattedName = "Lía",
      structuredName = List("García"),
      nicknames = Nil,
      birthday = None,
      anniversary = None,
      emails = Nil,
      phones = Nil,
      accounts = Nil,
      urls = Nil,
      addresses = Nil,
      organization = None,
      title = None,
      role = None,
      related = Nil,
      note = None,
      categories = Nil
    )
    val (_, shortIntents) =
      VCard.intents(shortName).fold(problems => fail(problems.mkString(", ")), identity)
    val shortContact = PrmIds.record("contact", "name:Lía")
    val shortNameRecord = PrmIds.child(shortContact, "name", "chosen\u0000Lía")
    assertEquals(
      dataValues(shortIntents.toList, shortNameRecord),
      Map(
        RelationshipsModule.nameValue -> "Lía",
        RelationshipsModule.nameKind -> "chosen",
        RelationshipsModule.familyName -> "García"
      )
    )

  test("vCard framing, unfolding, escaping, and card numbering have exact observable errors"):
    val folded =
      "ignored\rBEGIN:VCARD\rVERSION:4.0\rFN:Li\r a\r\t!\rN:Li;;;;\rEND:VCARD\rtrailer"
    assertEquals(
      VCard.parse(folded).map(_.map(_.formattedName)),
      Right(List("Lia!"))
    )
    assertEquals(
      VCard.parse(
        "BEGIN:VCARD\nVERSION:4.0\nFN:One\nEND:VCARD\n" +
          "BEGIN:VCARD\nVERSION:3.0\nFN:Two\nBROKEN\nEND:VCARD\n"
      ),
      Left(
        List(
          "card 2: content line has no ':' separator: BROKEN"
        )
      )
    )
    assertEquals(
      VCard.parse("BEGIN:VCARD\nVERSION:3.0\nFN: \nEND:VCARD\n"),
      Left(List("card 1: VERSION must be 4.0", "card 1: FN is required"))
    )
    val shortAddress = VCard
      .parse("BEGIN:VCARD\nVERSION:4.0\nFN:A\nADR:;;Street\nEND:VCARD\n")
      .fold(problems => fail(problems.mkString(", ")), identity)
      .headOption
      .flatMap(_.addresses.headOption)
      .getOrElse(fail("address missing"))
    assertEquals(
      shortAddress,
      VCardAddress(None, Some("Street"), None, None, None, None, None, None)
    )
    val escaped =
      """BEGIN:VCARD
        |VERSION:4.0
        |FN:A\\B\, C\; D\NNext
        |EMAIL;TYPE=HOME;LABEL=work\:main:a\:b@example.test
        |END:VCARD
        |""".stripMargin
    val parsed = VCard
      .parse(escaped)
      .fold(problems => fail(problems.mkString(", ")), identity)
      .headOption
      .getOrElse(fail("card missing"))
    assertEquals(parsed.formattedName, "A\\B, C; D\nNext")
    assertEquals(
      parsed.emails,
      List(VCardField("a:b@example.test", Some("work:main"), Set("home")))
    )
    val mixed =
      """BEGIN:VCARD
        |VERSION:4.0
        |FN:Good
        |END:VCARD
        |BEGIN:VCARD
        |VERSION:4.0
        |FN:Bad
        |EMAIL:bad
        |END:VCARD
        |""".stripMargin
    assertEquals(
      VCard.importIntents(mixed),
      Left(List("card 2: email address must contain one non-edge @"))
    )

  test("FOAF parser reports every token boundary and handles comments, escapes, and N-Triples"):
    val cases = List(
      "@prefix ex: <https://example.test/> .\n." ->
        List("line 2: unexpected end of statement"),
      "@prefix ex: <https://example.test/> .\nex:s ." ->
        List("line 2: unexpected end of statement"),
      "@prefix ex: <https://example.test/> .\nex:s ex:p" ->
        List("line 2: unexpected end of statement"),
      "@prefix ex: <https://example.test/> .\n<unterminated ex:p ex:o ." ->
        List("line 2: unterminated IRI"),
      "@prefix ex: <https://example.test/> .\nex:s ex:p \"unterminated ." ->
        List("line 2: unterminated literal"),
      "@prefix ex: <https://example.test/> .\nex:s missing:p ex:o ." ->
        List("line 2: unknown prefix 'missing'"),
      "@prefix ex: <https://example.test/> .\nnot-an-iri ex:p ex:o ." ->
        List("line 2: expected an IRI, found 'not-an-iri'"),
      "@prefix ex: <https://example.test/> .\nex:s ex:p ex:o . extra" ->
        List("line 2: only one triple per Turtle statement is supported")
    )
    cases.foreach: (document, expected) =>
      assertEquals(Foaf.parseRdf(document), Left(expected))

    val valid =
      """@prefix ex: <https://example.test/> .
        |# ignored
        |
        |ex:a\.b ex:p "escaped \" quote"@en .
        |ex:s ex:q "7"^^<http://www.w3.org/2001/XMLSchema#integer> .
        |""".stripMargin
    assertEquals(
      Foaf.parseRdf(valid),
      Right(
        List(
          Triple(
            Iri.absolute("https://example.test/a.b"),
            Iri.absolute("https://example.test/p"),
            Node.Lit(Literal.tagged("escaped \" quote", "en"))
          ),
          Triple(
            Iri.absolute("https://example.test/s"),
            Iri.absolute("https://example.test/q"),
            Node.Lit(Literal.integer(BigInt(7)))
          )
        )
      )
    )
    assertEquals(
      Foaf.parseRdf(
        "<https://example.test/s> <https://example.test/p> <https://example.test/o> ."
      ),
      Right(
        List(
          Triple(
            Iri.absolute("https://example.test/s"),
            Iri.absolute("https://example.test/p"),
            Node.Ref(Iri.absolute("https://example.test/o"))
          )
        )
      )
    )
    assertEquals(
      Foaf.parseRdf("<https://example.test/s> broken"),
      Left(List("line 1: expected '<' at offset 25"))
    )
    val malformedExpanded = Foaf.parseRdf(
      "@prefix ex: <https://example.test/> .\nex:s ex:p \"x\"@en_US ."
    )
    assert(
      malformedExpanded.left.exists(_.exists(_.startsWith("line 2:"))),
      malformedExpanded
    )
    val prefixes = Map("ex" -> "https://example.test/")
    assertEquals(Foaf.token("  ex:s rest", 0), Right("ex:s" -> 6))
    assertEquals(Foaf.token("  <https://example.test/s> rest", 0), Right("<https://example.test/s>" -> 26))
    assertEquals(Foaf.token("  \"text\"@en rest", 0), Right("\"text\"@en" -> 11))
    assertEquals(Foaf.token("  \"7\"^^ex:number rest", 0), Right("\"7\"^^ex:number" -> 16))
    assertEquals(Foaf.literalToken("\"\"", 0), Right("\"\"" -> 2))
    assertEquals(Foaf.literalToken("\"a\\\\\\\"b\"", 0), Right("\"a\\\\\\\"b\"" -> 8))
    assertEquals(Foaf.literalToken("\"x\"@en", 0), Right("\"x\"@en" -> 6))
    assertEquals(Foaf.literalToken("\"x\"^^ex:t", 0), Right("\"x\"^^ex:t" -> 9))
    assertEquals(Foaf.literalToken("\"x\"junk rest", 0), Right("\"x\"" -> 3))
    assertEquals(Foaf.literalToken("\"x\\", 0), Left("unterminated literal"))
    assertEquals(Foaf.token("ex:s rest", 0), Right("ex:s" -> 4))
    assertEquals(Foaf.expandIriToken("<https://example.test/x>", prefixes), Right("<https://example.test/x>"))
    assertEquals(
      Foaf.expandIriToken("<https://example.test/x", prefixes),
      Left("unknown prefix '<https'")
    )
    assertEquals(Foaf.expandIriToken("https://example.test/x>", prefixes), Left("unknown prefix 'https'"))
    assertEquals(Foaf.expandLiteralToken("\"^^x\"^^ex:t", prefixes), Right("\"^^x\"^^<https://example.test/t>"))
    assertEquals(Foaf.expandLiteralToken("\"plain\"", prefixes), Right("\"plain\""))
    assertEquals(Foaf.stripScheme("MAILTO:LIA@example.test", "mailto:"), "LIA@example.test")
    assertEquals(Foaf.stripScheme("urn:number", "tel:"), "urn:number")

  test("vCard writing escapes every component and folds at the exact byte boundary"):
    val address = PostalAddressView(
      "Home: A, B",
      Some("Street;One"),
      Some("Unit,2"),
      Some("City\nTwo"),
      Some("R\\1"),
      Some("01;000"),
      Some("M,X")
    )
    val card = ContactCard(
      lia,
      "Lía, García; One\\Two\nNext",
      Some(
        StructuredNameView(
          Some("García, One"),
          Some("Lía;Two"),
          Some("M\\N"),
          Some("Dr.\n"),
          Some("PhD")
        )
      ),
      organization = false,
      None,
      List(
        ContactMethodView(
          Iri("noesis:e/address"),
          "postal",
          address.formatted,
          Some("home:main"),
          None,
          "active",
          None,
          Some(address)
        )
      ),
      Nil,
      Nil,
      ContactCompleteness.Reachable
    )
    val rendered = VCard.write(card, _.display)
    assert(rendered.contains("FN:Lía\\, García\\; One\\\\Two\\nNext\r\n"))
    assert(rendered.contains("N:García\\, One;Lía\\;Two;M\\\\N;Dr.\\n;PhD\r\n"))
    assert(rendered.contains(
      "ADR;LABEL=Home\\: A\\, B:;Unit\\,2;Street\\;One;City\\nTwo;R\\\\1;01\\;000;M\\,X\r\n"
    ))
    val emptyAddress = address.copy(
      formatted = "",
      street = None,
      extended = None,
      locality = None,
      region = None,
      postalCode = None,
      countryCode = None
    )
    val sparse = card.copy(
      displayName = "Lía",
      structuredName = None,
      methods = List(
        ContactMethodView(
          Iri("noesis:e/empty-address"),
          "postal",
          "",
          None,
          None,
          "active",
          None,
          Some(emptyAddress)
        )
      )
    )
    val sparseRendered = VCard.write(sparse, _.display)
    assert(sparseRendered.contains("N:Lía;;;;\r\n"))
    assert(sparseRendered.contains("ADR;LABEL=:;;;;;;\r\n"))

    def renderedName(length: Int): List[String] =
      VCard
        .write(card.copy(displayName = "a" * length, structuredName = None, methods = Nil), _.display)
        .split("\r\n")
        .toList
    assert(renderedName(72).contains("FN:" + ("a" * 72)))
    assert(renderedName(73).sliding(2).exists:
      case List(first, second) => first == "FN:" + ("a" * 72) && second == " a"
      case _                   => false
    )
    assertEquals(VCard.foldLine("a" * 75), "a" * 75)
    assertEquals(VCard.foldLine("a" * 76), ("a" * 75) + "\r\n a")
    assertEquals(
      VCard.foldLine("a" * 149),
      ("a" * 75) + "\r\n " + ("a" * 74)
    )
    assertEquals(
      VCard.foldLine("a" * 150),
      ("a" * 75) + "\r\n " + ("a" * 74) + "\r\n a"
    )
    assertEquals(VCard.purpose(Set("home")), Some("home"))
    assertEquals(VCard.purpose(Set("work")), Some("work"))
    assertEquals(VCard.purpose(Set("mobile")), None)

  test("FOAF import preserves exact record identities, fallbacks, errors, and annotations"):
    val external = Iri.absolute("https://example.test/lia")
    val remote = Iri.absolute("https://example.test/account")
    val graph = List(
      Triple(external, Vocab.rdfType, Node.Ref(Foaf.Person)),
      Triple(external, Foaf.nick, Node.Lit(Literal.string("Lili"))),
      Triple(external, Foaf.mbox, Node.Ref(Iri.absolute("MAILTO:LIA@example.test"))),
      Triple(external, Foaf.phone, Node.Ref(Iri.absolute("urn:number"))),
      Triple(external, Foaf.account, Node.Ref(remote)),
      Triple(external, Foaf.homepage, Node.Ref(Iri.absolute("https://lia.example/")))
    )
    val batches = Foaf.intents(graph).fold(problems => fail(problems.mkString(", ")), identity)
    val (contact, batch) = batches.headOption.getOrElse(fail("contact missing"))
    assertEquals(contact, PrmIds.record("foaf", external.value))
    val found = batch.toList
    val methodIds = asserted(found).collect:
      case (Axiom.ClassAssertion(id, cls), _)
          if Set(
            RelationshipsModule.EmailAddress,
            RelationshipsModule.TelephoneNumber,
            RelationshipsModule.OnlineAccount,
            RelationshipsModule.ContactMethod
          ).contains(cls) =>
        id
    .toSet
    assertEquals(
      methodIds,
      Set(
        PrmIds.child(contact, "email", "0\u0000LIA@example.test"),
        PrmIds.child(contact, "phone", "0\u0000urn:number"),
        PrmIds.child(contact, "account", s"0\u0000${remote.value}"),
        PrmIds.child(contact, "url", "0\u0000https://lia.example/")
      )
    )
    val values = asserted(found).collect:
      case (Axiom.DataAssertion(subject, property, value), annotations) =>
        (subject, property, value.text, annotations)
    assert(values.exists((_, property, value, _) =>
      property == RelationshipsModule.nameValue && value == "Lili"
    ))
    assert(values.exists((_, property, value, _) =>
      property == RelationshipsModule.contactPurpose && value == "homepage"
    ))
    val currentValues = opened(found).collect:
      case (_, property, Node.Lit(value), _) if property == RelationshipsModule.contactValue =>
        value.text
    assertEquals(
      currentValues.toSet,
      Set("LIA@example.test", "urn:number", remote.value, "https://lia.example/")
    )
    assert(values.forall((_, _, _, annotations) =>
      annotations.truthConfidence.contains(0.7) &&
        annotations.provenance.proposedBy.contains("foaf-import")
    ))
    val identifier = PrmIds.child(contact, "identifier", s"foaf\u0000${external.value}")
    assert(asserted(found).exists(_._1 ==
      Axiom.ClassAssertion(identifier, RelationshipsModule.ExternalIdentifier)))
    assert(asserted(found).exists(_._1 ==
      Axiom.ObjectAssertion(
        identifier,
        RelationshipsModule.identifierScheme,
        Iri("noesis:e/identifier-scheme-foaf-iri")
      )))

    val invalidMethod = Foaf.intents(
      List(
        Triple(external, Vocab.rdfType, Node.Ref(Foaf.Person)),
        Triple(external, Foaf.name, Node.Lit(Literal.string("Lía"))),
        Triple(external, Foaf.mbox, Node.Ref(Iri.absolute("mailto:bad")))
      )
    )
    assertEquals(invalidMethod, Left(List("email address must contain one non-edge @")))
    val invalidContact = Foaf.intents(
      List(
        Triple(external, Vocab.rdfType, Node.Ref(Foaf.Person)),
        Triple(external, Foaf.name, Node.Lit(Literal.string("")))
      )
    )
    assertEquals(invalidContact, Left(List("display name must not be blank")))
    val accountOnly = Iri.absolute("https://example.test/account-owner")
    val unrelated = Iri.absolute("https://example.test/unrelated")
    val accountBatches = Foaf.intents(
      List(
        Triple(accountOnly, Foaf.account, Node.Ref(remote)),
        Triple(unrelated, Foaf.page, Node.Ref(remote))
      )
    ).fold(problems => fail(problems.mkString(", ")), identity)
    assertEquals(accountBatches.map(_._1), List(PrmIds.record("foaf", accountOnly.value)))
    val telephone = Iri.absolute("https://example.test/telephone")
    val telephoneBatch = Foaf.intents(
      List(
        Triple(telephone, Vocab.rdfType, Node.Ref(Foaf.Person)),
        Triple(telephone, Foaf.name, Node.Lit(Literal.string("Telephone"))),
        Triple(telephone, Foaf.phone, Node.Ref(Iri.absolute("tel:+5255")))
      )
    ).fold(problems => fail(problems.mkString(", ")), identity)
    val telephoneValues = telephoneBatch.flatMap(_._2.toList).collect:
      case Intent.OpenState(_, property, Node.Lit(value), _, _)
          if property == RelationshipsModule.contactValue =>
        value.text
    assertEquals(telephoneValues, List("+5255"))

  test("FOAF mapped edge values preserve SMS export and canonical birthday input"):
    val card = ContactCard(
      lia,
      "Lía",
      None,
      organization = false,
      Some(Literal.date(PartialDate.of(1988, 5, 12))),
      List(
        ContactMethodView(
          Iri("noesis:e/sms"),
          "sms",
          "+52 55",
          None,
          None,
          "active",
          None,
          None
        )
      ),
      Nil,
      Nil,
      ContactCompleteness.Complete
    )
    val triples = Foaf
      .parseRdf(
        Foaf.write(card, options = FoafExportOptions(includeContactData = true))
      )
      .fold(problems => fail(problems.mkString(", ")), _.toSet)
    assert(triples.contains(Triple(lia, Foaf.phone, Node.Ref(Iri.absolute("tel:+5255")))))
    assert(triples.contains(
      Triple(lia, Foaf.birthday, Node.Lit(Literal.string("05-12")))
    ))
    val imported = Foaf.intents(
      List(
        Triple(lia, Vocab.rdfType, Node.Ref(Foaf.Person)),
        Triple(lia, Foaf.name, Node.Lit(Literal.string("Lía"))),
        Triple(lia, Foaf.birthday, Node.Lit(Literal.string("--05-12")))
      )
    ).fold(problems => fail(problems.mkString(", ")), identity)
    val birthdayValues = imported.flatMap(_._2.toList).collect:
      case Intent.Assert(Axiom.DataAssertion(_, property, value), _)
          if property == RelationshipsModule.birthday =>
        value.text
    assertEquals(birthdayValues, List("--05-12"))

  test("PRM triple projections distinguish defaults, status, structure, and import evidence"):
    val method = Iri("noesis:e/method")
    val address = Iri("noesis:e/address")
    val employment = Iri("noesis:e/employment")
    val interaction = Iri("noesis:e/interaction")
    val triples = Set(
      Triple(method, RelationshipsModule.contactFor, Node.Ref(lia)),
      Triple(method, RelationshipsModule.contactValue, Node.Lit(Literal.string(" value "))),
      Triple(address, RelationshipsModule.contactFor, Node.Ref(lia)),
      Triple(address, RelationshipsModule.contactKind, Node.Lit(Literal.string("postal"))),
      Triple(address, RelationshipsModule.contactValue, Node.Lit(Literal.string("formatted"))),
      Triple(address, RelationshipsModule.contactStatus, Node.Lit(Literal.string("active"))),
      Triple(address, RelationshipsModule.formattedAddress, Node.Lit(Literal.string("Full"))),
      Triple(address, RelationshipsModule.streetAddress, Node.Lit(Literal.string("Street"))),
      Triple(address, RelationshipsModule.extendedAddress, Node.Lit(Literal.string("Unit"))),
      Triple(address, RelationshipsModule.locality, Node.Lit(Literal.string("City"))),
      Triple(address, RelationshipsModule.region, Node.Lit(Literal.string("Region"))),
      Triple(address, RelationshipsModule.postalCode, Node.Lit(Literal.string("01000"))),
      Triple(address, RelationshipsModule.countryCode, Node.Lit(Literal.string("MX"))),
      Triple(employment, RelationshipsModule.employmentFor, Node.Ref(lia)),
      Triple(employment, RelationshipsModule.employer, Node.Ref(acme)),
      Triple(employment, RelationshipsModule.employmentStatus, Node.Lit(Literal.string("active"))),
      Triple(employment, RelationshipsModule.jobTitle, Node.Lit(Literal.string("Researcher"))),
      Triple(employment, RelationshipsModule.department, Node.Lit(Literal.string("R&D"))),
      Triple(employment, RelationshipsModule.workLocation, Node.Lit(Literal.string("CDMX"))),
      Triple(interaction, RelationshipsModule.participant, Node.Ref(lia)),
      Triple(interaction, RelationshipsModule.participant, Node.Ref(marco)),
      Triple(interaction, RelationshipsModule.occurredAt, Node.Lit(Literal.date(PartialDate.of(2026, 7, 30)))),
      Triple(interaction, RelationshipsModule.interactionKind, Node.Lit(Literal.string("meal"))),
      Triple(interaction, RelationshipsModule.interactionDirection, Node.Lit(Literal.string("outbound"))),
      Triple(interaction, RelationshipsModule.interactionSummary, Node.Lit(Literal.string("Lunch")))
    )
    assertEquals(
      Prm.contactMethods(triples, lia),
      List(
        ContactMethodView(method, "other", " value ", None, None, "active", None, None),
        ContactMethodView(
          address,
          "postal",
          "formatted",
          None,
          None,
          "active",
          None,
          Some(
            PostalAddressView(
              "Full",
              Some("Street"),
              Some("Unit"),
              Some("City"),
              Some("Region"),
              Some("01000"),
              Some("MX")
            )
          )
        )
      )
    )
    assertEquals(
      Prm.currentEmployments(triples, lia),
      List(EmploymentView(employment, acme, Some("Researcher"), Some("R&D"), Some("CDMX")))
    )
    assertEquals(
      Prm.interactionsFor(triples, lia),
      List(
        InteractionView(
          interaction,
          List(lia, marco),
          PartialDate.of(2026, 7, 30),
          "other",
          Some("meal"),
          Some("outbound"),
          Some("Lunch")
        )
      )
    )
    assertEquals(ContactMethodView(method, "sms", " +52 55 ", None, None, "active", None, None).normalizedValue, "+5255")

    val name = Iri("noesis:e/name")
    val other = Iri("noesis:e/other")
    val evidence = cats.data.NonEmptyList.of[Intent](
      Intent.OpenState(lia, RelationshipsModule.hasName, Node.Ref(name)),
      Intent.OpenState(other, RelationshipsModule.hasName, Node.Ref(Iri("noesis:e/noise-name"))),
      Intent.Assert(Axiom.DataAssertion(name, RelationshipsModule.noteBody, Literal.string("noise"))),
      Intent.Assert(
        Axiom.DataAssertion(
          Iri("noesis:e/not-a-name"),
          RelationshipsModule.nameValue,
          Literal.string("noise")
        )
      ),
      Intent.Assert(Axiom.DataAssertion(name, RelationshipsModule.nameValue, Literal.string("Lía"))),
      Intent.Assert(Axiom.DataAssertion(method, RelationshipsModule.contactKind, Literal.string("sms"))),
      Intent.Assert(Axiom.DataAssertion(method, RelationshipsModule.noteKind, Literal.string("noise"))),
      Intent.Assert(
        Axiom.DataAssertion(
          Iri("noesis:e/not-owned"),
          RelationshipsModule.contactKind,
          Literal.string("noise")
        )
      ),
      Intent.Assert(Axiom.ObjectAssertion(method, RelationshipsModule.contactFor, lia)),
      Intent.OpenState(method, RelationshipsModule.contactValue, Node.Lit(Literal.string("+52 55"))),
      Intent.OpenState(method, RelationshipsModule.noteKind, Node.Lit(Literal.string("noise"))),
      Intent.OpenState(
        Iri("noesis:e/not-owned"),
        RelationshipsModule.contactValue,
        Node.Lit(Literal.string("noise"))
      ),
      Intent.Assert(
        Axiom.ObjectAssertion(Iri("noesis:e/untyped-method"), RelationshipsModule.contactFor, lia)
      ),
      Intent.OpenState(
        Iri("noesis:e/untyped-method"),
        RelationshipsModule.contactValue,
        Node.Lit(Literal.string("custom"))
      ),
      Intent.Assert(Axiom.ObjectAssertion(Iri("noesis:e/noise"), RelationshipsModule.contactFor, other))
    )
    assertEquals(
      Prm.importEvidence(lia, evidence),
      Some("Lía") -> List("sms" -> "+52 55", "other" -> "custom")
    )

  test("PRM projection helpers reject one-sided matches and preserve exact normalization boundaries"):
    val name = Iri("noesis:e/name")
    val cls = Iri("crm:Class")
    val property = Iri("crm:property")
    val dataProperty = Iri("crm:data")
    val triples = Set(
      Triple(lia, Vocab.rdfType, Node.Ref(cls)),
      Triple(marco, Vocab.rdfType, Node.Ref(Iri("crm:OtherClass"))),
      Triple(Iri("noesis:e/wrong-predicate"), Iri("crm:other"), Node.Ref(lia)),
      Triple(Iri("noesis:e/wrong-object"), property, Node.Ref(marco)),
      Triple(Iri("noesis:e/right"), property, Node.Ref(lia)),
      Triple(lia, property, Node.Ref(marco)),
      Triple(marco, property, Node.Ref(acme)),
      Triple(lia, dataProperty, Node.Lit(Literal.string("z"))),
      Triple(lia, dataProperty, Node.Lit(Literal.string("a"))),
      Triple(marco, dataProperty, Node.Lit(Literal.string("noise"))),
      Triple(lia, RelationshipsModule.hasName, Node.Ref(name)),
      Triple(name, RelationshipsModule.familyName, Node.Lit(Literal.string("García"))),
      Triple(name, RelationshipsModule.givenName, Node.Lit(Literal.string("Lía"))),
      Triple(name, RelationshipsModule.additionalName, Node.Lit(Literal.string("María"))),
      Triple(name, RelationshipsModule.honorificPrefix, Node.Lit(Literal.string("Dr."))),
      Triple(name, RelationshipsModule.honorificSuffix, Node.Lit(Literal.string("PhD")))
    )
    assertEquals(Prm.instances(triples, cls), List(lia))
    assertEquals(Prm.objectSubjects(triples, property, lia), List(Iri("noesis:e/right")))
    assertEquals(Prm.objectValues(triples, lia, property), List(marco))
    assertEquals(
      Prm.structuredName(triples, lia),
      Some(
        StructuredNameView(
          Some("García"),
          Some("Lía"),
          Some("María"),
          Some("Dr."),
          Some("PhD")
        )
      )
    )
    assertEquals(
      Prm.structuredName(
        Set(Triple(lia, RelationshipsModule.hasName, Node.Ref(name))),
        lia
      ),
      None
    )
    assertEquals(Prm.normalize("email", "LIA@EXAMPLE.TEST"), "LIA@example.test")
    assertEquals(Prm.normalize("phone", " +52 (55) "), "+5255")
    assertEquals(Prm.normalize("sms", " 55-12 "), "5512")
    assertEquals(Prm.normalize("custom", " value "), "value")
    assertEquals(
      Prm.nextOccurrence(java.time.MonthDay.of(5, 12), java.time.LocalDate.of(2026, 5, 11)),
      java.time.LocalDate.of(2026, 5, 12)
    )
    assertEquals(
      Prm.nextOccurrence(java.time.MonthDay.of(5, 12), java.time.LocalDate.of(2026, 5, 13)),
      java.time.LocalDate.of(2027, 5, 12)
    )
    // A year alone names no recurring day, so it never reaches `nextOccurrence` — the type says so
    // now, where the old signature returned `None` at runtime.
    assertEquals(
      Literal.date(PartialDate.Year(2026)).asAnniversary,
      None
    )
