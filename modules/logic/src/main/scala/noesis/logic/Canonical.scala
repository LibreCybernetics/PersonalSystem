package noesis.logic

import java.nio.charset.StandardCharsets

import io.circe.{Json, JsonNumber}

/** JSON canonicalization per RFC 8785 (JCS).
  *
  * `AxiomId` is a digest of an axiom's canonical form, and SPEC §6.2 of this module requires an
  * unchanged axiom to keep its identifier across releases. That invariant is only meaningful if
  * "canonical" names a fixed byte sequence. Deriving it from a serializer's incidental behavior does
  * not: circe emits object members in case-class declaration order, so reordering two fields in
  * [[Axiom]] would silently change every identifier in every journal. JCS fixes member order, number
  * formatting and string escaping independently of how the JSON was produced.
  *
  * Canonicalization is deliberately a separate, total function rather than an inlined expression:
  * RFC 8785 publishes test vectors, and a conformance suite needs something to point at.
  */
object Canonical:

  /** RFC 8785 §3.2 serialization of an already-parsed JSON value. */
  def serialize(json: Json): String =
    json.fold(
      "null",
      boolean => if boolean then "true" else "false",
      number,
      string,
      values => values.map(serialize).mkString("[", ",", "]"),
      // RFC 8785 §3.2.3: members sort by their key's UTF-16 code units, which is exactly what
      // `String.compareTo` — and therefore `Ordering.String` — compares.
      obj =>
        obj.toList
          .sortBy(_._1)
          .map((key, value) => s"${string(key)}:${serialize(value)}")
          .mkString("{", ",", "}")
    )

  /** The canonical form Noesis hashes and persists: absent optionals dropped, then JCS.
    *
    * Dropping nulls is a Noesis pre-step, not part of JCS. It is applied *deeply* so that a nested
    * absent value — a literal without a language tag, a fluent without an end reason — contributes
    * nothing, exactly as an absent top-level field does. The journal writer and [[AxiomId]] share
    * this function so "canonical JSON" has one meaning in the codebase rather than two.
    */
  def noesis(json: Json): String = serialize(json.deepDropNullValues)

  def bytes(json: Json): Array[Byte] = noesis(json).getBytes(StandardCharsets.UTF_8)

  /** RFC 8785 §3.2.2.2: the shortest escape for every character that requires one. */
  private def string(value: String): String =
    val escaped = value.flatMap:
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\b'         => "\\b"
      case '\f'         => "\\f"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    s"\"$escaped\""

  /** RFC 8785 §3.2.2.3 defers number formatting to ECMAScript `Number::toString`.
    *
    * JCS defines canonicalization only for numbers representable as IEEE-754 doubles. Noesis does
    * not reject wider input — it emits the number's own lexical form instead, a recorded deviation.
    * Nothing Noesis encodes can reach that branch: axioms carry no numbers at all now that literals
    * are lexical, and everything else journaled is a sequence number or a value in [0,1].
    */
  private def number(value: JsonNumber): String =
    val double = value.toDouble
    if double.isFinite then ecmascript(double) else value.toString

  private def ecmascript(value: Double): String =
    // Negatives are peeled off before zero so that the two tests do not overlap: `< 0.0` and
    // `<= 0.0` then disagree on zero itself, which a test can pin.
    if value < 0.0 then s"-${ecmascript(-value)}"
    else if value == 0.0 then "0"
    else
      val (digits, point) = decompose(value)
      val length = digits.length
      // The window in which the algorithm writes fixed notation is tested first, so that each
      // remaining branch turns on one condition rather than repeating the bound. Every boundary
      // here is reachable: 1e20/1e21 straddle the upper edge and 1e-6/1e-7 the lower.
      if point > 21 || point <= -6 then exponential(digits, point - 1)
      else if point >= length then digits + ("0" * (point - length))
      else if point > 0 then s"${digits.take(point)}.${digits.drop(point)}"
      else s"0.${"0" * -point}$digits"

  /** Exponential notation for a digit string and an exponent.
    *
    * Separated out, and visible to this module's tests, because the values that reach it from
    * [[ecmascript]] always have an exponent of 21 or more or -7 or less. The sign of a zero
    * exponent is therefore not reachable through canonicalization, but it is part of this
    * function's contract, so it is pinned here rather than left as an untestable branch.
    */
  private[logic] def exponential(digits: String, exponent: Int): String =
    val mantissa = if digits.length == 1 then digits else s"${digits.head}.${digits.tail}"
    // A negative exponent already prints its own sign; only a non-negative one needs a `+`.
    val sign = if exponent < 0 then "" else "+"
    s"${mantissa}e$sign$exponent"

  /** Splits a positive finite double into its shortest round-tripping digits and the position of
    * the decimal point relative to them, which is the `(s, n)` pair ECMAScript's algorithm is
    * written in terms of.
    *
    * `Double.toString` has produced the shortest round-tripping representation since JDK 19, so the
    * digits below are already minimal; only the framing differs between Java and ECMAScript.
    */
  private def decompose(value: Double): (String, Int) =
    // Java writes at most one `E` and always one digit either side of the point, so splitting on
    // the first occurrence of each is exact. `span` rather than `indexOf` because there is no
    // second occurrence for a search direction to disagree about.
    val (mantissa, exponentText) = java.lang.Double.toString(value).span(_ != 'E')
    val exponent = if exponentText.isEmpty then 0 else exponentText.drop(1).toInt
    val (whole, fractionText) = mantissa.span(_ != '.')
    val raw = whole + fractionText.drop(1)
    val significant = raw.dropWhile(_ == '0')
    val point = whole.length + exponent - (raw.length - significant.length)
    (significant.reverse.dropWhile(_ == '0').reverse, point)
