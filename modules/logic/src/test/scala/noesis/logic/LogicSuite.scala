package noesis.logic

import java.time.{Instant, LocalDate}

import cats.Order
import cats.effect.IO
import cats.effect.std.{SecureRandom, UUIDGen}
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
    val canonical = """{"individual":"noesis:e/alice","cls":"crm:Person","type":"ClassAssertion"}"""

    assertEquals(axiom.asJson.dropNullValues.noSpaces, canonical)
    assertEquals(axiom.id.value, "ax_b1bfb8fa359d0b982772c65a")

  test("every triple-shaped axiom round-trips through the ternary view"):
    val property = Iri("crm:knows")
    val axioms = List(
      Axiom.ClassAssertion(alice, person),
      Axiom.ObjectAssertion(alice, property, Iri("noesis:e/marco")),
      Axiom.DataAssertion(alice, Vocab.label, Literal.Str("Alice")),
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
    assertEquals(Iri("urn:example:Person").prefix, Some("urn"))
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
      assert(iri.value.startsWith("noesis:e/"))
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

  test("partial dates render, parse, bound, compare, and match occasions without invented values"):
    val values = List(
      PartialDate(Some(2026), Some(5), Some(12)) -> "2026-05-12",
      PartialDate(Some(2026), Some(5), None) -> "2026-05",
      PartialDate(Some(2026), None, Some(12)) -> "2026---12",
      PartialDate(Some(2026), None, None) -> "2026",
      PartialDate(None, Some(5), Some(12)) -> "--05-12",
      PartialDate(None, Some(5), None) -> "--05",
      PartialDate(None, None, Some(12)) -> "---12",
      PartialDate(None, None, None) -> "unknown"
    )
    values.foreach: (date, rendered) =>
      assertEquals(date.render, rendered)
      assertEquals(PartialDate.parse(rendered), Right(date))

    val complete = PartialDate.of(2026, 5, 12)
    assert(complete.isComplete)
    assert(!PartialDate(Some(2026), Some(5), None).isComplete)
    assert(!PartialDate(None, Some(5), Some(12)).isComplete)
    assertEquals(complete.lowerBound, Some(LocalDate.of(2026, 5, 12)))
    assertEquals(complete.upperBound, Some(LocalDate.of(2026, 5, 13)))
    assertEquals(PartialDate(Some(2026), Some(5), None).upperBound, Some(LocalDate.of(2026, 6, 1)))
    assertEquals(PartialDate(Some(2026), None, None).upperBound, Some(LocalDate.of(2027, 1, 1)))
    assertEquals(PartialDate.monthDay(5, 12).lowerBound, None)
    assert(PartialDate.monthDay(5, 12).sameMonthDay(complete))
    assert(!PartialDate.monthDay(5, 11).sameMonthDay(complete))
    assert(!PartialDate(None, None, Some(12)).sameMonthDay(complete))
    assert(!PartialDate(None, None, Some(12)).sameMonthDay(PartialDate(None, None, Some(12))))
    assertEquals(PartialDate.parse("5-12"), Right(PartialDate.monthDay(5, 12)))
    assertEquals(PartialDate.parse("  "), Left("empty date"))
    assertEquals(PartialDate.parse("--x"), Left("not a number: x"))
    assertEquals(PartialDate.parse("--1-2-3"), Left("unparseable partial date: --1-2-3"))
    assertEquals(PartialDate.parse("2026-1-2-3"), Left("unparseable date: 2026-1-2-3"))
    assert(Order[PartialDate].compare(complete, PartialDate.of(2027, 1, 1)) < 0)
    assert(Order[PartialDate].compare(complete, PartialDate.monthDay(5, 12)) < 0)
    assert(Order[PartialDate].compare(PartialDate.monthDay(5, 12), complete) > 0)
    assertEquals(Order[PartialDate].compare(PartialDate.monthDay(5, 12), PartialDate.monthDay(6, 1)), 0)

  test("CLI literals retain types, language tags, text, and renderings"):
    val instant = Instant.parse("2026-07-30T12:00:00Z")
    val cases = List(
      "hello" -> Literal.Str("hello"),
      "hello@en" -> Literal.Str("hello", Some("en")),
      "hello@work@en" -> Literal.Str("hello@work", Some("en")),
      "true" -> Literal.Bool(true),
      "false" -> Literal.Bool(false),
      "-12.50" -> Literal.Num(BigDecimal("-12.50")),
      "2026-07-30" -> Literal.Date(PartialDate.of(2026, 7, 30)),
      "--05-12" -> Literal.Date(PartialDate.monthDay(5, 12)),
      "05-12" -> Literal.Date(PartialDate.monthDay(5, 12))
    )
    cases.foreach: entry =>
      val (raw, expected) = entry
      assertEquals(Literal.parse(raw), expected)

    val literals = List(
      Literal.Str("bonjour", Some("fr")) -> "bonjour@fr",
      Literal.Num(BigDecimal("1.25")) -> "1.25",
      Literal.Bool(true) -> "true",
      Literal.Date(PartialDate.of(2026, 7, 30)) -> "2026-07-30",
      Literal.Time(instant) -> "2026-07-30T12:00:00Z"
    )
    literals.foreach: entry =>
      val (literal, rendered) = entry
      assertEquals(literal.render, rendered)
    assertEquals(Literal.tagged("bonjour", "fr").text, "bonjour")
    assertEquals(Literal.Num(BigDecimal(2)).text, "2")

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
