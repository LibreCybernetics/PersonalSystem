package noesis.logic

import java.time.{Instant, LocalDate, MonthDay}

import scala.util.Try

import cats.Order
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Codec, Decoder, DecodingFailure, Encoder, HCursor, Json}

/** A date located in time but known only to some precision: `2026`, `2026-05` or `2026-05-12`
  * (SPEC §3.1).
  *
  * The year is not optional, and that is the whole point of the type. A value without a year is not
  * an imprecise date but a *recurrence* — "12 May" names a day in every year — and conflating the
  * two cost more than it saved: the ordering was unlawful (two different yearless values compared
  * equal), the bounds were `Option` for every caller including those that could never see one, and
  * the two shapes with no XSD datatype existed only to keep the parser total. Recurrences are
  * `java.time.MonthDay` and carry `xsd:gMonthDay`; see [[Literal.anniversary]].
  *
  * Every shape here is the lexical form of an XSD date datatype (§3.3.9–§3.3.11), so
  * [[Datatypes.of]] is total and no Noesis-minted date datatype is needed.
  */
enum PartialDate:
  case Day(year: Int, month: Int, day: Int)
  case Month(year: Int, month: Int)
  case Year(year: Int)

  // No `year`/`month`/`day` accessors: an enum case field may not share a name with a method on the
  // enum, and nothing outside this file wanted the components — callers want a rendering, a bound
  // or an anniversary. Matching on the case is how code inside gets at them.

  /** XSD-style rendering: `2026-05-12`, `2026-05`, `2026`.
    *
    * Years are padded to four digits because `xsd:gYear` admits no shorter form. Years outside
    * 0001–9999 are still not handled (deviation D2).
    */
  def render: String =
    def two(i: Int) = "%02d".format(i)
    def four(i: Int) = "%04d".format(i)
    this match
      case Day(y, m, d) => s"${four(y)}-${two(m)}-${two(d)}"
      case Month(y, m)  => s"${four(y)}-${two(m)}"
      case Year(y)      => four(y)

  def isComplete: Boolean = this match
    case Day(_, _, _) => true
    case _            => false

  /** The earliest day this date could denote. Total: a located date always has one. */
  def lowerBound: LocalDate = this match
    case Day(y, m, d) => LocalDate.of(y, m, d)
    case Month(y, m)  => LocalDate.of(y, m, 1)
    case Year(y)      => LocalDate.of(y, 1, 1)

  /** The day after the last one this date could denote (exclusive). */
  def upperBound: LocalDate = this match
    case Day(y, m, d) => LocalDate.of(y, m, d).plusDays(1)
    case Month(y, m)  => LocalDate.of(y, m, 1).plusMonths(1)
    case Year(y)      => LocalDate.of(y, 1, 1).plusYears(1)

  /** The recurrence this date has an instance of, when it is precise enough to have one.
    *
    * What connects the two types: a birthday recorded as `1990-05-12` and one recorded as `--05-12`
    * must drive the same occasion (SPEC §7.4), and this is where the first becomes the second.
    */
  def anniversary: Option[MonthDay] = this match
    case Day(_, m, d) => Some(MonthDay.of(m, d))
    case _            => None

object PartialDate:
  def of(year: Int, month: Int, day: Int): PartialDate = Day(year, month, day)

  def from(date: LocalDate): PartialDate =
    of(date.getYear, date.getMonthValue, date.getDayOfMonth)

  /** A timezone on a date is legal XSD and meaningless here — see [[parse]]. */
  private val timezoned = """.*(?:Z|[+-]\d{2}:\d{2})$""".r

  /** Parses the renderings produced by [[PartialDate.render]].
    *
    * Rejects a timezoned form explicitly rather than failing on a digit. XSD 1.1 admits an optional
    * timezone on every date datatype and `Datatypes` accepts one, because that clause is what
    * conformance is claimed against; a Noesis date is a calendar date about someone's life, has no
    * zone to record, and would denote an interval rather than a day if it had one. The two answer
    * different questions, and the message says so instead of leaving the caller to guess.
    */
  def parse(s: String): Either[String, PartialDate] =
    def int(x: String) = x.toIntOption.toRight(s"not a number: $x")
    s.trim match
      case ""                 => Left("empty date")
      // `unknown` was a value this type held until it was narrowed. Naming it is worth a branch:
      // the answer is not a different spelling but an absent value, and a caller that reaches here
      // is holding a date-shaped hole rather than a date.
      case "unknown"          => Left("a date that is not known is an absent value, not 'unknown'")
      case timezoned(_*)      => Left(s"a date carries no timezone in Noesis: $s")
      case v if v.startsWith("-") => Left(s"unparseable date: $s")
      // The year is four digits or more, which is what `render` emits and all XSD's date datatypes
      // admit. Without the check `05-12` reads as *year 5, month 12* rather than failing, and the
      // month-day form that command lines and FOAF both use would be silently mis-dated.
      case v if !v.takeWhile(_ != '-').matches("""\d{4,}""") =>
        Left(s"a date starts with a four-digit year: $s")
      case v =>
        v.split('-').toList match
          case y :: Nil      => int(y).map(Year.apply)
          case y :: m :: Nil => (int(y), int(m)).mapN(Month.apply)
          case y :: m :: d :: Nil => (int(y), int(m), int(d)).mapN(Day.apply)
          case _             => Left(s"unparseable date: $s")

  /** Serialized as its rendering so the journal stays human-readable. The three surviving shapes
    * render exactly as they did before this type was narrowed, so no stored date changes.
    */
  given Codec[PartialDate] = Codec.from(
    Decoder.decodeString.emap(parse),
    Encoder.encodeString.contramap(_.render)
  )

  /** Orders by the earliest day each could denote, which is total and lawful now that every value
    * is located: `compare` returns 0 only for equal values.
    */
  given Order[PartialDate] = Order.from: (a, b) =>
    val byStart = a.lowerBound.compareTo(b.lowerBound)
    // Same start, different precision: `2026` starts on the day `2026-01-01` does. The wider one
    // sorts second, so that ordering agrees with equality instead of calling them the same date.
    if byStart =!= 0 then byStart else a.upperBound.compareTo(b.upperBound)

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

  /** The recurrence this literal denotes: an `xsd:gMonthDay` directly, a located date through its
    * anniversary. One accessor for both so that occasion matching never has to ask which shape a
    * birthday was captured in.
    */
  def asAnniversary: Option[MonthDay] =
    if datatype == Xsd.gMonthDay then Try(MonthDay.parse(lexical)).toOption
    else asDate.flatMap(_.anniversary)

  def asInstant: Option[Instant] =
    Option.when(datatype == Xsd.dateTime)(lexical).flatMap(form => Try(Instant.parse(form)).toOption)

object Literal:
  def string(value: String): Literal = Literal(value, Xsd.string)

  /** RDF 1.1 §3.3: a language tag implies — and is only permitted with — `rdf:langString`. */
  def tagged(value: String, lang: String): Literal = Literal(value, Rdf.langString, Some(lang))

  def boolean(value: Boolean): Literal = Literal(value.toString, Xsd.boolean)

  def decimal(value: BigDecimal): Literal =
    val raw = value.bigDecimal.toPlainString
    Literal(Datatypes.canonical(Xsd.decimal, raw).getOrElse(raw), Xsd.decimal)

  def integer(value: BigInt): Literal = Literal(value.toString, Xsd.integer)

  def date(value: PartialDate): Literal = Literal(value.render, Datatypes.of(value))

  def date(year: Int, month: Int, day: Int): Literal = date(PartialDate.of(year, month, day))

  /** A recurring day of the year — a birthday whose year nobody knows (SPEC §7.4).
    *
    * `MonthDay.toString` is `--05-12`, which is exactly the `xsd:gMonthDay` lexical form, so this
    * writes the same literal the old yearless `PartialDate` did: stored birthdays keep their
    * lexical form, their datatype and therefore their `AxiomId` across the narrowing.
    */
  def anniversary(value: MonthDay): Literal = Literal(value.toString, Xsd.gMonthDay)

  def anniversary(month: Int, day: Int): Literal = anniversary(MonthDay.of(month, day))

  def instant(value: Instant): Literal = Literal(value.toString, Xsd.dateTime)

  /** A birthday-shaped value from an interchange format: a located date, or a recurring day.
    *
    * Both are legitimate answers to "when is their birthday", and which one a vCard or a FOAF
    * profile carries is not the importer's choice. Splitting the value model into two types made
    * that a question every import boundary has to ask, so it is asked once, here.
    */
  def dateOrAnniversary(raw: String): Option[Literal] =
    PartialDate.parse(raw).toOption.map(date).orElse(monthDay(raw).map(anniversary))

  /** `--05-12` as `xsd:gMonthDay` writes it, or the bare `05-12` that vCard, FOAF and command lines
    * use. Both denote the same recurring day.
    */
  private def monthDay(raw: String): Option[MonthDay] =
    if raw.matches("""\d{1,2}-\d{1,2}""") then
      val Array(m, d) = raw.split("-"): @unchecked
      Try(MonthDay.of(m.toInt, d.toInt)).toOption
    else Try(MonthDay.parse(raw)).toOption

  /** Best-effort parse of a CLI-supplied value, used by structured capture. */
  def parse(raw: String): Literal =
    raw match
      case v if v.matches("""--\d{2}-\d{2}""") =>
        Try(MonthDay.parse(v)).fold(_ => string(v), anniversary)
      case v if v.matches("""\d{4}(-\d{2}(-\d{2})?)?""") =>
        PartialDate.parse(v).fold(_ => string(v), date)
      // `5-12` / `05-12`: a recurring day, so command lines need no `--` escaping.
      case v if v.matches("""\d{1,2}-\d{1,2}""") =>
        dateOrAnniversary(v).getOrElse(string(v))
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
      case "Date" =>
        cursor.get[String]("value").flatMap: raw =>
          legacyDate(raw).left.map(DecodingFailure(_, cursor.history))
      case "Time" => cursor.get[Instant]("value").map(instant)
      case other  => Left(DecodingFailure(s"unknown literal form: $other", cursor.history))

  /** A date written by a journal older than the narrowing, when one type held both located dates
    * and recurring days.
    *
    * Every shape that still denotes something keeps denoting it — a located date becomes a
    * `PartialDate`, `--05-12` becomes a recurrence, and `--05` / `---12` survive as the `xsd:gMonth`
    * and `xsd:gDay` terms they always were, even though no value type reads them. The two that go
    * are the ones the narrowing removed, and replay fails loudly on them rather than guessing:
    * neither has an XSD datatype to fall back to, and both meant "we do not know", which is now the
    * absence of a value rather than a value.
    */
  private def legacyDate(raw: String): Either[String, Literal] =
    PartialDate.parse(raw).map(date) match
      case Right(located) => Right(located)
      case Left(_)        =>
        Try(MonthDay.parse(raw)).toOption.map(anniversary) match
          case Some(recurring) => Right(recurring)
          case None            =>
            List(Xsd.gMonth, Xsd.gDay)
              .find(Datatypes.isValid(_, raw))
              .map(Literal(raw, _))
              .toRight(
                s"legacy date '$raw' has no representation: it is one of the shapes dropped when " +
                  "PartialDate was narrowed to located dates, and it has no XSD datatype"
              )

  given Codec[Literal] = Codec.from(decoder, encoder)
