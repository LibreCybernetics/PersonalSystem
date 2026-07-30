package noesis.conformance

import scala.annotation.tailrec
import scala.util.Try

/** An independently written checker for the restrictions RFC 7493 puts on a JSON text.
  *
  * The same reasoning as the Turtle suite: a conformance claim checked by the code that makes it is
  * worth little, so this walks the text itself rather than asking circe. It has to — the two
  * constraints that matter most here are invisible after parsing. A duplicate member name (§2.3) is
  * resolved by the parser before any value exists to inspect, and an escaped lone surrogate (§2.1)
  * is indistinguishable from a raw one once decoded.
  *
  * The input is assumed to be a conforming JSON text; syntax is `JsonSyntaxConformanceSuite`'s
  * question, not this one. Malformed input therefore yields a `Left` naming the position rather
  * than a clause.
  *
  * Two things RFC 7493 asks for are deliberately not checked here. §2.1's UTF-8 requirement is
  * about bytes, so it is tested against the writer's own encoding rather than against a `String`.
  * And §2.2's SHOULD on *precision* is not decidable from a lexeme — `0.1` expresses more precision
  * than a double holds, and forbidding it would fail every ordinary decimal — so only the two
  * decidable parts are enforced: the exact-integer bound and the double magnitude range.
  */
object Ijson:

  /** RFC 7493 §2.2: the largest integer an I-JSON receiver must treat as exact. */
  private val maxExactInteger = BigInt("9007199254740991")

  /** `Right` if `text` is an I-JSON message; `Left` names the clause it violates. */
  def check(text: String): Either[String, Unit] =
    value(text, 0).map(_ => ())

  private def value(text: String, from: Int): Either[String, Int] =
    val at = skipWhitespace(text, from)
    charAt(text, at) match
      case None                          => Left(s"unexpected end of text at $at")
      case Some('{')                     => member(text, at + 1, Set.empty)
      case Some('[')                     => element(text, at + 1)
      case Some('"')                     => string(text, at).flatMap((decoded, next) => codePoints(decoded).as(next))
      case Some(c) if c == '-' || c.isDigit => number(text, at)
      case Some(_)                       => literal(text, at)

  /** One object member and whatever follows it, carrying the names already bound in this object. */
  private def member(text: String, from: Int, seen: Set[String]): Either[String, Int] =
    val at = skipWhitespace(text, from)
    if charAt(text, at).contains('}') then Right(at + 1)
    else
      for
        nameAndNext <- string(text, at)
        (name, afterName) = nameAndNext
        _ <- codePoints(name)
        _ <- Either.cond(
          !seen.contains(name),
          (),
          s"""RFC 7493 §2.3: duplicate member name "$name""""
        )
        afterValue <- value(text, skipWhitespace(text, afterName) + 1)
        next = skipWhitespace(text, afterValue)
        end <- charAt(text, next) match
          case Some(',') => member(text, next + 1, seen + name)
          case Some('}') => Right(next + 1)
          case _         => Left(s"expected ',' or '}' at $next")
      yield end

  private def element(text: String, from: Int): Either[String, Int] =
    val at = skipWhitespace(text, from)
    if charAt(text, at).contains(']') then Right(at + 1)
    else
      for
        afterValue <- value(text, at)
        next = skipWhitespace(text, afterValue)
        end <- charAt(text, next) match
          case Some(',') => element(text, next + 1)
          case Some(']') => Right(next + 1)
          case _         => Left(s"expected ',' or ']' at $next")
      yield end

  /** Reads a string starting at its opening quote, returning what it denotes and where it ends.
    *
    * Escapes are resolved here because §2.1 and §2.3 are both about the *denoted* characters:
    * `"a"` and `"a"` are the same member name, and `"\ud800"` is a surrogate however it is
    * spelled.
    */
  private def string(text: String, at: Int): Either[String, (String, Int)] =
    // A `Vector[Char]` rather than a growing `String`: the accumulated characters are code units
    // being collected, not text being concatenated, and the build forbids `String + Char` for
    // exactly the reason that distinction matters.
    @tailrec
    def read(index: Int, acc: Vector[Char]): Either[String, (String, Int)] =
      charAt(text, index) match
        case None       => Left(s"unterminated string at $at")
        case Some('"')  => Right((acc.mkString, index + 1))
        case Some('\\') =>
          charAt(text, index + 1) match
            case Some('u') =>
              val hex = text.slice(index + 2, index + 6)
              Try(Integer.parseInt(hex, 16)).toOption match
                case Some(code) if hex.lengthIs == 4 => read(index + 6, acc :+ code.toChar)
                case _                               => Left(s"malformed \\u escape at $index")
            case Some(escape) => read(index + 2, acc :+ unescape(escape))
            case None         => Left(s"dangling escape at $index")
        case Some(c) => read(index + 1, acc :+ c)

    read(at + 1, Vector.empty)

  private def unescape(escape: Char): Char = escape match
    case 'b' => '\b'
    case 'f' => '\f'
    case 'n' => '\n'
    case 'r' => '\r'
    case 't' => '\t'
    case c   => c

  /** RFC 7493 §2.1: no surrogates and no noncharacters, in names or in values. */
  private def codePoints(decoded: String): Either[String, Unit] =
    decoded.codePoints.toArray
      .find(code => isSurrogate(code) || isNoncharacter(code))
      .fold(Right(())): code =>
        val kind = if isSurrogate(code) then "a surrogate" else "a noncharacter"
        Left(f"RFC 7493 §2.1: string contains U+$code%04X, which is $kind")

  private def isSurrogate(code: Int): Boolean = code >= 0xd800 && code <= 0xdfff

  private def isNoncharacter(code: Int): Boolean =
    (code & 0xfffe) == 0xfffe || (code >= 0xfdd0 && code <= 0xfdef)

  /** RFC 7493 §2.2, on the number's value rather than on how it is spelled. */
  private def number(text: String, at: Int): Either[String, Int] =
    val end = text.indexWhere(c => !isNumberCharacter(c), at) match
      case -1    => text.length
      case index => index
    val lexeme = text.substring(at, end)

    Try(BigDecimal(lexeme)).toOption.toRight(s"malformed number $lexeme at $at").flatMap: decimal =>
      if decimal.isWhole && decimal.toBigInt.abs > maxExactInteger then
        Left(s"RFC 7493 §2.2: $lexeme is an integer beyond the exactly representable range")
      else if !decimal.toDouble.isFinite then
        Left(s"RFC 7493 §2.2: $lexeme is beyond the magnitude an IEEE 754 double provides")
      else Right(end)

  private def isNumberCharacter(c: Char): Boolean =
    c.isDigit || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'

  private def literal(text: String, at: Int): Either[String, Int] =
    List("true", "false", "null")
      .find(word => text.startsWith(word, at))
      .toRight(s"unexpected character at $at")
      .map(word => at + word.length)

  private def skipWhitespace(text: String, from: Int): Int =
    text.indexWhere(c => !c.isWhitespace, from) match
      case -1    => text.length
      case index => index

  private def charAt(text: String, index: Int): Option[Char] =
    Option.when(index >= 0 && index < text.length)(text.charAt(index))

  extension [A](either: Either[String, A])
    /** Replaces a checked-and-discarded result, which `-Wvalue-discard` would otherwise reject. */
    private def as[B](value: B): Either[String, B] = either.map(_ => value)
