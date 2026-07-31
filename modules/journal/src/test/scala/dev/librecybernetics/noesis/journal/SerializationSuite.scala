package dev.librecybernetics.noesis.journal

import munit.FunSuite
import dev.librecybernetics.noesis.logic.*

/** The journal's serialization duties: reading N-Triples and writing Turtle.
  *
  * Corpus-driven conformance to the RDF grammars lives in `modules/conformance`. These are the
  * unit-level claims that pin the behavior those corpora exercise.
  */
class SerializationSuite extends FunSuite:

  private val alice = Iri("noesis:e/alice")
  private val marco = Iri("noesis:e/marco")
  private val person = Iri("crm:Person")
  private val label = Vocab.label

  private def line(triple: Triple) = NTriples.render(triple)

  // ── N-Triples: reading ─────────────────────────────────────────────────────

  test("a triple of absolute IRIs parses into the identifiers Noesis stores"):
    assertEquals(
      NTriples.parseLine(s"<${alice.value}> <${Vocab.rdfType.value}> <${person.value}> ."),
      Right(Some(Triple(alice, Vocab.rdfType, Node.Ref(person))))
    )

  test("literal forms parse with the datatype each denotes"):
    def obj(text: String) =
      NTriples.parseLine(s"<${alice.value}> <${label.value}> $text .").map(_.map(_.obj))

    // RDF 1.1 §3.3: a literal with no datatype is xsd:string, not an untyped thing.
    assertEquals(obj("\"Alice\""), Right(Some(Node.Lit(Literal("Alice", Xsd.string)))))
    assertEquals(obj("\"Alice\"@en"), Right(Some(Node.Lit(Literal.tagged("Alice", "en")))))
    assertEquals(obj("\"Alice\"@zh-Hant-TW"), Right(Some(Node.Lit(Literal.tagged("Alice", "zh-Hant-TW")))))
    assertEquals(
      obj(s""""--05-12"^^<${Xsd.gMonthDay.value}>"""),
      Right(Some(Node.Lit(Literal("--05-12", Xsd.gMonthDay))))
    )
    assertEquals(obj("\"\""), Right(Some(Node.Lit(Literal("", Xsd.string)))))

  test("comments and blank lines are not triples"):
    assertEquals(NTriples.parseLine("# a comment"), Right(None))
    assertEquals(NTriples.parseLine("   # indented"), Right(None))
    assertEquals(NTriples.parseLine(""), Right(None))
    assertEquals(NTriples.parseLine("    "), Right(None))

  test("whitespace between terms is free"):
    val spaced = s"  <${alice.value}>\t<${label.value}>   \"Alice\"   .   "
    assertEquals(NTriples.parseLine(spaced).map(_.map(_.subject)), Right(Some(alice)))

  test("every ECHAR and both UCHAR forms are resolved"):
    def lexical(text: String) =
      NTriples.parseLine(s"""<${alice.value}> <${label.value}> "$text" .""").map:
        _.flatMap(_.obj match
          case Node.Lit(l) => Some(l.lexical)
          case _           => None)

    assertEquals(lexical("""a\tb"""), Right(Some("a\tb")))
    assertEquals(lexical("""a\bb"""), Right(Some("a\bb")))
    assertEquals(lexical("""a\nb"""), Right(Some("a\nb")))
    assertEquals(lexical("""a\rb"""), Right(Some("a\rb")))
    assertEquals(lexical("""a\fb"""), Right(Some("a\fb")))
    assertEquals(lexical("""a\"b"""), Right(Some("a\"b")))
    assertEquals(lexical("""a\'b"""), Right(Some("a'b")))
    assertEquals(lexical("""a\\b"""), Right(Some("a\\b")))
    assertEquals(lexical("""ö"""), Right(Some("ö")))
    assertEquals(lexical("""ö"""), Right(Some("ö")), "hex digits are case-insensitive")
    assertEquals(lexical("""\U0001F600"""), Right(Some("😀")))
    assertEquals(lexical("no escapes here"), Right(Some("no escapes here")))

  test("a malformed line is rejected rather than skipped, and says why"):
    def rejected(text: String, reason: String) =
      val result = NTriples.parseLine(text)
      assert(result.isLeft, s"should have been rejected: $text")
      assert(result.left.exists(_.contains(reason)), s"$text: expected '$reason', got $result")

    val s = s"<${alice.value}>"
    val p = s"<${label.value}>"
    rejected(s"$s $p \"x\"", "terminator")
    rejected(s"$s $p", "expected an IRI")
    rejected(s"$s $p \"x\" . <extra>", "terminator")
    rejected(s"${alice.value} $p \"x\" .", "expected '<'")
    rejected(s"<${alice.value} $p \"x\" .", "illegal character")
    rejected(s"""$s "not an iri" "x" .""", "expected '<'")
    rejected(s"$s $p \"unterminated .", "unterminated literal")
    rejected(s"""$s $p "bad \\q" .""", "unknown escape")
    rejected(s"""$s $p "trunc \\u12" .""", "truncated")
    rejected(s"""$s $p "nothex \\uZZZZ" .""", "not hexadecimal")
    rejected(s"""$s $p "x"@ .""", "language tag")
    rejected(s"""$s $p "x"@en_US .""", "terminator")
    rejected(s"""$s $p "x"^^notaniri .""", "expected '<'")
    rejected(s"""$s $p "x"^^<http://example.org/a b> .""", "illegal character")
    rejected(s"<$s> $p \"x\" .", "illegal character")
    rejected("<not an iri> " + p + " \"x\" .", "illegal character")

  test("blank nodes are rejected by name, in either position"):
    val p = s"<${label.value}>"
    assert(NTriples.parseLine(s"_:b $p \"x\" .").left.exists(_.contains("blank nodes")))
    assert(NTriples.parseLine(s"<${alice.value}> $p _:b .").left.exists(_.contains("blank nodes")))

  test("an unterminated IRI and a dangling escape are distinguishable failures"):
    val p = s"<${label.value}>"
    assert(NTriples.parseLine(s"<${alice.value} ").left.exists(_.contains("unterminated IRI")))
    assert(NTriples.parseLine(s"""<${alice.value}> $p "x\\""").left.exists(_.contains("dangling")))

  test("a document reports the line a failure is on"):
    val good = line(Triple(alice, label, Node.Lit(Literal.string("Alice"))))
    assertEquals(NTriples.parse(s"$good\n$good").map(_.size), Right(2))
    assertEquals(NTriples.parse("# only a comment\n\n"), Right(Nil))
    assert(NTriples.parse(s"$good\n<broken").left.exists(_.startsWith("line 2:")))
    assert(NTriples.parse("<broken").left.exists(_.startsWith("line 1:")))

  // ── N-Triples: writing ─────────────────────────────────────────────────────

  test("N-Triples writes absolute IRIs and round-trips every literal shape"):
    val triples = List(
      Triple(alice, Vocab.rdfType, Node.Ref(person)),
      Triple(alice, label, Node.Lit(Literal.string("Alice"))),
      Triple(alice, label, Node.Lit(Literal.tagged("Алиса", "ru"))),
      Triple(alice, Iri("crm:birthday"), Node.Lit(Literal.date(2026, 5, 12))),
      Triple(alice, Iri("vf:quantity"), Node.Lit(Literal.integer(BigInt(3)))),
      Triple(alice, label, Node.Lit(Literal.string("quote:\" slash:\\ tab:\t nl:\n cr:\r")))
    )
    triples.foreach: triple =>
      assertEquals(NTriples.parse(line(triple)), Right(List(triple)), line(triple))
      assert(line(triple).startsWith(s"<${triple.subject.value}> "), "subjects are absolute")

    assertEquals(NTriples.parse(NTriples.render(triples)), Right(triples))
    assertEquals(NTriples.render(Nil), "")

  test("a plain literal is written without its implicit datatype"):
    val plain = line(Triple(alice, label, Node.Lit(Literal.string("Alice"))))
    assert(plain.endsWith("\"Alice\" ."), plain)
    assert(!plain.contains("^^"), "xsd:string is implicit and must not be written")
    val tagged = line(Triple(alice, label, Node.Lit(Literal.tagged("Alice", "en"))))
    assert(tagged.endsWith("\"Alice\"@en ."), tagged)
    assert(!tagged.contains("^^"), "a language tag replaces the datatype, never joins it")

  // ── Turtle ─────────────────────────────────────────────────────────────────

  test("a term abbreviates when it can and falls back to an absolute IRI when it cannot"):
    assertEquals(Turtle.term(person), "crm:Person")
    assertEquals(Turtle.term(label), "rdfs:label")
    // '/' is not a PN_CHARS, but PN_LOCAL_ESC covers it.
    assertEquals(Turtle.term(alice), """noesis:e\/alice""")
    // A space is neither plain nor escapable, so no abbreviation is possible.
    val spaced = Iri.absolute(s"${Namespaces.base}ns/crm#has space")
    assertEquals(Turtle.term(spaced), s"<${spaced.value}>")
    // PN_LOCAL admits neither a leading '-' nor a trailing '.'.
    val leadingDash = Iri.absolute(s"${Namespaces.base}ns/crm#-dash")
    val trailingDot = Iri.absolute(s"${Namespaces.base}ns/crm#dot.")
    assertEquals(Turtle.term(leadingDash), s"<${leadingDash.value}>")
    assertEquals(Turtle.term(trailingDot), s"<${trailingDot.value}>")
    // A dash or dot inside the local part is fine.
    assertEquals(Turtle.term(Iri.absolute(s"${Namespaces.base}ns/crm#a-b.c")), """crm:a-b\.c""")
    // An IRI in no bound namespace has nothing to abbreviate against.
    assertEquals(Turtle.term(Iri("https://example.org/x")), "<https://example.org/x>")

  test("every escapable character is escaped rather than dropped or emitted bare"):
    val escapable = "~.!$&'()*+,;=/?#@%"
    escapable.foreach: c =>
      val iri = Iri.absolute(s"${Namespaces.base}ns/crm#a${c}b")
      assertEquals(Turtle.term(iri), s"crm:a\\${c}b", s"escaping '$c'")

  test("the prefix block is generated, listing exactly the prefixes used"):
    val document = Turtle.write(
      List(
        Triple(alice, Vocab.rdfType, Node.Ref(person)),
        Triple(alice, Iri("crm:birthday"), Node.Lit(Literal.date(2026, 5, 12)))
      )
    )
    val prefixes = document.linesIterator.takeWhile(_.nonEmpty).toList
    assertEquals(
      prefixes,
      List(
        s"@prefix crm: <${Namespaces.base}ns/crm#> .",
        s"@prefix noesis: <${Namespaces.base}> .",
        s"@prefix rdf: <${Namespaces.rdf}> .",
        s"@prefix xsd: <${Namespaces.xsd}> ."
      )
    )
    // A datatype used only in the object position still gets its prefix declared.
    assert(document.contains("^^xsd:date"), document)

  test("xsd:string needs no declaration because it is never written"):
    val document = Turtle.write(List(Triple(alice, label, Node.Lit(Literal.string("Alice")))))
    assert(!document.contains("@prefix xsd:"), document)
    assert(document.contains("\"Alice\" ."), document)

  test("a document using no abbreviable term declares no prefixes"):
    val external = Iri("https://example.org/s")
    val document = Turtle.write(List(Triple(external, Iri("https://example.org/p"), Node.Ref(external))))
    assertEquals(document.linesIterator.takeWhile(_.nonEmpty).toList, Nil)
    assert(document.contains("<https://example.org/s> <https://example.org/p> <https://example.org/s> ."))

  test("statements are ordered so that an export is reproducible"):
    val unordered = List(
      Triple(marco, label, Node.Lit(Literal.string("Marco"))),
      Triple(alice, label, Node.Lit(Literal.string("Alice"))),
      Triple(alice, Vocab.rdfType, Node.Ref(person))
    )
    val statements = Turtle.write(unordered).linesIterator.dropWhile(_.nonEmpty).drop(1).toList
    assertEquals(statements, Turtle.write(unordered.reverse).linesIterator.dropWhile(_.nonEmpty).drop(1).toList)
    assertEquals(statements.headOption.map(_.takeWhile(_ != ' ')), Some("""noesis:e\/alice"""))

  test("a custom namespace map is honoured"):
    val ns = Namespaces(Map("ex" -> "https://example.org/"))
    assertEquals(Turtle.term(Iri("https://example.org/Thing"), ns), "ex:Thing")
    val document = Turtle.write(List(Triple(Iri("https://example.org/s"), Iri("https://example.org/p"), Node.Ref(person))), ns)
    assert(document.contains("@prefix ex: <https://example.org/> ."), document)
    // `person` is outside the supplied bindings, so it is written in full.
    assert(document.contains(s"<${person.value}>"), document)

  // ── Shared term syntax ─────────────────────────────────────────────────────

  test("literal escaping covers every character the grammars require"):
    assertEquals(RdfTerms.escape("""a"b"""), """a\"b""")
    assertEquals(RdfTerms.escape("""a\b"""), """a\\b""")
    assertEquals(RdfTerms.escape("a\nb"), """a\nb""")
    assertEquals(RdfTerms.escape("a\rb"), """a\rb""")
    assertEquals(RdfTerms.escape("a\tb"), """a\tb""")
    assertEquals(RdfTerms.escape("plain"), "plain")
    // The reverse solidus has to be escaped first, or it would double what the others introduce.
    assertEquals(RdfTerms.escape("\\n"), """\\n""")

  test("literal syntax carries a language tag or a datatype, never both"):
    val syntax = (l: Literal) => RdfTerms.literal(l, d => s"<${d.value}>")
    assertEquals(syntax(Literal.string("x")), "\"x\"")
    assertEquals(syntax(Literal.tagged("x", "en")), "\"x\"@en")
    assertEquals(syntax(Literal("x", Xsd.integer)), s""""x"^^<${Xsd.integer.value}>""")
    // The datatype rendering is the caller's, which is what lets Turtle abbreviate it.
    assertEquals(RdfTerms.literal(Literal("x", Xsd.integer), _ => "xsd:integer"), "\"x\"^^xsd:integer")

  test("a raw newline inside a literal is rejected, not absorbed"):
    // Reachable only through `parseLine` directly: `parse` splits on lines first, so the reader
    // has to refuse the newline itself rather than rely on never seeing one.
    val p = s"<${label.value}>"
    assert(NTriples.parseLine(s"""<${alice.value}> $p "a${"\n"}b" .""").left.exists(_.contains("raw newline")))
    assert(NTriples.parseLine(s"""<${alice.value}> $p "a${"\r"}b" .""").left.exists(_.contains("raw newline")))

  test("an escape with nothing after it is a dangling escape, wherever it appears"):
    // Inside a literal, `closingQuote` consumes escape pairs, so a trailing backslash there can
    // only mean an escaped closing quote. An IRI has no such pairing, so this is where a lone
    // trailing backslash reaches `unescape`.
    val result = NTriples.parseLine(s"""<https://example.org/a\\> <${label.value}> "x" .""")
    assert(result.left.exists(_.contains("dangling escape")), s"got $result")

  test("a partially hexadecimal escape is rejected rather than parsed"):
    val p = s"<${label.value}>"
    def lexicalOf(text: String) = NTriples.parseLine(s"""<${alice.value}> $p "$text" .""")
    // Every digit invalid, and only some invalid: both must fail, and the second is what
    // distinguishes "all four are hex" from "at least one is".
    assert(lexicalOf("""\uZZZZ""").left.exists(_.contains("not hexadecimal")))
    assert(lexicalOf("""\u12ZZ""").left.exists(_.contains("not hexadecimal")))
    assert(lexicalOf("""\U0001F6ZZ""").left.exists(_.contains("not hexadecimal")))
    // Upper-case hex digits are valid.
    assertEquals(
      lexicalOf("""ö""").map(_.map(_.obj)),
      Right(Some(Node.Lit(Literal("ö", Xsd.string))))
    )

  test("plainness is decided at the exact character-class boundaries"):
    // Each of these is the first or last character of a range in `isPlain`. A boundary that is
    // off by one either escapes something legal or emits something illegal bare.
    val plain = "azAZ09_-"
    plain.foreach: c =>
      assertEquals(
        Turtle.term(Iri.absolute(s"${Namespaces.base}ns/crm#x${c}y")),
        s"crm:x${c}y",
        s"'$c' should need no escape"
      )
    // The characters immediately outside each range. '/' and '@' are escapable; the rest are not
    // spellable at all, so the term falls back to an absolute IRI.
    List('/' -> """crm:x\/y""", '@' -> """crm:x\@y""").foreach: (c, expected) =>
      assertEquals(Turtle.term(Iri.absolute(s"${Namespaces.base}ns/crm#x${c}y")), expected, s"'$c'")
    List('`', '{', '[', ':').foreach: c =>
      val iri = Iri.absolute(s"${Namespaces.base}ns/crm#x${c}y")
      assertEquals(Turtle.term(iri), s"<${iri.value}>", s"'$c' is not spellable in a local name")

  test("a Turtle document is newline-terminated"):
    val document = Turtle.write(List(Triple(alice, label, Node.Lit(Literal.string("Alice")))))
    assert(document.endsWith("\n"), s"document does not end in a newline: ${document.takeRight(20)}")
    assert(!document.startsWith("\n"), "a document with no prefixes still starts at its first line")
