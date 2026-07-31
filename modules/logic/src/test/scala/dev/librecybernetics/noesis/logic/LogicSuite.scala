package dev.librecybernetics.noesis.logic

import java.time.{Instant, LocalDate, MonthDay}

import cats.Order
import cats.effect.IO
import cats.effect.std.{SecureRandom, UUIDGen}
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite

/** Compatibility tests for the persisted formal language. */
class LogicSuite extends CatsEffectSuite:
  given SecureRandom[IO] =
    SecureRandom.javaSecuritySecureRandom[IO].unsafeRunSync()(using cats.effect.unsafe.implicits.global)
  given UUIDGen[IO] = UUIDGen.fromSecureRandom[IO]

  private val alice = Iri("noesis:e/alice")
  private val marco = Iri("noesis:e/marco")
  private val person = Iri("crm:Person")
  private val knows = Iri("crm:knows")

  test("canonical axiom JSON and its content-derived id remain stable"):
    val axiom = Axiom.ClassAssertion(alice, person)
    // RFC 8785 orders members by key, so this is independent of the order the fields are declared
    // in — which is the whole reason the identifier can be promised stable across releases.
    val canonical =
      """{"cls":"https://noesis.librecybernetics.dev/ns/crm#Person",""" +
        """"individual":"https://noesis.librecybernetics.dev/e/alice","type":"ClassAssertion"}"""

    assertEquals(Canonical.noesis(axiom.asJson), canonical)
    assertEquals(axiom.id.value, "ax_f7408c653d845bfe5f98eaeb")

  test("every triple-shaped axiom round-trips through the ternary view"):
    val property = Iri("crm:knows")
    val axioms = List(
      Axiom.ClassAssertion(alice, person),
      Axiom.ObjectAssertion(alice, property, Iri("noesis:e/marco")),
      Axiom.DataAssertion(alice, Vocab.label, Literal.string("Alice")),
      Axiom.SubClassOf(person, Vocab.Agent),
      Axiom.SubPropertyOf(property, Iri("crm:relatedTo"))
    )

    axioms.foreach: axiom =>
      assertEquals(Triples.of(axiom).map(Triples.toAxiom), Some(axiom))

  test("non-ternary OWL constructs are not flattened into misleading triples"):
    assertEquals(Triples.of(Axiom.DisjointClasses(person, Vocab.Organization)), None)
    assertEquals(
      Triples.of(Axiom.PropertyChain(List(ChainStep(Iri("crm:parentOf"))), Iri("crm:ancestorOf"))),
      None
    )

  test("the explicit clear patch remains distinct from an absent patch after JSON round-trip"):
    val clear = AnnotationPatch(sensitivity = Patch.Clear)
    val leave = AnnotationPatch(sensitivity = Patch.Leave)

    assertEquals(decode[AnnotationPatch](clear.asJson.noSpaces), Right(clear))
    assertNotEquals(clear, leave)

  test("IRI structure distinguishes vocabulary terms, opaque entities, and unqualified names"):
    assertEquals(Iri("crm:Person").prefix, Some("crm"))
    assertEquals(Iri("urn:example:Person").prefix, None, "urn is not a bound prefix")
    assertEquals(Iri("noesis:e/alice").prefix, None)
    assertEquals(Iri("Person").prefix, None)
    assertEquals(Iri(":Person").prefix, None)
    assertEquals(Iri("urn:example:Person").local, "Person")
    assertEquals(Iri("Person").local, "Person")
    assert(Iri("noesis:e/alice").isOpaque)
    assert(!Iri("crm:Person").isOpaque)

  test("fresh semantic identifiers carry their type-specific opaque prefixes"):
    for
      iri <- Iri.fresh[IO]
      fluent <- FluentId.fresh[IO]
    yield
      assert(iri.value.startsWith(s"${Namespaces.base}e/"))
      assert(fluent.value.startsWith("fl_"))

  test("every axiom case exposes the exact signature, individuals, property, and rendering"):
    val property2 = Iri("crm:relatedTo")
    val org = Iri("core:Organization")
    val literal = Literal.tagged("Alice", "en")
    val cases = List(
      (Axiom.SubClassOf(person, Vocab.Agent), Set(person, Vocab.Agent), Set.empty[Iri], None,
        "Person SubClassOf: Agent", false),
      (Axiom.DisjointClasses(person, org), Set(person, org), Set.empty[Iri], None,
        "Person DisjointWith: Organization", false),
      (Axiom.SubPropertyOf(knows, property2), Set(knows, property2), Set.empty[Iri], None,
        "knows SubPropertyOf: relatedTo", false),
      (Axiom.InverseProperties(knows, property2), Set(knows, property2), Set.empty[Iri], None,
        "knows InverseOf: relatedTo", false),
      (Axiom.SymmetricProperty(knows), Set(knows), Set.empty[Iri], None,
        "knows Characteristics: Symmetric", false),
      (Axiom.TransitiveProperty(knows), Set(knows), Set.empty[Iri], None,
        "knows Characteristics: Transitive", false),
      (Axiom.IrreflexiveProperty(knows), Set(knows), Set.empty[Iri], None,
        "knows Characteristics: Irreflexive", false),
      (
        Axiom.PropertyChain(List(ChainStep(knows), ChainStep(property2, inverse = true)), knows),
        Set(knows, property2),
        Set.empty[Iri],
        None,
        "knows o relatedTo⁻ SubPropertyOf: knows",
        false
      ),
      (Axiom.PropertyDomain(knows, person), Set(knows, person), Set.empty[Iri], None,
        "knows Domain: Person", false),
      (Axiom.PropertyRange(knows, person), Set(knows, person), Set.empty[Iri], None,
        "knows Range: Person", false),
      (Axiom.TimeVarying(knows), Set(knows), Set.empty[Iri], None,
        "knows Characteristics: TimeVarying", false),
      (Axiom.ClassAssertion(alice, person), Set(alice, person), Set(alice), None,
        "e/alice Types: Person", true),
      (Axiom.ObjectAssertion(alice, knows, marco), Set(alice, knows, marco), Set(alice, marco),
        Some(knows), "e/alice Facts: knows e/marco", true),
      (Axiom.DataAssertion(alice, Vocab.label, literal), Set(alice, Vocab.label), Set(alice),
        Some(Vocab.label), """e/alice Facts: label "Alice@en"""", true),
      (Axiom.SameIndividual(alice, marco), Set(alice, marco), Set(alice, marco), None,
        "e/alice SameAs: e/marco", true),
      (Axiom.DifferentIndividuals(alice, marco), Set(alice, marco), Set(alice, marco), None,
        "e/alice DifferentFrom: e/marco", true)
    )

    cases.foreach: (axiom, signature, individuals, assertedProperty, manchester, assertional) =>
      assertEquals(axiom.signature, signature)
      assertEquals(axiom.individuals, individuals)
      assertEquals(axiom.assertedProperty, assertedProperty)
      assertEquals(axiom.manchester, manchester)
      assertEquals(axiom.isAssertional, assertional)

  test("fluent assertions, temporal boundaries, matching, and descriptions preserve every distinction"):
    val from = PartialDate.of(2026, 1, 1)
    val to = PartialDate.of(2026, 7, 1)
    val reference = Fluent(FluentId.unsafe("fl_ref"), alice, knows, Node.Ref(marco))
    val data = Fluent(FluentId.unsafe("fl_data"), alice, Vocab.label, Node.Lit(Literal.string("Alice")))
    val bounded = reference.copy(validFrom = Some(from), validTo = Some(to), endReason = Some(EndReason.Ended))
    val open = reference.copy(validFrom = Some(from))
    val unknownStartClosed = reference.copy(validTo = Some(to), endReason = Some(EndReason.Ended))
    val unplacedEnd = reference.copy(endReason = Some(EndReason.Superseded))

    assertEquals(reference.assertion, Axiom.ObjectAssertion(alice, knows, marco))
    assertEquals(data.assertion, Axiom.DataAssertion(alice, Vocab.label, Literal.string("Alice")))
    assertEquals(reference.triple, Triple(alice, knows, Node.Ref(marco)))
    assert(reference.isOngoing)
    assert(!bounded.isOngoing)
    assert(!unplacedEnd.isOngoing)
    assert(!open.heldOn(LocalDate.of(2025, 12, 31)))
    assert(open.heldOn(LocalDate.of(2026, 1, 1)))
    assert(bounded.heldOn(LocalDate.of(2026, 6, 30)))
    assert(!bounded.heldOn(LocalDate.of(2026, 7, 1)))
    assert(reference.heldOn(LocalDate.of(2026, 6, 1)))
    assert(!unplacedEnd.heldOn(LocalDate.of(2026, 6, 1)))
    assert(reference.matches(alice, knows, None))
    assert(reference.matches(alice, knows, Some(Node.Ref(marco))))
    assert(!reference.matches(marco, knows, None))
    assert(!reference.matches(alice, Vocab.label, None))
    assert(!reference.matches(alice, knows, Some(Node.Ref(alice))))
    assertEquals(bounded.describe, "noesis:e/alice crm:knows noesis:e/marco [2026-01-01 → 2026-07-01]")
    assertEquals(open.describe, "noesis:e/alice crm:knows noesis:e/marco [open since 2026-01-01]")
    assertEquals(unknownStartClosed.describe, "noesis:e/alice crm:knows noesis:e/marco [until 2026-07-01]")
    assertEquals(reference.describe, "noesis:e/alice crm:knows noesis:e/marco [ongoing, start unknown]")

  test("end reasons parse case-insensitively and reject unknown values precisely"):
    assertEquals(EndReason.parse("ended"), Right(EndReason.Ended))
    assertEquals(EndReason.parse("SUPERSEDED"), Right(EndReason.Superseded))
    assertEquals(EndReason.parse("wrong"), Left("unknown end reason: wrong"))

  test("a partial date is located, renders as an XSD date form, and round-trips"):
    val values = List(
      PartialDate.Day(2026, 5, 12) -> "2026-05-12",
      PartialDate.Month(2026, 5) -> "2026-05",
      PartialDate.Year(2026) -> "2026"
    )
    values.foreach: (date, rendered) =>
      assertEquals(date.render, rendered)
      assertEquals(PartialDate.parse(rendered), Right(date))
      // Every shape has an XSD datatype: this is what closed D1, and it is a property of the type
      // rather than of the mapping, so a fourth shape could not be added without one.
      assert(Datatypes.dates.contains(Datatypes.of(date)), s"$rendered has no XSD date datatype")
      assert(Datatypes.isValid(Datatypes.of(date), rendered), s"$rendered is not in its lexical space")

    assertEquals(Datatypes.of(PartialDate.Day(2026, 5, 12)), Xsd.date)
    assertEquals(Datatypes.of(PartialDate.Month(2026, 5)), Xsd.gYearMonth)
    assertEquals(Datatypes.of(PartialDate.Year(2026)), Xsd.gYear)

  test("partial-date bounds are total, and its order agrees with equality"):
    val complete = PartialDate.of(2026, 5, 12)
    assert(complete.isComplete)
    assert(!PartialDate.Month(2026, 5).isComplete)
    assert(!PartialDate.Year(2026).isComplete)
    assertEquals(complete.lowerBound, LocalDate.of(2026, 5, 12))
    assertEquals(complete.upperBound, LocalDate.of(2026, 5, 13))
    assertEquals(PartialDate.Month(2026, 5).lowerBound, LocalDate.of(2026, 5, 1))
    assertEquals(PartialDate.Month(2026, 5).upperBound, LocalDate.of(2026, 6, 1))
    assertEquals(PartialDate.Year(2026).lowerBound, LocalDate.of(2026, 1, 1))
    assertEquals(PartialDate.Year(2026).upperBound, LocalDate.of(2027, 1, 1))
    assertEquals(PartialDate.from(LocalDate.of(2026, 5, 12)), complete)

    assert(Order[PartialDate].compare(complete, PartialDate.of(2027, 1, 1)) < 0)
    assert(Order[PartialDate].compare(PartialDate.of(2027, 1, 1), complete) > 0)
    assertEquals(Order[PartialDate].compare(complete, PartialDate.of(2026, 5, 12)), 0)
    // Three values share the day 2026-01-01 as a lower bound. The old order called them equal,
    // which made `Eq` derived from it claim two different dates were one; the precise one sorts
    // first now, and only equal values compare equal.
    val year = PartialDate.Year(2026)
    val month = PartialDate.Month(2026, 1)
    val day = PartialDate.of(2026, 1, 1)
    assert(Order[PartialDate].compare(day, month) < 0)
    assert(Order[PartialDate].compare(month, year) < 0)
    assert(Order[PartialDate].compare(year, day) > 0)
    // Where the two bounds disagree, the start decides: 2026 begins before 1 June 2026 even though
    // it ends after it. Comparing by end alone would sort the year second.
    assert(Order[PartialDate].compare(year, PartialDate.of(2026, 6, 1)) < 0)
    assert(Order[PartialDate].compare(PartialDate.of(2026, 6, 1), year) > 0)
    List(day, month, year).foreach: date =>
      assertEquals(Order[PartialDate].compare(date, date), 0)

  test("a date carries no timezone, and says so rather than failing on a digit"):
    // XSD 1.1 admits a timezone on every date datatype and `Datatypes` accepts one, because that
    // clause is what conformance is claimed against. The value type does not, and the boundary
    // between the two is stated in the message instead of surfacing as "not a number: 12+02:00".
    assert(Datatypes.isValid(Xsd.date, "2026-05-12+02:00"))
    assert(Datatypes.isValid(Xsd.date, "2026-05-12Z"))
    assertEquals(
      PartialDate.parse("2026-05-12+02:00"),
      Left("a date carries no timezone in Noesis: 2026-05-12+02:00")
    )
    assertEquals(
      PartialDate.parse("2026-05-12Z"),
      Left("a date carries no timezone in Noesis: 2026-05-12Z")
    )
    assertEquals(Literal("2026-05-12+02:00", Xsd.date).asDate, None)

  test("partial dates reject what they cannot hold, including the shapes they used to invent"):
    assertEquals(PartialDate.parse("  "), Left("empty date"))
    assertEquals(PartialDate.parse("2026-x"), Left("not a number: x"))
    assertEquals(PartialDate.parse("2026-1-2-3"), Left("unparseable date: 2026-1-2-3"))
    // The four shapes the narrowing removed. Each was either meaningless as a calendar date or a
    // second spelling of "not recorded", which `Option` already says. The exact messages are
    // asserted because the owner reads them at the CLI, where the fix is to write a year.
    assertEquals(PartialDate.parse("2026---12"), Left("unparseable date: 2026---12"))
    assertEquals(
      PartialDate.parse("unknown"),
      Left("a date that is not known is an absent value, not 'unknown'")
    )
    assertEquals(PartialDate.parse("--05"), Left("unparseable date: --05"))
    assertEquals(PartialDate.parse("---12"), Left("unparseable date: ---12"))
    assertEquals(PartialDate.parse("-2026"), Left("unparseable date: -2026"))
    // `05-12` is a recurring day, not year 5 month 12. Requiring four digits is what keeps the
    // month-day form vCard, FOAF and the CLI all use from being silently mis-dated.
    assertEquals(PartialDate.parse("05-12"), Left("a date starts with a four-digit year: 05-12"))
    assertEquals(PartialDate.parse("26"), Left("a date starts with a four-digit year: 26"))
    assertEquals(PartialDate.parse("2026-05-12"), Right(PartialDate.of(2026, 5, 12)))

  test("a recurring day is a MonthDay, not a date, and keeps the literal a yearless date wrote"):
    val may12 = MonthDay.of(5, 12)
    assertEquals(Literal.anniversary(may12), Literal("--05-12", Xsd.gMonthDay))
    assertEquals(Literal.anniversary(5, 12), Literal.anniversary(may12))
    // The stored form is unchanged by the narrowing, so no birthday in an existing journal moves
    // and no `AxiomId` changes.
    assertEquals(Literal.anniversary(may12).lexical, "--05-12")
    assert(Datatypes.isValid(Xsd.gMonthDay, Literal.anniversary(may12).lexical))

    // What ties the two types together: a birthday captured with its year and one captured without
    // must drive the same occasion.
    assertEquals(Literal.anniversary(may12).asAnniversary, Some(may12))
    assertEquals(Literal.date(PartialDate.of(1990, 5, 12)).asAnniversary, Some(may12))
    assertEquals(Literal.date(PartialDate.Month(1990, 5)).asAnniversary, None)
    assertEquals(Literal.date(PartialDate.Year(1990)).asAnniversary, None)
    assertEquals(Literal.string("--05-12").asAnniversary, None)
    assertEquals(Literal("nonsense", Xsd.gMonthDay).asAnniversary, None)
    // What an import boundary asks once instead of at every call site.
    assertEquals(Literal.dateOrAnniversary("--05-12"), Some(Literal.anniversary(may12)))
    assertEquals(Literal.dateOrAnniversary("1990-05-12"), Some(Literal.date(PartialDate.of(1990, 5, 12))))
    assertEquals(Literal.dateOrAnniversary("1990-05"), Some(Literal.date(PartialDate.Month(1990, 5))))
    assertEquals(Literal.dateOrAnniversary("--02-31"), None)
    assertEquals(Literal.dateOrAnniversary("sometime"), None)

    assertEquals(PartialDate.of(1990, 5, 12).anniversary, Some(may12))
    assertEquals(PartialDate.Month(1990, 5).anniversary, None)
    assertEquals(PartialDate.Year(1990).anniversary, None)

  test("CLI literals retain types, language tags, text, and renderings"):
    val instant = Instant.parse("2026-07-30T12:00:00Z")
    val cases = List(
      "hello" -> Literal.string("hello"),
      "hello@en" -> Literal.tagged("hello", "en"),
      "hello@work@en" -> Literal.tagged("hello@work", "en"),
      "true" -> Literal.boolean(true),
      "false" -> Literal.boolean(false),
      "-12.50" -> Literal.decimal(BigDecimal("-12.50")),
      "2026-07-30" -> Literal.date(PartialDate.of(2026, 7, 30)),
      "2026-07" -> Literal.date(PartialDate.Month(2026, 7)),
      "--05-12" -> Literal.anniversary(5, 12),
      "05-12" -> Literal.anniversary(5, 12),
      // A month-day that does not exist is text, not a silently corrected date.
      "--02-31" -> Literal.string("--02-31"),
      "13-01" -> Literal.string("13-01"),
      // A bare numeral is an integer, not a decimal: they are different datatypes with different
      // canonical forms, and capture must not silently widen one into the other.
      "100" -> Literal.integer(BigInt(100)),
      "-7" -> Literal.integer(BigInt(-7)),
      "007" -> Literal.integer(BigInt(7))
    )
    cases.foreach: entry =>
      val (raw, expected) = entry
      assertEquals(Literal.parse(raw), expected)

    val literals = List(
      Literal.tagged("bonjour", "fr") -> "bonjour@fr",
      Literal.decimal(BigDecimal("1.25")) -> "1.25",
      Literal.boolean(true) -> "true",
      Literal.date(PartialDate.of(2026, 7, 30)) -> "2026-07-30",
      Literal.instant(instant) -> "2026-07-30T12:00:00Z"
    )
    literals.foreach: entry =>
      val (literal, rendered) = entry
      assertEquals(literal.render, rendered)
    assertEquals(Literal.tagged("bonjour", "fr").text, "bonjour")
    // XSD's canonical decimal requires a digit on each side of the point (§3.3.3.2), so the
    // canonical form of two is "2.0" — "2" is the canonical form of xsd:integer, a different type.
    assertEquals(Literal.decimal(BigDecimal(2)).text, "2.0")
    assertEquals(Literal.integer(BigInt(2)).text, "2")

  test("node and triple rendering retain reference and literal object distinctions"):
    val ref = Node.Ref(marco)
    val lit = Node.Lit(Literal.string("Marco"))
    assertEquals(ref.render, "noesis:e/marco")
    assertEquals(lit.render, "Marco")
    assertEquals(ref.asIri, Some(marco))
    assertEquals(lit.asIri, None)
    assertEquals(Triple(alice, knows, ref).render, "noesis:e/alice crm:knows noesis:e/marco")

  test("sensitivity combination and annotation helpers preserve boundary values"):
    assertEquals(Sensitivity.max(Sensitivity.Public, Sensitivity.Sensitive), Sensitivity.Sensitive)
    assertEquals(Sensitivity.max(Sensitivity.Sensitive, Sensitivity.Public), Sensitivity.Sensitive)
    assertEquals(Sensitivity.max(Sensitivity.Personal, Sensitivity.Personal), Sensitivity.Personal)
    assertEquals(Sensitivity.min(Sensitivity.Public, Sensitivity.Sensitive), Sensitivity.Public)
    assertEquals(Sensitivity.min(Sensitivity.Sensitive, Sensitivity.Public), Sensitivity.Public)
    assertEquals(Sensitivity.min(Sensitivity.Personal, Sensitivity.Personal), Sensitivity.Personal)
    assertEquals(Sensitivity.parse("PeRsOnAl"), Right(Sensitivity.Personal))
    assertEquals(Sensitivity.parse("secret"), Left("unknown sensitivity: secret"))

    val initial = AxiomAnnotations(
      sensitivity = Some(Sensitivity.Personal),
      knowledgeScope = Set(Iri("org:old"))
    )
    assertEquals(
      initial.withSensitivity(Sensitivity.Internal).knowledgeScope,
      Set(Iri("org:old"))
    )
    assertEquals(
      initial.withSensitivity(Sensitivity.Internal, Set(Iri("org:new"))).knowledgeScope,
      Set(Iri("org:new"))
    )
    assertEquals(initial.withUtility(-1).recallUtility, Some(0.0))
    assertEquals(initial.withUtility(0.4).recallUtility, Some(0.4))
    assertEquals(initial.withUtility(2).recallUtility, Some(1.0))

  test("patch values apply, encode, decode, and report malformed operations exactly"):
    assertEquals(Patch.Leave.applyTo(Some(1)), Some(1))
    assertEquals(Patch.Clear.applyTo(Some(1)), None)
    assertEquals(Patch.of(2).applyTo(Some(1)), Some(2))
    assert(Patch.Leave.isLeave)
    assert(!Patch.Clear.isLeave)
    assertEquals(Patch.fromOption(Some(2)), Patch.SetTo(2))
    assertEquals(Patch.fromOption(None), Patch.Clear)
    assertEquals((Patch.Leave: Patch[Int]).asJson.noSpaces, """{"op":"leave"}""")
    assertEquals((Patch.Clear: Patch[Int]).asJson.noSpaces, """{"op":"clear"}""")
    assertEquals(Patch.of(2).asJson.noSpaces, """{"op":"set","value":2}""")
    assertEquals(decode[Patch[Int]]("""{"op":"set","value":2}"""), Right(Patch.SetTo(2)))
    val malformed = decode[Patch[Int]]("""{"op":"replace","value":2}""")
    assert(malformed.left.exists(_.getMessage.contains("unknown patch op: replace")))

    val original = AxiomAnnotations(
      truthConfidence = Some(1.0),
      sensitivity = Some(Sensitivity.Personal),
      knowledgeScope = Set(Iri("org:old")),
      recallUtility = Some(0.5),
      provenance = Provenance.owner(Some("capture"))
    )
    val patch = AnnotationPatch(
      truthConfidence = Patch.Clear,
      sensitivity = Patch.of(Sensitivity.Internal),
      knowledgeScope = Patch.Clear,
      recallUtility = Patch.of(0.9)
    )
    assert(!patch.isEmpty)
    assert(AnnotationPatch().isEmpty)
    assert(!AnnotationPatch(truthConfidence = Patch.Clear).isEmpty)
    assert(!AnnotationPatch(sensitivity = Patch.Clear).isEmpty)
    assert(!AnnotationPatch(knowledgeScope = Patch.Clear).isEmpty)
    assert(!AnnotationPatch(recallUtility = Patch.Clear).isEmpty)
    assertEquals(
      patch.applyTo(original),
      AxiomAnnotations(
        truthConfidence = None,
        sensitivity = Some(Sensitivity.Internal),
        knowledgeScope = Set.empty,
        recallUtility = Some(0.9),
        provenance = original.provenance
      )
    )

  // ── RFC 8785 canonicalization ──────────────────────────────────────────────

  test("canonicalization sorts members by UTF-16 code unit, not by declaration order"):
    val json = Json.obj(
      "b" -> Json.fromString("second"),
      "a" -> Json.fromString("first"),
      "ä" -> Json.fromString("umlaut"),
      "A" -> Json.fromString("upper")
    )
    // 'A' (0x41) < 'a' (0x61) < 'b' (0x62) < 'ä' (0xE4): code-unit order, not locale order.
    assertEquals(
      Canonical.serialize(json),
      """{"A":"upper","a":"first","b":"second","ä":"umlaut"}"""
    )

  test("canonicalization escapes only what RFC 8785 requires, in its shortest form"):
    val cases = List(
      "\"" -> """"\""""",
      "\\" -> """"\\"""",
      "\b" -> """"\b"""",
      "\f" -> """"\f"""",
      "\n" -> """"\n"""",
      "\r" -> """"\r"""",
      "\t" -> """"\t"""",
      1.toChar.toString -> "\"\\u0001\"",
      " " -> """" """",
      "" -> """""""",
      // A solidus needs no escape, and non-ASCII stays literal rather than becoming \uXXXX.
      "a/b" -> """"a/b"""",
      "Алиса" -> """"Алиса""""
    )
    cases.foreach: (raw, expected) =>
      assertEquals(Canonical.serialize(Json.fromString(raw)), expected, s"escaping $raw")

  test("canonical numbers follow ECMAScript Number::toString"):
    val cases = List(
      0.0 -> "0",
      -0.0 -> "0",
      1.0 -> "1",
      -1.5 -> "-1.5",
      100.0 -> "100",
      0.5 -> "0.5",
      0.001 -> "0.001",
      // The edges of the fixed-notation window: 1e-7 and 1e21 fall outside it, their neighbours
      // inside. These four cases are what the exponent rules actually turn on.
      1e-6 -> "0.000001",
      1e-7 -> "1e-7",
      1e21 -> "1e+21",
      1e20 -> "100000000000000000000",
      1.5e22 -> "1.5e+22",
      // Past the edges, not just on them: the window is a bound, not an equality.
      1e-8 -> "1e-8",
      1e22 -> "1e+22"
    )
    cases.foreach: (value, expected) =>
      assertEquals(Canonical.serialize(Json.fromDoubleOrNull(value)), expected, s"formatting $value")

  test("canonicalization covers the remaining JSON forms and drops nulls deeply"):
    assertEquals(Canonical.serialize(Json.Null), "null")
    assertEquals(Canonical.serialize(Json.True), "true")
    assertEquals(Canonical.serialize(Json.False), "false")
    // Arrays keep their order; only object members are sorted.
    assertEquals(Canonical.serialize(Json.arr(Json.fromInt(2), Json.fromInt(1))), "[2,1]")
    val nested = Json.obj("keep" -> Json.obj("gone" -> Json.Null, "here" -> Json.fromInt(1)))
    assertEquals(Canonical.serialize(nested), """{"keep":{"gone":null,"here":1}}""")
    assertEquals(Canonical.noesis(nested), """{"keep":{"here":1}}""")

  test("a number too wide for a double keeps its own lexical form"):
    // JCS defines canonicalization only over IEEE-754 doubles. Noesis records this as a deviation
    // rather than rejecting the input; nothing it encodes can produce such a number.
    val huge = decode[Json]("1e400").fold(err => fail(err.getMessage), identity)
    assertEquals(Canonical.serialize(huge), "1e400")

  test("axiom identity does not depend on the order fields are declared in"):
    val axiom = Axiom.DataAssertion(alice, Vocab.label, Literal.tagged("Alice", "en"))
    val canonical = Canonical.noesis(axiom.asJson)
    assertEquals(
      canonical,
      """{"property":"http://www.w3.org/2000/01/rdf-schema#label",""" +
        """"subject":"https://noesis.librecybernetics.dev/e/alice","type":"DataAssertion",""" +
        """"value":{"datatype":"http://www.w3.org/1999/02/22-rdf-syntax-ns#langString",""" +
        """"language":"en","lexical":"Alice"}}"""
    )
    assertEquals(Canonical.bytes(axiom.asJson).length, canonical.getBytes("UTF-8").length)

  // ── IRIs and namespaces ────────────────────────────────────────────────────

  test("IRI syntax accepts both absolute and compact forms and rejects malformed ones"):
    assertEquals(Iri.parse("crm:worksAt"), Right(Iri("crm:worksAt")))
    assertEquals(Iri.parse("https://example.org/a#b"), Right(Iri("https://example.org/a#b")))
    assertEquals(Iri.parse("urn:uuid:0-0"), Right(Iri("urn:uuid:0-0")))
    assertEquals(Iri.parse(""), Left("empty IRI"))
    assertEquals(Iri.parse("nocolon"), Left("IRI has no scheme or prefix: nocolon"))
    assertEquals(Iri.parse(":leading"), Left("IRI has no scheme or prefix: :leading"))
    assertEquals(Iri.parse("crm:"), Left("IRI has no local part: crm:"))
    assertEquals(Iri.parse("1bad:x"), Left("IRI scheme must start with a letter: 1bad:x"))
    assertEquals(Iri.parse("b_d:x"), Left("illegal character in IRI scheme: b_d:x"))
    assertEquals(
      Iri.parse("crm:has space"),
      Left("whitespace or control character in IRI: crm:has space")
    )
    assertEquals(Iri.parse("crm:a<b"), Left("illegal character '<' in IRI: crm:a<b"))
    assertEquals(Iri.parse("crm:a\\b"), Left("illegal character '\\' in IRI: crm:a\\b"))

  test("a bound prefix expands at construction; an unbound one is left alone"):
    assertEquals(Iri("crm:worksAt").value, s"${Namespaces.base}ns/crm#worksAt")
    assertEquals(Iri("xsd:string").value, s"${Namespaces.xsd}string")
    assertEquals(Iri("noesis:e/abc").value, s"${Namespaces.base}e/abc")
    // Unbound schemes pass through untouched, so external identifiers survive intact.
    assertEquals(Iri("https://example.org/a").value, "https://example.org/a")
    assertEquals(Iri("urn:uuid:0-0").value, "urn:uuid:0-0")
    assertEquals(Iri("unbound:thing").value, "unbound:thing")
    assertEquals(Iri("nocolon").value, "nocolon")
    assertEquals(Iri("crm:").value, "crm:", "a prefix with no local part is not a compact name")
    assertEquals(Iri(":leading").value, ":leading")
    // `absolute` is the escape hatch that skips expansion entirely.
    assertEquals(Iri.absolute("crm:worksAt").value, "crm:worksAt")
    // Expanding twice is expanding once: the result is no longer a compact name.
    assertEquals(Iri(Iri("crm:worksAt").value), Iri("crm:worksAt"))

  test("namespaces split and abbreviate, preferring the longest match"):
    val ns = Namespaces.default
    assertEquals(ns.split(Iri("crm:worksAt")), Some(("crm", "worksAt")))
    assertEquals(ns.compact(Iri("xsd:string")), Some("xsd:string"))
    // `ns/crm#` is nested inside the `noesis:` base, so the longer binding has to win.
    assertEquals(ns.compact(Iri("crm:worksAt")), Some("crm:worksAt"))
    assertEquals(ns.compact(Iri("noesis:e/abc")), Some("noesis:e/abc"))
    assertEquals(ns.compact(Iri("https://elsewhere.example/x")), None)
    assertEquals(ns.compact(Iri.absolute(Namespaces.xsd)), None, "a bare namespace has no local part")
    assertEquals(ns.expandName("crm:worksAt"), Some(s"${Namespaces.base}ns/crm#worksAt"))
    assertEquals(ns.expandName("nocolon"), None)
    assertEquals(ns.expandName(":leading"), None)
    assertEquals(ns.expandName("crm:"), None)
    assertEquals(
      Namespaces(Map.empty).withPrefix("ex", "https://example.org/").expandName("ex:a"),
      Some("https://example.org/a")
    )
    assertEquals(
      Namespaces(Map.empty).withPrefix("ex", "https://example.org/").expand(Iri.absolute("ex:a")),
      Some(Iri.absolute("https://example.org/a"))
    )

  test("display accessors read through the namespace bindings"):
    assertEquals(Iri("crm:worksAt").prefix, Some("crm"))
    assertEquals(Iri("crm:worksAt").local, "worksAt")
    // `noesis:` is hidden from display: a minted entity has no readable prefix worth showing.
    assertEquals(Iri("noesis:e/abc").prefix, None)
    assertEquals(Iri("noesis:e/abc").local, "e/abc")
    assert(Iri("noesis:e/abc").isOpaque)
    assert(!Iri("crm:worksAt").isOpaque)
    // An IRI in no bound namespace still renders as its last segment rather than in full.
    assertEquals(Iri("https://elsewhere.example/path/Thing").local, "Thing")
    assertEquals(Iri("https://elsewhere.example/vocab#Thing").local, "Thing")
    assertEquals(Iri("urn:example:Thing").local, "Thing")
    assertEquals(Iri("https://elsewhere.example/x").prefix, None)

  // ── XSD datatypes ──────────────────────────────────────────────────────────

  test("lexical spaces admit exactly their datatype's forms"):
    assert(Datatypes.isValid(Xsd.boolean, "true"))
    assert(Datatypes.isValid(Xsd.boolean, "0"))
    assert(!Datatypes.isValid(Xsd.boolean, "yes"))
    assert(Datatypes.isValid(Xsd.integer, "-042"))
    assert(!Datatypes.isValid(Xsd.integer, "4.2"))
    assert(Datatypes.isValid(Xsd.decimal, ".5"))
    assert(!Datatypes.isValid(Xsd.decimal, "1e3"))
    assert(Datatypes.isValid(Xsd.date, "2026-05-12"))
    assert(Datatypes.isValid(Xsd.date, "2026-05-12Z"))
    assert(Datatypes.isValid(Xsd.date, "2026-05-12+14:00"))
    assert(!Datatypes.isValid(Xsd.date, "2026-05-12+15:00"))
    assert(!Datatypes.isValid(Xsd.date, "2026-13-12"))
    assert(!Datatypes.isValid(Xsd.date, "26-05-12"), "the date family requires four-digit years")
    assert(Datatypes.isValid(Xsd.gMonthDay, "--05-12"))
    assert(Datatypes.isValid(Xsd.gDay, "---12"))
    assert(Datatypes.isValid(Xsd.gMonth, "--05"))
    assert(Datatypes.isValid(Xsd.gYearMonth, "2026-05"))
    assert(Datatypes.isValid(Xsd.gYear, "2026"))
    assert(Datatypes.isValid(Xsd.dateTime, "2026-07-30T12:00:00Z"))
    assert(!Datatypes.isValid(Xsd.dateTime, "2026-07-30 12:00:00"))
    // Unconstrained and unknown datatypes admit anything, per RDF 1.1.
    assert(Datatypes.isValid(Xsd.string, "  anything"))
    assert(Datatypes.isValid(Iri("ex:invented"), "whatever"))
    // XSD 1.1 admits an optional timezone on every date datatype, and that is what conformance is
    // claimed against — the value types decline these separately, and say why.
    assert(Datatypes.isValid(Xsd.date, "2026-05-12+02:00"))
    assert(Datatypes.isValid(Xsd.gMonthDay, "--05-12-05:00"))
    assert(Datatypes.isValid(Xsd.gYear, "2026Z"))

  test("canonical mappings reduce to the form XSD prescribes"):
    assertEquals(Datatypes.canonical(Xsd.boolean, "1"), Right("true"))
    assertEquals(Datatypes.canonical(Xsd.boolean, "true"), Right("true"))
    assertEquals(Datatypes.canonical(Xsd.boolean, "0"), Right("false"))
    assertEquals(Datatypes.canonical(Xsd.boolean, "false"), Right("false"))
    assertEquals(Datatypes.canonical(Xsd.integer, "+007"), Right("7"))
    assertEquals(Datatypes.canonical(Xsd.integer, "-007"), Right("-7"))
    assertEquals(Datatypes.canonical(Xsd.integer, "-000"), Right("0"), "zero is unsigned")
    assertEquals(Datatypes.canonical(Xsd.decimal, "+1.250"), Right("1.25"))
    assertEquals(Datatypes.canonical(Xsd.decimal, "007"), Right("7.0"))
    assertEquals(Datatypes.canonical(Xsd.decimal, ".5"), Right("0.5"))
    assertEquals(Datatypes.canonical(Xsd.decimal, "-0.00"), Right("0.0"), "negative zero is zero")
    assertEquals(Datatypes.canonical(Xsd.decimal, "-1.50"), Right("-1.5"))
    assertEquals(
      Datatypes.canonical(Xsd.dateTime, "2026-07-30T12:00:00.000Z"),
      Right("2026-07-30T12:00:00Z")
    )
    assertEquals(
      Datatypes.canonical(Xsd.dateTime, "2026-07-30T12:00:00.500Z"),
      Right("2026-07-30T12:00:00.5Z")
    )
    assertEquals(
      Datatypes.canonical(Xsd.dateTime, "2026-07-30T12:00:00Z"),
      Right("2026-07-30T12:00:00Z")
    )
    assertEquals(Datatypes.canonical(Xsd.date, "2026-05-12"), Right("2026-05-12"))
    assertEquals(Datatypes.canonical(Xsd.string, "as-is"), Right("as-is"))
    // Fraction trimming belongs to xsd:dateTime alone — a string that looks numeric is untouched.
    assertEquals(Datatypes.canonical(Xsd.string, "1.50"), Right("1.50"))
    assertEquals(
      Datatypes.canonical(Xsd.integer, "4.2"),
      Left("'4.2' is not in the lexical space of xsd:integer")
    )

  test("a partial date carries the datatype its known components determine"):
    assertEquals(Datatypes.of(PartialDate.of(2026, 5, 12)), Xsd.date)
    assertEquals(Datatypes.of(PartialDate.Month(2026, 5)), Xsd.gYearMonth)
    assertEquals(Datatypes.of(PartialDate.Year(2026)), Xsd.gYear)
    assert(Datatypes.isDate(Xsd.date) && Datatypes.isDate(Xsd.gMonthDay))
    // `gMonth` and `gDay` remain dates the term layer knows, because an import may carry one —
    // there is simply no `PartialDate` for them to become.
    assert(Datatypes.isDate(Xsd.gMonth) && Datatypes.isDate(Xsd.gDay))
    assertEquals(Literal("--05", Xsd.gMonth).asDate, None)
    assertEquals(Literal("---12", Xsd.gDay).asDate, None)
    assert(!Datatypes.isDate(Xsd.string))
    // Years pad to four digits because no shorter form is in xsd:gYear's lexical space.
    assertEquals(PartialDate.Year(26).render, "0026")
    assert(Datatypes.isValid(Xsd.gYear, PartialDate.Year(26).render))

  // ── Typed literals ─────────────────────────────────────────────────────────

  test("literals carry a datatype, and a language tag only with rdf:langString"):
    assertEquals(Literal.string("hi"), Literal("hi", Xsd.string, None))
    assertEquals(Literal.tagged("hi", "en"), Literal("hi", Rdf.langString, Some("en")))
    assertEquals(Literal.boolean(false), Literal("false", Xsd.boolean, None))
    assertEquals(Literal.integer(BigInt(-7)), Literal("-7", Xsd.integer, None))
    assertEquals(Literal.decimal(BigDecimal("1.500")), Literal("1.5", Xsd.decimal, None))
    assertEquals(Literal.date(2026, 5, 12), Literal("2026-05-12", Xsd.date, None))
    assertEquals(
      Literal.instant(Instant.parse("2026-07-30T12:00:00Z")),
      Literal("2026-07-30T12:00:00Z", Xsd.dateTime, None)
    )

  test("typed accessors answer only for their own datatype"):
    assertEquals(Literal.decimal(BigDecimal("1.5")).asDecimal, Some(BigDecimal("1.5")))
    assertEquals(Literal.integer(BigInt(3)).asDecimal, Some(BigDecimal(3)))
    // A numeral typed as a string is not a number, however it reads.
    assertEquals(Literal.string("1.5").asDecimal, None)
    assertEquals(Literal("oops", Xsd.decimal).asDecimal, None)
    assertEquals(Literal.boolean(true).asBoolean, Some(true))
    assertEquals(Literal.boolean(false).asBoolean, Some(false))
    assertEquals(Literal("1", Xsd.boolean).asBoolean, Some(true))
    assertEquals(Literal("0", Xsd.boolean).asBoolean, Some(false))
    assertEquals(Literal("maybe", Xsd.boolean).asBoolean, None)
    assertEquals(Literal.string("true").asBoolean, None)
    assertEquals(Literal.date(2026, 5, 12).asDate, Some(PartialDate.of(2026, 5, 12)))
    assertEquals(Literal.string("2026-05-12").asDate, None)
    assertEquals(Literal("nonsense", Xsd.date).asDate, None)
    val instant = Instant.parse("2026-07-30T12:00:00Z")
    assertEquals(Literal.instant(instant).asInstant, Some(instant))
    assertEquals(Literal("nonsense", Xsd.dateTime).asInstant, None)
    assertEquals(Literal.string("2026-07-30T12:00:00Z").asInstant, None)

  test("a literal reports and repairs its own well-formedness"):
    assert(Literal.decimal(BigDecimal(1)).isWellFormed)
    assert(!Literal("4.2", Xsd.integer).isWellFormed)
    assertEquals(Literal("+1.250", Xsd.decimal).canonical, Right(Literal("1.25", Xsd.decimal)))
    assertEquals(
      Literal("4.2", Xsd.integer).canonical,
      Left("'4.2' is not in the lexical space of xsd:integer")
    )
    // Ordering separates the same lexical form under different datatypes and tags.
    assert(Order[Literal].compare(Literal.string("a"), Literal.string("b")) < 0)
    assert(Order[Literal].compare(Literal("1", Xsd.integer), Literal("1", Xsd.string)) < 0)
    assert(Order[Literal].compare(Literal.tagged("a", "en"), Literal.string("a")) < 0)
    assertEquals(Order[Literal].compare(Literal.string("a"), Literal.string("a")), 0)

  test("a literal decodes from its lexical form, datatype and optional language, and nothing else"):
    // The datatype IRI is the only authority on what the value is: there is no discriminator to
    // disagree with it, and a payload without a lexical form is not a literal.
    List(
      """{"datatype":"xsd:string","language":null}""" -> "a lexical form is required",
      """{"lexical":"x"}""" -> "a datatype is required",
      """{"type":"Str","value":"Alice"}""" -> "a discriminated sum is not a literal",
      """{"other":1}""" -> "an unrelated object is not a literal"
    ).foreach: (json, why) =>
      assert(decode[Literal](json).isLeft, s"$json decoded: $why")

    assertEquals(
      decode[Literal]("""{"lexical":"Alice","datatype":"rdf:langString","language":"en"}"""),
      Right(Literal.tagged("Alice", "en"))
    )
    val tagged = Literal.tagged("Alice", "en")
    assertEquals(decode[Literal](tagged.asJson.noSpaces), Right(tagged))
    assertEquals(decode[Literal](Literal.date(2026, 5, 12).asJson.noSpaces), Right(Literal.date(2026, 5, 12)))

  // ── OWL 2 EL profile ───────────────────────────────────────────────────────

  test("inverse and symmetric properties warn about leaving OWL 2 EL"):
    val parentOf = Iri("crm:parentOf")
    val childOf = Iri("crm:childOf")
    val worksAt = Iri("crm:worksAt")
    val colleagueOf = Iri("crm:colleagueOf")
    assertEquals(
      Profile.elWarning(Axiom.InverseProperties(parentOf, childOf)),
      Some("inverse properties (parentOf/childOf) are outside OWL 2 EL")
    )
    assertEquals(
      Profile.elWarning(Axiom.SymmetricProperty(knows)),
      Some("symmetric property knows is outside OWL 2 EL (it requires inverses)")
    )
    assertEquals(
      Profile.elWarning(
        Axiom.PropertyChain(
          List(ChainStep(worksAt), ChainStep(parentOf, inverse = true)),
          colleagueOf
        )
      ),
      Some("the chain defining colleagueOf uses an inverse step, which is outside OWL 2 EL")
    )
    assertEquals(
      Profile.elWarning(Axiom.DifferentIndividuals(alice, marco)),
      Some("asserting e/alice ≠ e/marco is outside OWL 2 EL")
    )

  test("EL-safe axioms produce no warning"):
    val parentOf = Iri("crm:parentOf")
    val ancestorOf = Iri("crm:ancestorOf")
    assert(Profile.isEl(Axiom.SubClassOf(person, Vocab.Agent)))
    assert(Profile.isEl(Axiom.ClassAssertion(alice, person)))
    assert(Profile.isEl(Axiom.PropertyDomain(Iri("crm:worksAt"), person)))
    assert(
      Profile.isEl(Axiom.PropertyChain(List(ChainStep(parentOf), ChainStep(parentOf)), ancestorOf))
    )
    assertEquals(
      Profile.warnings(
        List(Axiom.SubClassOf(person, Vocab.Agent), Axiom.DifferentIndividuals(alice, marco))
      ),
      List(
        Axiom.DifferentIndividuals(alice, marco) ->
          "asserting e/alice ≠ e/marco is outside OWL 2 EL"
      )
    )

  test("exponential notation is signed by its exponent, including a zero exponent"):
    // Canonicalization only ever reaches this with an exponent of 21 or more, or -7 or less, so
    // the zero case is pinned here rather than left as a branch no input can turn.
    assertEquals(Canonical.exponential("1", 0), "1e+0")
    assertEquals(Canonical.exponential("1", 21), "1e+21")
    assertEquals(Canonical.exponential("1", -7), "1e-7")
    assertEquals(Canonical.exponential("15", 22), "1.5e+22")
    assertEquals(Canonical.exponential("15", -8), "1.5e-8")

  test("a negative number in exponential range keeps its sign outside the mantissa"):
    // The sign has to be peeled off before the digits are decomposed: left in, it would be
    // mistaken for a significant digit and land inside the mantissa as "-.1e+22".
    assertEquals(Canonical.serialize(Json.fromDoubleOrNull(-1e21)), "-1e+21")
    assertEquals(Canonical.serialize(Json.fromDoubleOrNull(-1e-7)), "-1e-7")
    assertEquals(Canonical.serialize(Json.fromDoubleOrNull(-1e20)), "-100000000000000000000")

  // ── BCP 47 language tags ───────────────────────────────────────────────────

  test("BCP 47 well-formedness accepts each production of the langtag grammar"):
    val wellFormed = List(
      "en",
      "yue",
      "es-MX",
      "es-419",
      "zh-Hant-TW",
      "zh-cmn-Hans-CN",
      "sl-rozaj",
      "sl-rozaj-biske",
      "de-CH-1901",
      "en-US-u-islamcal",
      "en-a-bbb-x-a-ccc",
      "en-x-custom",
      "x-private",
      "qaaa",
      "abcdefgh"
    )
    wellFormed.foreach(tag => assert(LanguageTag.isWellFormed(tag), s"should be well-formed: $tag"))

  test("BCP 47 well-formedness rejects what the grammar excludes"):
    val malformed = List(
      "",
      "e",
      "abcdefghi",
      "en-",
      "-en",
      "en--US",
      "en_US",
      "e1",
      "en-a",
      "x-",
      "en-u",
      "en US",
      // Irregular grandfathered tags are a closed deprecated list, deliberately not encoded.
      "i-klingon"
    )
    malformed.foreach(tag => assert(!LanguageTag.isWellFormed(tag), s"should be malformed: $tag"))

  test("conventional casing lowercases the language, title-cases scripts, upper-cases regions"):
    assertEquals(LanguageTag.canonical("EN"), "en")
    assertEquals(LanguageTag.canonical("es-mx"), "es-MX")
    assertEquals(LanguageTag.canonical("ES-MX"), "es-MX")
    assertEquals(LanguageTag.canonical("zh-hant-tw"), "zh-Hant-TW")
    assertEquals(LanguageTag.canonical("ZH-HANT-TW"), "zh-Hant-TW")
    // A numeric region is not a two-letter subtag, and a variant is neither.
    assertEquals(LanguageTag.canonical("ES-419"), "es-419")
    assertEquals(LanguageTag.canonical("DE-ch-1901"), "de-CH-1901")
    assertEquals(LanguageTag.canonical("SL-ROZAJ"), "sl-rozaj")
    // "1901" is four characters but not four letters, so it is a variant and keeps its form
    // rather than being title-cased into a script subtag.
    assertEquals(LanguageTag.canonical("DE-CH-1901"), "de-CH-1901")
    // Script and region casing keys off subtags that are *entirely* letters. An extension subtag
    // that merely contains letters is neither, and is lowercased like anything else.
    assertEquals(LanguageTag.canonical("en-u-A1B2"), "en-u-a1b2")
    assertEquals(LanguageTag.canonical("en-u-A1"), "en-u-a1")
    assertEquals(LanguageTag.canonical("en"), "en")
    assertEquals(LanguageTag.canonical(""), "")
    // Casing never changes whether a tag is well-formed.
    List("EN", "es-mx", "ZH-HANT-TW").foreach: tag =>
      assertEquals(LanguageTag.isWellFormed(tag), LanguageTag.isWellFormed(LanguageTag.canonical(tag)))

  test("a language-tagged literal is well-formed only when its tag is"):
    assert(Literal.tagged("Alice", "en").isWellFormed)
    assert(Literal.tagged("Alice", "zh-Hant-TW").isWellFormed)
    assert(!Literal.tagged("Alice", "en_US").isWellFormed)
    assert(!Literal.tagged("Alice", "i-klingon").isWellFormed)
    // A datatype violation and a tag violation are both violations.
    assert(!Literal("4.2", Xsd.integer).isWellFormed)

  test("a compact name splits at the first colon, however many follow"):
    // `crm:a:b` abbreviates `…#a:b`; splitting at the last colon would look for a prefix `crm:a`.
    assertEquals(Namespaces.default.expandName("crm:a:b"), Some(s"${Namespaces.base}ns/crm#a:b"))
    assertEquals(Iri("crm:a:b").value, s"${Namespaces.base}ns/crm#a:b")

  test("an unbound IRI's local part is its last segment, by whichever separator comes last"):
    assertEquals(Iri("https://elsewhere.example/a#b#c").local, "c")
    assertEquals(Iri("https://elsewhere.example/a#b/c").local, "c")
    assertEquals(Iri("https://elsewhere.example/a/b#c").local, "c")
    assertEquals(Iri("urn:a:b:c").local, "c")
