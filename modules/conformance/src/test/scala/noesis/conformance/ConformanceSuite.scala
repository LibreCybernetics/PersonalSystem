package noesis.conformance

import scala.io.Source
import scala.util.Using

import io.circe.syntax.*
import munit.FunSuite
import noesis.journal.{NTriples, Turtle}
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
