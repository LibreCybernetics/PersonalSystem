package noesis.logic

import java.time.{Instant, LocalDate}

import scala.util.control.NonFatal

import cats.Order
import cats.syntax.all.*
import io.circe.derivation.ConfiguredCodec
import io.circe.{Codec, Decoder, Encoder}

/** A date that may be partially specified, e.g. `--05-12` for "12 May, year unknown" (SPEC §3.1).
  *
  * Birthdays are the motivating case: many are known as month/day only, and the ontology must not
  * force a fake year.
  */
final case class PartialDate(year: Option[Int], month: Option[Int], day: Option[Int]):
  /** XSD-style rendering: `2026-01-01`, `2026-05`, `--05-12`, `2026`. */
  def render: String =
    def two(i: Int) = "%02d".format(i)
    (year, month, day) match
      case (Some(y), Some(m), Some(d)) => s"$y-${two(m)}-${two(d)}"
      case (Some(y), Some(m), None)    => s"$y-${two(m)}"
      case (Some(y), None, Some(d))    => s"$y---${two(d)}"
      case (Some(y), None, None)       => y.toString
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

/** A typed data value. Language-tagged strings and XSD datatypes per SPEC §3.1. */
enum Literal derives ConfiguredCodec:
  case Str(value: String, lang: Option[String] = None)
  case Num(value: BigDecimal)
  case Bool(value: Boolean)
  case Date(value: PartialDate)
  case Time(value: Instant)

  /** Plain-text rendering used by verbalization and quiz answer matching. */
  def render: String = this match
    case Str(v, None)       => v
    case Str(v, Some(lang)) => s"$v@$lang"
    case Num(v)             => v.toString
    case Bool(v)            => v.toString
    case Date(v)            => v.render
    case Time(v)            => v.toString

  /** The text a learner would be expected to produce, without metadata noise. */
  def text: String = this match
    case Str(v, _) => v
    case other     => other.render

object Literal:
  def string(value: String): Literal = Str(value)
  def tagged(value: String, lang: String): Literal = Str(value, Some(lang))
  def date(year: Int, month: Int, day: Int): Literal = Date(PartialDate.of(year, month, day))

  /** Best-effort parse of a CLI-supplied value, used by structured capture. */
  def parse(raw: String): Literal =
    raw match
      case v if v.matches("""-{2,3}\d{2}(-\d{2})?""") =>
        PartialDate.parse(v).fold(_ => Str(v), Date(_))
      case v if v.matches("""\d{4}(-\d{2}(-\d{2})?)?""") =>
        PartialDate.parse(v).fold(_ => Str(v), Date(_))
      // `5-12` / `05-12`: a yearless date, so command lines need no `--` escaping.
      case v if v.matches("""\d{1,2}-\d{1,2}""") =>
        PartialDate.parse(v).fold(_ => Str(v), Date(_))
      case "true" | "false" => Bool(raw.toBoolean)
      case v if v.matches("""-?\d+(\.\d+)?""") =>
        try Num(BigDecimal(v))
        catch case NonFatal(_) => Str(v)
      case v if v.matches(""".+@[a-z]{2}(-[A-Za-z]+)?""") =>
        val i = v.lastIndexOf('@')
        Str(v.substring(0, i), Some(v.substring(i + 1)))
      case v => Str(v)

  given Order[Literal] = Order.by(_.render)
