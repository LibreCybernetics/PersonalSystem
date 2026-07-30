package noesis.logic

/** XSD 1.1 Part 2 datatype identifiers. */
object Xsd:
  val string: Iri = Iri("xsd:string")
  val boolean: Iri = Iri("xsd:boolean")
  val decimal: Iri = Iri("xsd:decimal")
  val integer: Iri = Iri("xsd:integer")
  val date: Iri = Iri("xsd:date")
  val dateTime: Iri = Iri("xsd:dateTime")
  val gYear: Iri = Iri("xsd:gYear")
  val gYearMonth: Iri = Iri("xsd:gYearMonth")
  val gMonthDay: Iri = Iri("xsd:gMonthDay")
  val gMonth: Iri = Iri("xsd:gMonth")
  val gDay: Iri = Iri("xsd:gDay")

/** RDF 1.1 datatypes that are not XSD's. */
object Rdf:
  /** The datatype of every language-tagged string (RDF 1.1 §3.3). */
  val langString: Iri = Iri("rdf:langString")

/** Datatypes Noesis defines because XSD has no counterpart. */
object CoreDatatype:
  /** A date whose known components do not form any XSD date datatype: a year with a day but no
    * month (`2026---12`), or a date with nothing known at all. XSD has seven date datatypes and
    * none covers these two shapes, so rather than invent a lexical form for `xsd:date` that is not
    * in its lexical space, Noesis names the gap. Recorded as a deviation.
    */
  val partialDate: Iri = Iri("core:partialDate")

/** Lexical spaces and canonical mappings for the datatypes Noesis mints.
  *
  * XSD 1.1 Part 2 defines a datatype as a lexical space, a value space, and the mappings between
  * them — so this is where XSD conformance is testable at all. [[Literal]] stores a lexical form and
  * a datatype IRI; that pair only denotes a value if the lexical form is in the datatype's lexical
  * space, and only compares reliably if it has been reduced to the canonical form.
  */
object Datatypes:

  private val timezone = """(?:Z|[+-](?:(?:0[0-9]|1[0-3]):[0-5][0-9]|14:00))?"""
  private val year = """-?(?:[1-9][0-9]{3,}|0[0-9]{3})"""
  private val month = """(?:0[1-9]|1[0-2])"""
  private val day = """(?:0[1-9]|[12][0-9]|3[01])"""
  private val time = """(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]+)?"""

  private val lexicalSpaces: Map[Iri, String] = Map(
    Xsd.boolean -> """true|false|1|0""",
    Xsd.integer -> """[+-]?[0-9]+""",
    Xsd.decimal -> """[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)""",
    Xsd.date -> s"$year-$month-$day$timezone",
    Xsd.dateTime -> s"${year}-$month-${day}T$time$timezone",
    Xsd.gYear -> s"$year$timezone",
    Xsd.gYearMonth -> s"$year-$month$timezone",
    Xsd.gMonthDay -> s"--$month-$day$timezone",
    Xsd.gMonth -> s"--$month$timezone",
    Xsd.gDay -> s"---$day$timezone"
  )

  /** Is `lexical` in `datatype`'s lexical space?
    *
    * `xsd:string`, `rdf:langString` and `core:partialDate` admit any character sequence, and an
    * unrecognized datatype is accepted rather than rejected: RDF 1.1 §5 makes unknown datatypes a
    * matter for the consuming application, not a syntax error, and modules are free to introduce
    * their own.
    */
  def isValid(datatype: Iri, lexical: String): Boolean =
    lexicalSpaces.get(datatype).forall(lexical.matches)

  /** The canonical representation of `lexical` under `datatype` (XSD 1.1 Part 2 §3.3.x), or a
    * reason it is not in the lexical space at all.
    */
  def canonical(datatype: Iri, lexical: String): Either[String, String] =
    if !isValid(datatype, lexical) then
      Left(s"'$lexical' is not in the lexical space of ${datatype.value}")
    else if datatype == Xsd.boolean then Right(if lexical == "true" || lexical == "1" then "true" else "false")
    else if datatype == Xsd.integer then Right(canonicalInteger(lexical))
    else if datatype == Xsd.decimal then Right(canonicalDecimal(lexical))
    else if datatype == Xsd.dateTime then Right(canonicalDateTime(lexical))
    else Right(lexical)

  /** §3.3.13.2: no leading `+`, no leading zeroes, and zero is unsigned. */
  private def canonicalInteger(lexical: String): String =
    val negative = lexical.startsWith("-")
    val digits = lexical.dropWhile(c => c == '+' || c == '-').dropWhile(_ == '0')
    if digits.isEmpty then "0" else if negative then s"-$digits" else digits

  /** §3.3.3.2: as integer, but a decimal point with at least one digit on each side. */
  private def canonicalDecimal(lexical: String): String =
    val negative = lexical.startsWith("-")
    val unsigned = lexical.dropWhile(c => c == '+' || c == '-')
    // The lexical space allows at most one point, so `span` splits exactly and leaves no second
    // occurrence for a search direction to disagree about.
    val (whole, fractionText) = unsigned.span(_ != '.')
    val fraction = fractionText.drop(1)
    val left = whole.dropWhile(_ == '0')
    val right = fraction.reverse.dropWhile(_ == '0').reverse
    val magnitude = s"${if left.isEmpty then "0" else left}.${if right.isEmpty then "0" else right}"
    if negative && magnitude.exists(c => c.isDigit && c != '0') then s"-$magnitude" else magnitude

  /** §3.3.8.2: a trailing fractional part of all zeroes is dropped, as is the point itself. */
  private def canonicalDateTime(lexical: String): String =
    val (head, pointAndRest) = lexical.span(_ != '.')
    val rest = pointAndRest.drop(1)
    val fraction = rest.takeWhile(_.isDigit)
    val suffix = rest.drop(fraction.length)
    // An absent point leaves an empty fraction, which is vacuously all zeroes — so the
    // no-fraction and all-zero-fraction cases are one case, not two.
    if fraction.forall(_ == '0') then head + suffix
    else s"$head.${fraction.reverse.dropWhile(_ == '0').reverse}$suffix"

  /** The datatypes whose values are dates, however partially known. */
  val dates: Set[Iri] =
    Set(
      Xsd.date,
      Xsd.gYear,
      Xsd.gYearMonth,
      Xsd.gMonthDay,
      Xsd.gMonth,
      Xsd.gDay,
      CoreDatatype.partialDate
    )

  def isDate(datatype: Iri): Boolean = dates.contains(datatype)

  /** Which datatype a [[PartialDate]] denotes, given which components it knows. */
  def of(date: PartialDate): Iri = (date.year, date.month, date.day) match
    case (Some(_), Some(_), Some(_)) => Xsd.date
    case (Some(_), Some(_), None)    => Xsd.gYearMonth
    case (Some(_), None, None)       => Xsd.gYear
    case (None, Some(_), Some(_))    => Xsd.gMonthDay
    case (None, Some(_), None)       => Xsd.gMonth
    case (None, None, Some(_))       => Xsd.gDay
    // A year with a day but no month, and a date with nothing known: no XSD datatype covers either.
    case (Some(_), None, Some(_))    => CoreDatatype.partialDate
    case (None, None, None)          => CoreDatatype.partialDate
