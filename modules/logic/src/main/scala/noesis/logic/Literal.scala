package noesis.logic

import java.time.{Instant, LocalDate}

import scala.util.Try

import cats.Order
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Codec, Decoder, DecodingFailure, Encoder, HCursor, Json}

/** A date that may be partially specified, e.g. `--05-12` for "12 May, year unknown" (SPEC §3.1).
  *
  * Birthdays are the motivating case: many are known as month/day only, and the ontology must not
  * force a fake year.
  */
final case class PartialDate(year: Option[Int], month: Option[Int], day: Option[Int]):
  /** XSD-style rendering: `2026-01-01`, `2026-05`, `--05-12`, `2026`.
    *
    * Each shape but two is the lexical form of an XSD date datatype (§3.3.9–§3.3.15), which is why
    * years are padded to four digits: `xsd:gYear` admits no shorter form. The exceptions are a year
    * with a day but no month, and a wholly unknown date; both carry `core:partialDate` instead.
    */
  def render: String =
    def two(i: Int) = "%02d".format(i)
    def four(i: Int) = "%04d".format(i)
    (year, month, day) match
      case (Some(y), Some(m), Some(d)) => s"${four(y)}-${two(m)}-${two(d)}"
      case (Some(y), Some(m), None)    => s"${four(y)}-${two(m)}"
      case (Some(y), None, Some(d))    => s"${four(y)}---${two(d)}"
      case (Some(y), None, None)       => four(y)
      case (None, Some(m), Some(d))    => s"--${two(m)}-${two(d)}"
      case (None, Some(m), None)       => s"--${two(m)}"
      case (None, None, Some(d))       => s"---${two(d)}"
      case (None, None, None)          => "unknown"

  def isComplete: Boolean = year.isDefined && month.isDefined && day.isDefined

  /** The earliest instant this date could denote, when that is determinable.
    *
    * Yearless dates (`--05-12`) are recurring rather than located in time, so they have no bound —
    * which is why fluent boundary comparisons treat them as unknown rather than guessing.
    */
  def lowerBound: Option[LocalDate] =
    year.map(y => LocalDate.of(y, month.getOrElse(1), day.getOrElse(1)))

  /** The instant just after the last one this date could denote (exclusive). */
  def upperBound: Option[LocalDate] =
    year.map: y =>
      (month, day) match
        case (Some(m), Some(d)) => LocalDate.of(y, m, d).plusDays(1)
        case (Some(m), None)    => LocalDate.of(y, m, 1).plusMonths(1)
        case _                  => LocalDate.of(y, 1, 1).plusYears(1)

  /** Does this date fall on the same month and day as `other`, ignoring years? Drives occasions. */
  def sameMonthDay(other: PartialDate): Boolean =
    month.isDefined && month === other.month && day.isDefined && day === other.day

object PartialDate:
  def of(year: Int, month: Int, day: Int): PartialDate =
    PartialDate(Some(year), Some(month), Some(day))

  def from(date: LocalDate): PartialDate =
    of(date.getYear, date.getMonthValue, date.getDayOfMonth)

  def monthDay(month: Int, day: Int): PartialDate = PartialDate(None, Some(month), Some(day))

  /** Parses the renderings produced by [[PartialDate.render]]. */
  def parse(s: String): Either[String, PartialDate] =
    def int(x: String) = x.toIntOption.toRight(s"not a number: $x")
    s.trim match
      case ""                       => Left("empty date")
      case "unknown"                => Right(PartialDate(None, None, None))
      case v if v.startsWith("---") => int(v.drop(3)).map(d => PartialDate(None, None, Some(d)))
      case v if v.startsWith("--")  =>
        v.drop(2).split('-').toList match
          case m :: Nil      => int(m).map(mm => PartialDate(None, Some(mm), None))
          case m :: d :: Nil =>
            (int(m), int(d)).mapN((mm, dd) => PartialDate(None, Some(mm), Some(dd)))
          case _ => Left(s"unparseable partial date: $s")
      // A year with a day but no month: not meaningful as a calendar date, but representable,
      // so render/parse stay inverse rather than silently dropping the day.
      case v if v.matches("""\d+---\d+""") =>
        val Array(y, d) = v.split("---"): @unchecked
        (int(y), int(d)).mapN((yy, dd) => PartialDate(Some(yy), None, Some(dd)))
      // Bare `MM-DD` for a yearless date. The canonical rendering is `--05-12`, but that reads as
      // a flag on a command line, and a birthday is the most common partial date there is.
      case v if v.matches("""\d{1,2}-\d{1,2}""") =>
        val Array(m, d) = v.split("-"): @unchecked
        (int(m), int(d)).mapN((mm, dd) => PartialDate(None, Some(mm), Some(dd)))
      case v =>
        v.split('-').toList match
          case y :: Nil           => int(y).map(yy => PartialDate(Some(yy), None, None))
          case y :: m :: Nil      => (int(y), int(m)).mapN((yy, mm) => PartialDate(Some(yy), Some(mm), None))
          case y :: m :: d :: Nil => (int(y), int(m), int(d)).mapN((yy, mm, dd) => PartialDate.of(yy, mm, dd))
          case _                  => Left(s"unparseable date: $s")

  /** Serialized as its rendering so the journal stays human-readable. */
  given Codec[PartialDate] = Codec.from(
    Decoder.decodeString.emap(parse),
    Encoder.encodeString.contramap(_.render)
  )

  /** Orders by lower bound; yearless dates sort last since they are not located in time. */
  given Order[PartialDate] = Order.from: (a, b) =>
    (a.lowerBound, b.lowerBound) match
      case (Some(x), Some(y)) => x.compareTo(y)
      case (Some(_), None)    => -1
      case (None, Some(_))    => 1
      case (None, None)       => 0

/** A typed data value: an RDF 1.1 literal (§3.3).
  *
  * Every literal is a lexical form plus a datatype IRI, and a language tag exactly when the
  * datatype is `rdf:langString`. Storing the lexical form rather than a parsed value is what the
  * standard requires and what makes the representation faithful: `xsd:integer` and `xsd:decimal`
  * are different datatypes even where they denote the same number, `"1.0"` and `"1.00"` are
  * different lexical forms of one value, and a datatype Noesis has never heard of still round-trips
  * through the journal intact.
  *
  * Being lexical also removes every number from the JSON an [[Axiom]] encodes to, so axiom
  * identifiers no longer depend on floating-point serialization at all.
  */
final case class Literal(lexical: String, datatype: Iri, language: Option[String] = None):

  /** Plain-text rendering used by verbalization and quiz answer matching. */
  def render: String = language.fold(lexical)(tag => s"$lexical@$tag")

  /** The text a learner would be expected to produce, without metadata noise. */
  def text: String = lexical

  /** Is the lexical form in its datatype's lexical space (XSD 1.1 Part 2), and — when this is a
    * language-tagged string — is the tag well-formed under BCP 47?
    */
  def isWellFormed: Boolean =
    Datatypes.isValid(datatype, lexical) && language.forall(LanguageTag.isWellFormed)

  /** This literal reduced to its datatype's canonical lexical form, or why it cannot be. */
  def canonical: Either[String, Literal] =
    Datatypes.canonical(datatype, lexical).map(form => copy(lexical = form))

  /** The numeric value, when this literal is one. `xsd:integer` derives from `xsd:decimal`, so both
    * answer here; a numeral typed as a string deliberately does not.
    */
  def asDecimal: Option[BigDecimal] =
    Option
      .when(datatype == Xsd.decimal || datatype == Xsd.integer)(lexical)
      .flatMap(form => Try(BigDecimal(form)).toOption)

  def asBoolean: Option[Boolean] =
    Option.when(datatype == Xsd.boolean)(lexical).collect:
      case "true" | "1"  => true
      case "false" | "0" => false

  def asDate: Option[PartialDate] =
    Option.when(Datatypes.isDate(datatype))(lexical).flatMap(PartialDate.parse(_).toOption)

  def asInstant: Option[Instant] =
    Option.when(datatype == Xsd.dateTime)(lexical).flatMap(form => Try(Instant.parse(form)).toOption)

object Literal:
  def string(value: String): Literal = Literal(value, Xsd.string)

  /** RDF 1.1 §3.3: a language tag implies — and is only permitted with — `rdf:langString`. */
  def tagged(value: String, lang: String): Literal = Literal(value, Rdf.langString, Some(lang))

  def boolean(value: Boolean): Literal = Literal(value.toString, Xsd.boolean)

  def decimal(value: BigDecimal): Literal =
    Literal(Datatypes.canonical(Xsd.decimal, value.toString).getOrElse(value.toString), Xsd.decimal)

  def integer(value: BigInt): Literal = Literal(value.toString, Xsd.integer)

  def date(value: PartialDate): Literal = Literal(value.render, Datatypes.of(value))

  def date(year: Int, month: Int, day: Int): Literal = date(PartialDate.of(year, month, day))

  def instant(value: Instant): Literal = Literal(value.toString, Xsd.dateTime)

  /** Best-effort parse of a CLI-supplied value, used by structured capture. */
  def parse(raw: String): Literal =
    raw match
      case v if v.matches("""-{2,3}\d{2}(-\d{2})?""") =>
        PartialDate.parse(v).fold(_ => string(v), date)
      case v if v.matches("""\d{4}(-\d{2}(-\d{2})?)?""") =>
        PartialDate.parse(v).fold(_ => string(v), date)
      // `5-12` / `05-12`: a yearless date, so command lines need no `--` escaping.
      case v if v.matches("""\d{1,2}-\d{1,2}""") =>
        PartialDate.parse(v).fold(_ => string(v), date)
      case "true" | "false" => boolean(raw.toBoolean)
      // Capture canonicalizes numerals. RDF keeps `"1.50"` and `"1.5"` distinct as *terms* even
      // though they denote one value, so reducing at the boundary is what stops the same fact,
      // typed twice, from becoming two axioms with two identifiers.
      case v if v.matches("""-?\d+""")      => integer(BigInt(v))
      case v if v.matches("""-?\d+\.\d+""") => decimal(BigDecimal(v))
      case v if v.matches(""".+@[a-z]{2}(-[A-Za-z]+)?""") =>
        val i = v.lastIndexOf('@')
        tagged(v.substring(0, i), v.substring(i + 1))
      case v => string(v)

  // Ordering by the tag itself rather than by a stand-in for its absence: an untagged literal sorts
  // before every tagged one, instead of before or after whichever sentinel happened to be chosen.
  given Order[Literal] =
    Order.by(literal => (literal.lexical, literal.datatype.value, literal.language))

  private val encoder: Encoder[Literal] = Encoder.instance: literal =>
    Json.obj(
      "lexical" -> Json.fromString(literal.lexical),
      "datatype" -> literal.datatype.asJson,
      "language" -> literal.language.asJson
    )

  /** Accepts both the current form and the pre-typed-literal one.
    *
    * The journal is append-only, so a wire-format change cannot rewrite what is already on disk —
    * the reader has to keep understanding it (journal SPEC §6). The two forms are told apart by
    * which key is present rather than by a version field: the old encoding was a circe sum with a
    * `type` discriminator, the new one has no discriminator and always carries `lexical`.
    */
  private val decoder: Decoder[Literal] = Decoder.instance: cursor =>
    cursor.get[Option[String]]("lexical").flatMap:
      case Some(lexical) =>
        (cursor.get[Iri]("datatype"), cursor.get[Option[String]]("language"))
          .mapN(Literal(lexical, _, _))
      case None => legacy(cursor)

  private def legacy(cursor: HCursor): Decoder.Result[Literal] =
    cursor.get[String]("type").flatMap:
      case "Str" =>
        (cursor.get[String]("value"), cursor.get[Option[String]]("lang")).mapN: (value, lang) =>
          lang.fold(string(value))(tagged(value, _))
      case "Num"  => cursor.get[BigDecimal]("value").map(decimal)
      case "Bool" => cursor.get[Boolean]("value").map(boolean)
      case "Date" => cursor.get[PartialDate]("value").map(date)
      case "Time" => cursor.get[Instant]("value").map(instant)
      case other  => Left(DecodingFailure(s"unknown literal form: $other", cursor.history))

  given Codec[Literal] = Codec.from(decoder, encoder)
