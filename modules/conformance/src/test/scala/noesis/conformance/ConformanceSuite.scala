package noesis.conformance

import java.nio.charset.StandardCharsets.UTF_8

import scala.io.Source
import scala.util.Using

import io.circe.syntax.*
import munit.FunSuite
import noesis.journal.{JournalEntry, JsonLines, NTriples, Operation, Turtle}
import noesis.logic.*

/** Conformance to the normative references, driven by corpora rather than by hand-written cases.
  *
  * These suites answer a different question from the module suites: not "does the implementation do
  * what we intended" but "does what we intended match the specification". A failure here is not
  * automatically a bug — it may be a deviation — but it must be a *recorded* one. Anything that does
  * not pass belongs in `DEVIATIONS.md` with the clause it departs from and why, never in a skip.
  *
  * Vectors are derived from the clauses cited in each corpus's provenance block. Vendoring the
  * upstream corpora themselves (the JCS test-data repository, W3C `rdf-tests`) is recorded as
  * follow-up work; the loaders and the reader here are shaped so those drop in beside these.
  */
abstract class ConformanceSuite extends FunSuite:
  /** Names a failure by the clause it violates, so the message points at the specification. */
  protected def cite(citation: Citation, id: String): String = citation.cite(id)

final class JcsConformanceSuite extends ConformanceSuite:
  private val manifest = Manifest.load[JcsCase]("jcs/canonicalization.json")

  test(s"RFC 8785 canonicalization (${manifest.cases.size} vectors)"):
    manifest.cases.foreach: vector =>
      assertEquals(
        Canonical.serialize(vector.input),
        vector.expected,
        cite(manifest.citation, vector.id)
      )

  test("canonicalization is idempotent: re-parsing a canonical form reproduces it"):
    manifest.cases.foreach: vector =>
      val reparsed = io.circe.parser
        .parse(Canonical.serialize(vector.input))
        .fold(err => fail(s"${vector.id}: ${err.getMessage}"), identity)
      assertEquals(Canonical.serialize(reparsed), vector.expected, cite(manifest.citation, vector.id))

/** The grammar JCS canonicalizes *into*, and the one the journal reader has to reject outside of.
  *
  * ISO/IEC 21778 and RFC 8259 are intended to define the same syntactic language, so one corpus
  * answers for both; the ISO text is cited because it is the one that is freely retrievable.
  * Vectors run against `io.circe.parser`, which is the reader `JsonLines` replays a journal with —
  * testing anything else would prove nothing about the journal.
  */
final class JsonSyntaxConformanceSuite extends ConformanceSuite:
  private val manifest = Manifest.load[JsonSyntaxCase]("json/syntax.json")

  test(s"ISO/IEC 21778 §2: the reader accepts exactly the conforming texts (${manifest.cases.size} vectors)"):
    manifest.cases.foreach: vector =>
      assertEquals(
        io.circe.parser.parse(vector.text).isRight,
        vector.conforming,
        cite(manifest.citation, vector.id)
      )

  test("canonicalization preserves the value every conforming text denotes"):
    // JCS is a re-serialization of the same JSON value: RFC 8785 §3.2 fixes member order, number
    // formatting and escaping, and nothing else. A canonical form that denoted something else would
    // break replay, since `AxiomId` is a digest of it.
    manifest.cases.filter(vector => vector.conforming && vector.id != "number-negative-zero").foreach: vector =>
      val parsed = io.circe.parser.parse(vector.text).fold(err => fail(s"${vector.id}: $err"), identity)
      assertEquals(
        io.circe.parser.parse(Canonical.serialize(parsed)),
        Right(parsed),
        cite(manifest.citation, vector.id)
      )

    // Negative zero is the exception, and it is the cited specification's decision rather than a
    // Noesis one: RFC 8785 §3.2.2.3 defers number formatting to ECMAScript `Number::toString`,
    // which renders -0 as "0". Pinned here rather than dropped from the corpus, and out of
    // `DEVIATIONS.md` because erasing it *is* conformance. No Noesis encoder can produce one.
    assertEquals(io.circe.parser.parse("-0").map(Canonical.serialize), Right("0"))

/** The restriction the journal actually persists under (`journal` SPEC §7).
  *
  * The claim being tested is one-directional and stated that way in the module spec: every line
  * Noesis *writes* is an I-JSON message. Reading is not restricted to I-JSON (D9), and the write
  * side holds for a forbidden string only by substituting for it (D10); the last two tests pin
  * both, because a deviation that no test demonstrates is a claim rather than a report.
  */
final class IjsonConformanceSuite extends ConformanceSuite:
  private val manifest = Manifest.load[IjsonCase]("json/ijson.json")

  private def clause(vector: IjsonCase): String =
    s"${manifest.citation.specification} §${vector.clause} [${vector.id}] — ${manifest.citation.source}"

  test(s"RFC 7493 §2.1–§2.3 (${manifest.cases.size} vectors)"):
    manifest.cases.foreach: vector =>
      assertEquals(
        Ijson.check(vector.text).isRight,
        vector.conforming,
        s"${clause(vector)}: ${Ijson.check(vector.text)}"
      )

  test("every I-JSON vector is a conforming JSON text"):
    // I-JSON is a restriction of JSON, so a vector that is not JSON at all would be testing the
    // wrong thing — it would fail the checker for a reason the corpus does not name.
    manifest.cases.foreach: vector =>
      assert(io.circe.parser.parse(vector.text).isRight, clause(vector))

  test("every line the journal writes is an I-JSON message"):
    val written = new String(JsonLines.encode(IjsonConformanceSuite.entries).toArray, UTF_8)
    val lines = written.linesIterator.toList
    assertEquals(lines.size, IjsonConformanceSuite.entries.size, "one line per entry")
    lines.foreach(line => assertEquals(Ijson.check(line), Right(()), line))

  test("a written line is valid UTF-8, as RFC 7493 §2.1 requires"):
    // `Canonical` escapes only what RFC 8785 §3.2.2.2 requires, so anything above U+001F is written
    // as itself and the encoding step is what has to be right. Re-encoding a decoded line reproduces
    // the bytes exactly when — and only when — those bytes were valid UTF-8 to begin with.
    val bytes = JsonLines.encode(IjsonConformanceSuite.entries).toArray
    assertEquals(new String(bytes, UTF_8).getBytes(UTF_8).toList, bytes.toList)

  test("D9: the reader accepts a line that is not an I-JSON message"):
    // Pinned rather than skipped, per `DEVIATIONS.md`: replaying a hand-edited journal line with
    // duplicate names silently keeps the last one instead of refusing to start.
    val duplicated = """{"seq":1,"seq":2}"""
    assert(Ijson.check(duplicated).isLeft, "the corpus's own rule should reject this")
    assertEquals(
      io.circe.parser.parse(duplicated).map(_.asObject.flatMap(_("seq")).flatMap(_.asNumber).map(_.toString)),
      Right(Some("2"))
    )

  test("D10: an unpaired surrogate in a literal is written as '?' rather than refused"):
    // Why the write-side claim above holds even for a string RFC 7493 §2.1 forbids: `Canonical`
    // escapes nothing above U+001F, so the surrogate reaches `String.getBytes`, which substitutes.
    // The line is I-JSON, and the literal it carries is no longer the one that was asserted.
    val lone = Literal.string("A\ud800B")
    val axiom = Axiom.DataAssertion(Iri("noesis:e/alice"), Vocab.label, lone)
    val line = new String(JsonLines.encode(List(axiom)).toArray, UTF_8)
    assertEquals(Ijson.check(line.trim), Right(()))
    assertEquals(io.circe.parser.decode[Axiom](line), Right(Axiom.DataAssertion(Iri("noesis:e/alice"), Vocab.label, Literal.string("A?B"))))

    // And the identifier goes with it: two literals that differ only in which surrogate they carry
    // hash to one `AxiomId`, so the journal cannot tell them apart at all.
    val other = Axiom.DataAssertion(Iri("noesis:e/alice"), Vocab.label, Literal.string("A\udc00B"))
    assertEquals(AxiomId.of(axiom), AxiomId.of(other))

object IjsonConformanceSuite:
  /** Journal entries chosen for what they put *into* a line: an astral character and a
    * language-tagged literal for §2.1, a sequence number and a confidence for §2.2, and nested
    * objects — annotations inside an operation inside an entry — for §2.3, which is per-object.
    */
  private val axiom: Axiom =
    Axiom.DataAssertion(Iri("noesis:e/alice"), Vocab.label, Literal.tagged("Alice 🙂 Ríos", "es-MX"))

  private val at = java.time.Instant.parse("2026-05-12T10:15:30Z")

  val entries: List[JournalEntry] = List(
    JournalEntry(
      1L,
      at,
      Operation.Assert(AxiomId.of(axiom), axiom, AxiomAnnotations.ownerConfirmed.withUtility(0.75))
    ),
    JournalEntry(2L, at, Operation.Reclassify(AxiomId.of(axiom), Sensitivity.Sensitive)),
    JournalEntry(3L, at, Operation.Retract(AxiomId.of(axiom), Some("mis-captured — “smart quotes”"))),
    JournalEntry(
      4L,
      at,
      Operation.OpenFluent(
        Fluent(
          FluentId.unsafe("fl_0e6e6f00-0000-4000-8000-000000000001"),
          Iri("noesis:e/alice"),
          Iri("crm:worksAt"),
          Node.Ref(Iri("noesis:e/acme"))
        )
      )
    )
  )

/** ISO/IEC 11179-5 conformance for the namespaces Noesis names things in.
  *
  * §2.2.2 states conformance for a *system* rather than for a name: a namespace conforms when every
  * item in it is named in accordance with a naming convention whose scope, authority, semantic,
  * syntactic, lexical and uniqueness rules are documented. Both halves are checked here — the corpus
  * is the documentation, and the vocabulary modules are the items — because either alone is the
  * failure the clause exists to prevent: rules nobody follows, or names nobody wrote down.
  */
final class NamingConformanceSuite extends ConformanceSuite:
  private val manifest = Manifest.load[NamingConvention]("mdr/naming.json")
  private val conventions = manifest.cases.map(convention => convention.prefix -> convention).toMap

  test(s"ISO/IEC 11179-5 §2.2.2: every bound namespace has a documented convention (${conventions.size})"):
    Namespaces.default.byPrefix.keys.foreach: prefix =>
      assert(conventions.contains(prefix), s"${cite(manifest.citation, prefix)}: no naming convention")

    conventions.keys.foreach: prefix =>
      assert(
        Namespaces.default.byPrefix.contains(prefix),
        s"${cite(manifest.citation, prefix)}: a convention for a namespace nothing binds"
      )

  test("ISO/IEC 11179-5 §9.2–§9.7: each convention documents all six rule kinds"):
    manifest.cases.foreach: convention =>
      convention.documented.foreach: (clause, rule) =>
        assert(
          rule.trim.nonEmpty,
          s"${manifest.citation.specification} §$clause [${convention.prefix}] — undocumented"
        )
      assert(
        Set("prescriptive", "descriptive").contains(convention.kind),
        // §7: the distinction decides whether a failing name is our bug or an import bug.
        s"${cite(manifest.citation, convention.prefix)}: ${convention.kind} is neither prescriptive nor descriptive"
      )

  test("ISO/IEC 11179-5 §2.2.2: every declared term is named in accordance with its convention"):
    // A corpus that checks nothing passes; `Manifest.load` guards the vectors the same way. The
    // subject here is derived from the modules, so the guard belongs on the subject.
    assert(
      Naming.declared.distinct.sizeIs >= 80,
      s"only ${Naming.declared.distinct.size} terms collected — the four shipped modules declare more"
    )

    Naming.declared.distinct.foreach: (iri, role) =>
      val (prefix, local) = Namespaces.default
        .split(iri)
        .getOrElse(fail(s"${iri.value} is in no bound namespace, so no convention governs it"))
      val convention = conventions.getOrElse(prefix, fail(s"no convention for $prefix"))
      val documented = Naming
        .pattern(convention, role)
        .getOrElse(fail(s"${cite(manifest.citation, prefix)}: $prefix documents no rule for a $role, but $local is one"))
      assert(
        local.matches(documented),
        s"${cite(manifest.citation, prefix)}: $prefix:$local does not match the $role rule $documented"
      )

  test("ISO/IEC 11179-5 §8.1.2: one item per name — no term is declared in two roles"):
    // Punning is what the "one item per name" indicator forbids, and OWL 2 DL forbids it between
    // classes and properties independently. A term declared as both is one name for two items.
    val byName = Naming.declared.distinct.groupBy((iri, _) => iri)
    byName.foreach: (iri, declarations) =>
      assertEquals(
        declarations.map((_, role) => role).distinct.size,
        1,
        s"${iri.value} is declared as ${declarations.map((_, role) => role).distinct.mkString(" and ")}"
      )

  test("ISO/IEC 11179-5 §9.2: no vocabulary term is named in the minting namespace"):
    // The `noesis:` scope rule says entity identifiers only. A vocabulary term there would tie the
    // ontology to identifiers that are deliberately meaningless.
    Naming.declared.foreach: (iri, role) =>
      val minted = Namespaces.default.split(iri).exists((prefix, _) => prefix == "noesis")
      assert(!minted || role == Role.Individual, s"${iri.value} is a $role in the entity namespace")

final class XsdConformanceSuite extends ConformanceSuite:
  private val manifest = Manifest.load[XsdCase]("xsd/datatypes.json")

  test(s"XSD 1.1 lexical spaces (${manifest.cases.size} vectors)"):
    manifest.cases.foreach: vector =>
      assertEquals(
        Datatypes.isValid(Iri(vector.datatype), vector.lexical),
        vector.valid,
        cite(manifest.citation, vector.id)
      )

  test("XSD 1.1 canonical mappings"):
    manifest.cases.foreach: vector =>
      val obtained = Datatypes.canonical(Iri(vector.datatype), vector.lexical)
      vector.canonical match
        case Some(expected) => assertEquals(obtained, Right(expected), cite(manifest.citation, vector.id))
        case None => assert(obtained.isLeft, cite(manifest.citation, vector.id))

  test("a canonical form is in its own lexical space and is its own canonical form"):
    manifest.cases.foreach: vector =>
      vector.canonical.foreach: expected =>
        val datatype = Iri(vector.datatype)
        assert(Datatypes.isValid(datatype, expected), cite(manifest.citation, vector.id))
        assertEquals(
          Datatypes.canonical(datatype, expected),
          Right(expected),
          cite(manifest.citation, vector.id)
        )

final class IriConformanceSuite extends ConformanceSuite:
  private val manifest = Manifest.load[IriCase]("iri/syntax.json")

  test(s"RFC 3987 syntax (${manifest.cases.size} vectors)"):
    manifest.cases.foreach: vector =>
      assertEquals(
        Iri.parse(vector.value).isRight,
        vector.valid,
        s"${cite(manifest.citation, vector.id)}: ${Iri.parse(vector.value)}"
      )

  test("every bound prefix expands at construction and abbreviates back to itself"):
    Namespaces.default.byPrefix.foreach: (prefix, namespace) =>
      val iri = Iri(s"$prefix:local")
      assertEquals(iri.value, s"${namespace}local", s"$prefix should expand at construction")
      assertEquals(Namespaces.default.compact(iri), Some(s"$prefix:local"), prefix)
      assert(Iri.parse(namespace).isRight, s"the $prefix namespace must itself be a legal IRI")

  test("every stored identifier is an absolute IRI"):
    // The point of expanding at construction: there is no surface left that can emit a compact
    // name by forgetting to expand, because no compact name survives construction.
    val stored = List(
      Iri("crm:worksAt"),
      Iri("xsd:string"),
      Iri("core:partialDate"),
      Xsd.date,
      Rdf.langString,
      Vocab.label,
      Vocab.rdfType
    )
    stored.foreach: iri =>
      assert(iri.value.startsWith("http"), s"${iri.value} is not absolute")
      assertEquals(Iri.parse(iri.value), Right(iri), iri.value)

final class LanguageTagConformanceSuite extends ConformanceSuite:
  private val manifest = Manifest.load[LanguageTagCase]("bcp47/tags.json")

  test(s"BCP 47 well-formedness (${manifest.cases.size} vectors)"):
    manifest.cases.foreach: vector =>
      assertEquals(
        LanguageTag.isWellFormed(vector.tag),
        vector.wellFormed,
        cite(manifest.citation, vector.id)
      )

  test("conventional casing, and casing never changes well-formedness"):
    manifest.cases.foreach: vector =>
      vector.canonical.foreach: expected =>
        assertEquals(LanguageTag.canonical(vector.tag), expected, cite(manifest.citation, vector.id))
        assert(LanguageTag.isWellFormed(expected), cite(manifest.citation, vector.id))

final class NTriplesConformanceSuite extends ConformanceSuite:
  private def resource(name: String): String =
    Using
      .Manager: use =>
        val stream = Option(getClass.getResourceAsStream(s"/$name"))
          .getOrElse(sys.error(s"conformance corpus not found: $name"))
        use(Source.fromInputStream(stream, "UTF-8")).mkString
      .fold(err => sys.error(err.getMessage), identity)

  private val positive = resource("ntriples/positive.nt")
  private val negative = resource("ntriples/negative.nt")

  test("well-formed N-Triples documents parse"):
    NTriples.parse(positive) match
      case Left(err)     => fail(s"positive corpus rejected: $err")
      case Right(triples) => assert(triples.sizeIs >= 15, s"only ${triples.size} triples parsed")

  test("parsed triples round-trip through rendering"):
    val triples = NTriples.parse(positive).fold(err => fail(err), identity)
    triples.foreach: triple =>
      assertEquals(
        NTriples.parse(NTriples.render(triple)),
        Right(List(triple)),
        s"round-tripping ${NTriples.render(triple)}"
      )

  test("absolute IRIs compact onto the vocabulary Noesis stores"):
    val triples = NTriples.parse(positive).fold(err => fail(err), identity)
    assert(
      triples.exists(t => t.property == Vocab.label && t.obj == Node.Lit(Literal.tagged("Alice", "en"))),
      "a language-tagged label should parse into a compact rdfs:label with rdf:langString"
    )
    assert(
      triples.exists(t => t.obj == Node.Lit(Literal("--05-12", Xsd.gMonthDay))),
      "a typed literal should keep its datatype, compacted to xsd:"
    )
    assert(
      triples.exists(t => t.obj == Node.Lit(Literal("Alice", Xsd.string))),
      "a plain literal is xsd:string per RDF 1.1"
    )

  test("every malformed line is rejected, and blank nodes are rejected by name"):
    val lines = negative.linesIterator.filter(line => line.trim.nonEmpty && !line.trim.startsWith("#"))
    lines.foreach: line =>
      assert(NTriples.parseLine(line).isLeft, s"should have been rejected: $line")

    assert(
      NTriples.parseLine("_:b <https://example.org/p> <https://example.org/o> .").left.exists(_.contains("blank nodes")),
      "blank nodes deserve their own message, not a generic syntax error"
    )

  test("comments and blank lines are not triples"):
    assertEquals(NTriples.parseLine("# just a comment"), Right(None))
    assertEquals(NTriples.parseLine("   "), Right(None))
    assertEquals(NTriples.parseLine(""), Right(None))

  test("a literal Noesis builds renders to N-Triples the reader accepts"):
    val built = List(
      Triple(Iri("noesis:e/x"), Vocab.label, Node.Lit(Literal.string("plain"))),
      Triple(Iri("noesis:e/x"), Vocab.label, Node.Lit(Literal.tagged("étiquette", "fr"))),
      Triple(Iri("noesis:e/x"), Iri("crm:birthday"), Node.Lit(Literal.date(2026, 5, 12))),
      Triple(Iri("noesis:e/x"), Iri("vf:quantity"), Node.Lit(Literal.integer(BigInt(3)))),
      Triple(Iri("noesis:e/x"), Iri("vf:value"), Node.Lit(Literal.decimal(BigDecimal("2.50")))),
      Triple(Iri("noesis:e/x"), Iri("core:flag"), Node.Lit(Literal.boolean(true))),
      Triple(Iri("noesis:e/x"), Vocab.label, Node.Lit(Literal.string("quote:\" slash:\\ tab:\t"))),
      Triple(Iri("noesis:e/x"), Vocab.rdfType, Node.Ref(Iri("crm:Person")))
    )
    built.foreach: triple =>
      assertEquals(NTriples.parse(NTriples.render(triple)), Right(List(triple)), NTriples.render(triple))

  test("the journal's canonical JSON survives a round trip through its own reader"):
    // Ties the two serializations together: whatever a literal is, both formats must agree on it.
    val literal = Literal.tagged("Алиса", "ru")
    val axiom = Axiom.DataAssertion(Iri("noesis:e/x"), Vocab.label, literal)
    val triple = Triples.of(axiom).getOrElse(fail("a data assertion is triple-shaped"))
    assertEquals(NTriples.parse(NTriples.render(triple)), Right(List(triple)))
    assertEquals(
      io.circe.parser.decode[Literal](Canonical.noesis(literal.asJson)),
      Right(literal)
    )

/** Turtle output is checked against an independently written transcription of the grammar rather
  * than against the writer's own idea of what is legal. The writer decides abbreviation by asking
  * whether every character is plain or escapable; the regexes below come from the productions in
  * Turtle §6.5. Two different formulations of the same rule catch what one cannot.
  */
final class TurtleConformanceSuite extends ConformanceSuite:
  // PN_LOCAL_ESC (§6.5). Written out rather than derived, so a change to the writer's set does not
  // silently change what this accepts.
  private val esc = """\\[-_~.!$&'()*+,;=/?#@%]"""
  private val first = s"""(?:[A-Za-z0-9_]|$esc)"""
  private val middle = s"""(?:[A-Za-z0-9_.-]|$esc)"""
  private val last = s"""(?:[A-Za-z0-9_-]|$esc)"""
  private val pnLocal = s"""$first(?:$middle*$last)?"""
  private val pnPrefix = """[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?"""
  private val pnameLn = s"""$pnPrefix:$pnLocal"""
  private val iriRef = """<[^<>"{}|^`\\\x00-\x20]*>"""
  private val directive = s"""@prefix $pnPrefix: $iriRef \\."""

  private val corpus =
    Using
      .Manager: use =>
        val stream = Option(getClass.getResourceAsStream("/ntriples/positive.nt"))
          .getOrElse(sys.error("the N-Triples corpus is missing"))
        use(Source.fromInputStream(stream, "UTF-8")).mkString
      .fold(err => sys.error(err.getMessage), identity)

  private val triples = NTriples.parse(corpus).fold(err => fail(err), identity)

  test("every prefix directive matches the Turtle grammar"):
    val lines = Turtle.write(triples).linesIterator.takeWhile(_.nonEmpty).toList
    assert(lines.nonEmpty, "a document using prefixed names must declare them")
    lines.foreach(line => assert(line.matches(directive), s"not a legal @prefix directive: $line"))

  test("every prefix used in a statement is declared, and every declaration is used"):
    val document = Turtle.write(triples)
    val declared = document.linesIterator
      .takeWhile(_.nonEmpty)
      .map(line => line.stripPrefix("@prefix ").takeWhile(_ != ':'))
      .toSet
    val used = document.linesIterator
      .dropWhile(_.nonEmpty)
      .flatMap(line => s"(?<![<\\w])$pnameLn".r.findAllMatchIn(line).map(_.matched.takeWhile(_ != ':')))
      .toSet
    assertEquals(used.diff(declared), Set.empty[String], "undeclared prefixes")
    assertEquals(declared.diff(used), Set.empty[String], "declared but unused prefixes")

  test("every abbreviated term denotes the IRI it stands for"):
    // The real conformance question: an abbreviation is only correct if resolving it under the
    // declared namespace gives back the identifier that was abbreviated.
    val terms = triples.flatMap(t => List(t.subject, t.property))
    terms.foreach: iri =>
      val written = Turtle.term(iri)
      if written.startsWith("<") then
        assertEquals(written, s"<${iri.value}>", "an unabbreviated term is its own absolute IRI")
        assert(written.matches(iriRef), s"not a legal IRIREF: $written")
      else
        assert(written.matches(pnameLn), s"not a legal PNAME_LN: $written")
        val (prefix, local) = (written.takeWhile(_ != ':'), written.dropWhile(_ != ':').drop(1))
        val resolved = Namespaces.default.byPrefix(prefix) + local.replaceAll(esc, "").pipe(_ => unescapeLocal(local))
        assertEquals(Iri.absolute(resolved), iri, s"$written does not denote ${iri.value}")

  test("a minted entity IRI abbreviates with an escaped solidus"):
    // The shape that has no unescaped spelling: PN_CHARS excludes '/', so `e/alice` is only a
    // legal local name once the solidus is escaped.
    assertEquals(Turtle.term(Iri("noesis:e/alice")), """noesis:e\/alice""")
    assert(Turtle.term(Iri("noesis:e/alice")).matches(pnameLn))

  test("a term whose local part cannot be spelled falls back to an absolute IRI"):
    // A space is neither plain nor escapable, so abbreviation is impossible and the writer must
    // not invent an escape for it.
    val awkward = Iri.absolute(s"${Namespaces.base}ns/crm#has space")
    assertEquals(Turtle.term(awkward), s"<${awkward.value}>")

  test("literal syntax carries a language tag or a datatype, never both"):
    val document = Turtle.write(triples)
    val literals = """"(?:[^"\\]|\\.)*"(?:@[A-Za-z0-9-]+|\^\^(?:<[^>]*>|""" + pnameLn + "))?"
    document.linesIterator
      .dropWhile(_.nonEmpty)
      .filter(_.contains('"'))
      .foreach: line =>
        val objectPart = line.dropWhile(_ != '"').stripSuffix(" .")
        assert(objectPart.matches(literals), s"not a legal literal: $objectPart")

  private def unescapeLocal(local: String): String =
    local.replaceAll("""\\(.)""", "$1")

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)
