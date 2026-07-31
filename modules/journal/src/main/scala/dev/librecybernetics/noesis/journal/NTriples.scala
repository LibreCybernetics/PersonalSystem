package dev.librecybernetics.noesis.journal

import dev.librecybernetics.noesis.logic.*

/** RDF 1.1 N-Triples: the reading half of the journal's serialization duties.
  *
  * N-Triples is line-based and prefix-free, which makes it the format that needs no parser state
  * and no namespace context — every term carries its own absolute IRI. That is exactly the shape
  * Noesis stores, so reading is a transcription rather than a translation.
  *
  * One deliberate gap: Noesis has no blank nodes. SPEC §3.1 reifies events and n-ary relations as
  * individuals with minted IRIs, so `_:b0` has nothing to map onto — and a stable name is what lets
  * annotations, learning items and justifications address a node at all. Blank nodes are rejected
  * with a distinct message rather than silently skipped.
  */
object NTriples:

  private val excludedFromIri = Set('<', '>', '"', '{', '}', '|', '^', '`', '\\')

  /** Parses one line. Returns `None` for blank lines and comments, which are not triples. */
  def parseLine(line: String): Either[String, Option[Triple]] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then Right(None)
    else parseTriple(trimmed).map(Some(_))

  def parse(document: String): Either[String, List[Triple]] =
    document.linesIterator.zipWithIndex.foldLeft[Either[String, List[Triple]]](Right(Nil)):
      (acc, entry) =>
        val (line, index) = entry
        for
          triples <- acc
          parsed <- parseLine(line).left.map(err => s"line ${index + 1}: $err")
        yield triples ++ parsed

  private def parseTriple(line: String): Either[String, Triple] =
    for
      (subject, afterSubject) <- iriRef(line, 0)
      (predicate, afterPredicate) <- iriRef(line, skipSpace(line, afterSubject))
      (obj, afterObject) <- node(line, skipSpace(line, afterPredicate))
      _ <- terminator(line, skipSpace(line, afterObject))
    yield Triple(subject, predicate, obj)

  private def skipSpace(line: String, from: Int): Int =
    line.indexWhere(!_.isWhitespace, from) match
      case -1    => line.length
      case index => index

  private def terminator(line: String, from: Int): Either[String, Unit] =
    if from < line.length && line.charAt(from) == '.' && skipSpace(line, from + 1) == line.length
    then Right(())
    else Left(s"expected a '.' terminator at offset $from")

  /** `<...>`, with `\uXXXX` / `\UXXXXXXXX` resolved and the characters RFC 3987 excludes rejected. */
  private def iriRef(line: String, from: Int): Either[String, (Iri, Int)] =
    if !line.isDefinedAt(from) then Left("expected an IRI, found end of line")
    else if line.charAt(from) == '_' then
      Left("blank nodes have no representation in Noesis: every node Noesis can express is named")
    else if line.charAt(from) != '<' then Left(s"expected '<' at offset $from")
    else
      line.indexOf('>', from) match
        case -1 => Left(s"unterminated IRI at offset $from")
        case close =>
          val raw = line.substring(from + 1, close)
          unescape(raw).flatMap: value =>
            if value.exists(c => c.isWhitespace || c.isControl || excludedFromIri.contains(c)) then
              Left(s"illegal character in IRI <$raw>")
            else Iri.parse(value).map(iri => (iri, close + 1))

  private def node(line: String, from: Int): Either[String, (Node, Int)] =
    if from < line.length && line.charAt(from) == '"' then
      literal(line, from).map((value, next) => (Node.Lit(value), next))
    else iriRef(line, from).map((iri, next) => (Node.Ref(iri), next))

  private def literal(line: String, from: Int): Either[String, (Literal, Int)] =
    closingQuote(line, from + 1).flatMap: close =>
      unescape(line.substring(from + 1, close)).flatMap: lexical =>
        val rest = close + 1
        if line.startsWith("^^", rest) then
          iriRef(line, rest + 2).map((datatype, next) => (Literal(lexical, datatype), next))
        else if line.startsWith("@", rest) then
          val end = line.indexWhere(c => !(c.isLetterOrDigit || c == '-'), rest + 1) match
            case -1    => line.length
            case index => index
          val tag = line.substring(rest + 1, end)
          if LanguageTag.isWellFormed(tag) then Right((Literal.tagged(lexical, tag), end))
          else Left(s"malformed language tag '@$tag'")
        // RDF 1.1 §3.3: a plain literal is xsd:string, which N-Triples writes without a datatype.
        else Right((Literal(lexical, Xsd.string), rest))

  private def closingQuote(line: String, from: Int): Either[String, Int] =
    if !line.isDefinedAt(from) then Left("unterminated literal")
    else
      line.charAt(from) match
        case '"'                            => Right(from)
        case '\\' if from + 1 < line.length => closingQuote(line, from + 2)
        case '\\'                           => Left("literal ends in a dangling escape")
        case c if c == '\n' || c == '\r'    => Left("a raw newline is not allowed inside a literal")
        case _                              => closingQuote(line, from + 1)

  /** Resolves ECHAR and UCHAR escapes (RDF 1.1 N-Triples §6). */
  private def unescape(raw: String): Either[String, String] =
    def go(index: Int, acc: StringBuilder): Either[String, String] =
      if !raw.isDefinedAt(index) then Right(acc.toString)
      else if raw.charAt(index) != '\\' then go(index + 1, acc.append(raw.charAt(index)))
      else if !raw.isDefinedAt(index + 1) then Left("dangling escape")
      else
        raw.charAt(index + 1) match
          case 't'   => go(index + 2, acc.append('\t'))
          case 'b'   => go(index + 2, acc.append('\b'))
          case 'n'   => go(index + 2, acc.append('\n'))
          case 'r'   => go(index + 2, acc.append('\r'))
          case 'f'   => go(index + 2, acc.append('\f'))
          case '"'   => go(index + 2, acc.append('"'))
          case '\''  => go(index + 2, acc.append('\''))
          case '\\'  => go(index + 2, acc.append('\\'))
          case 'u'   => codePoint(raw, index + 2, 4).flatMap((text, next) => go(next, acc.append(text)))
          case 'U'   => codePoint(raw, index + 2, 8).flatMap((text, next) => go(next, acc.append(text)))
          case other => Left(s"unknown escape '\\$other'")

    go(0, new StringBuilder)

  private def codePoint(raw: String, from: Int, digits: Int): Either[String, (String, Int)] =
    if from + digits > raw.length then Left("truncated unicode escape")
    else
      val hex = raw.substring(from, from + digits)
      if !hex.forall(c => c.isDigit || ('a' to 'f').contains(c.toLower)) then
        Left(s"'$hex' is not hexadecimal")
      else Right((Character.toChars(Integer.parseInt(hex, 16)).mkString, from + digits))

  /** Writes one triple. Every term is an absolute IRI in angle brackets — N-Triples has no
    * prefixes, which is what makes a line self-contained.
    */
  def render(triple: Triple): String =
    val obj = triple.obj match
      case Node.Ref(iri)     => s"<${iri.value}>"
      case Node.Lit(literal) => RdfTerms.literal(literal, datatype => s"<${datatype.value}>")
    s"<${triple.subject.value}> <${triple.property.value}> $obj ."

  /** Each line terminated rather than separated, so an empty document is empty rather than a lone
    * newline — the same trap `JsonLines.encode` avoids for the same reason.
    */
  def render(triples: List[Triple]): String = triples.map(triple => s"${render(triple)}\n").mkString
