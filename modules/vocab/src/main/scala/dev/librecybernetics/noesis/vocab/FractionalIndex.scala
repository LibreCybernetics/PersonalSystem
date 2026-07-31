package dev.librecybernetics.noesis.vocab

/** Sibling order keys for the outline (SPEC §8.5.1, PRODUCT.md PD-08).
  *
  * A block's position among its siblings is a *sortable string* chosen so that inserting between
  * two blocks produces a key strictly between theirs. The alternative — integer positions — makes
  * inserting one line renumber every line below it, and because `note:order` is a fluent (§3.6)
  * every one of those renumbers is a supersession recorded in the journal forever. Typing a line
  * near the top of a long note would cost proportionally to the note's length, permanently.
  *
  * A key is an **integer part** followed by an optional **fraction**. The integer part is what
  * keeps appending cheap: it counts, so a thousand appends produce a key of four characters rather
  * than of two hundred. A plain fractional midpoint was measured at about one character per five
  * blocks, which is fine for a scratch note and wrong for a page appended to every day — and
  * appending is the one operation a dated page is made of. The fraction absorbs what counting
  * cannot: inserting *between* two adjacent positions, where growth is unavoidable in any scheme
  * that does not renumber.
  *
  * The digits are ASCII-ordered on purpose, so ordinary string comparison *is* the order and no
  * comparator has to travel alongside the keys. `xsd:string` sorting, `sortBy` here, and a `sort`
  * over an exported Markdown mirror all agree.
  *
  * **Keys are never rebalanced.** Renumbering a note would emit `state.changed` for every block in
  * it (§3.6), which is a false "this changed" signal to §4.1's staleness detection — and one that
  * pruning superseded order states (§3.2.1) does not undo, because the events were already emitted.
  *
  * The scheme is the one described by
  * [Implementing Fractional Indexing](https://observablehq.com/@dgreensp/implementing-fractional-indexing).
  */
object FractionalIndex:

  /** ASCII order and digit order are the same sequence, which is the property everything rests on. */
  private val digits: String =
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

  private val base: Int = digits.length
  private val zero: Char = digits.charAt(0)
  private val top: Char = digits.charAt(base - 1)

  /** The position of each digit, looked up rather than searched for.
    *
    * A search would also be correct — no digit repeats — which is exactly the problem: `indexOf`
    * and `lastIndexOf` agree here, so a table makes the intent unambiguous and the lookup constant.
    */
  private val valueOf: Map[Char, Int] = digits.zipWithIndex.toMap

  /** How many characters an integer part occupies, by its head.
    *
    * Positive magnitudes grow upward from `a` and negative ones downward from `Z`, so that a longer
    * negative number — a smaller one — carries an earlier head and still sorts first. A character
    * outside both ranges heads no integer part at all, which is what makes a digit an illegal head.
    */
  private val integerSizes: Map[Char, Int] =
    (('a' to 'z').map(head => head -> (head - 'a' + 2))
      ++ ('A' to 'Z').map(head => head -> ('Z' - head + 2))).toMap

  /** The integer part standing for zero, and so the key of the first block in a note. */
  private val integerZero: String = "a0"

  /** Why a pair of neighbours admits no key between them. */
  enum Problem:
    case OutOfOrder(lower: String, upper: String)
    case NotAKey(key: String)

    /** The integer part ran out of room. Reaching this needs on the order of 62^26 insertions in
      * one direction, so it is here to be total rather than to be handled.
      */
    case Exhausted(neighbour: String)

  /** A key strictly between `lower` and `upper`, either of which may be absent for an open end.
    *
    * `between(None, None)` is the first key in an empty note, `between(Some(last), None)` appends,
    * and `between(None, Some(first))` prepends.
    *
    * Fails rather than guessing when the neighbours are out of order or are not keys this object
    * could have produced. A caller that has swapped two siblings would otherwise get a key that
    * sorts somewhere else entirely, and the outline would silently rearrange itself.
    */
  def between(lower: Option[String], upper: Option[String]): Either[Problem, String] =
    (lower.toList ++ upper.toList).find(key => split(key).isEmpty) match
      case Some(bad) => Left(Problem.NotAKey(bad))
      case None      =>
        (lower, upper) match
          case (Some(l), Some(u)) if l >= u => Left(Problem.OutOfOrder(l, u))
          case _                            => generate(lower, upper)

  /** A key's integer part and fraction, or `None` if the string is not a key.
    *
    * The head character says how long the integer part is, which is what lets integer parts of
    * different lengths still sort correctly against each other as plain strings.
    */
  private def split(key: String): Option[(String, String)] =
    for
      head <- key.headOption
      size <- integerSizes.get(head)
      if key.length >= size && key.forall(valueOf.contains)
      fraction = key.drop(size)
      // A fraction ending in zero has no room below it, which is the one shape `midpoint` cannot
      // answer for. Nothing here produces one, so accepting one would only admit a foreign key.
      if !fraction.endsWith(zero.toString)
    yield (key.take(size), fraction)

  private def generate(lower: Option[String], upper: Option[String]): Either[Problem, String] =
    (lower.flatMap(split), upper.flatMap(split)) match
      case (None, None) => Right(integerZero)

      // Prepending. Counting down is free until the integer space runs out, after which there is
      // still room inside the fraction of the smallest integer there is.
      case (None, Some((integer, fraction))) =>
        decrement(integer) match
          case Some(smaller)             => Right(smaller)
          case None if fraction.nonEmpty => Right(integer + midpoint("", Some(fraction)))
          case None                      => Left(Problem.Exhausted(integer + fraction))

      // Appending. When counting up overflows, the fraction takes over above the last key.
      case (Some((integer, fraction)), None) =>
        Right(increment(integer).getOrElse(integer + midpoint(fraction, None)))

      case (Some((lowInteger, lowFraction)), Some((highInteger, highFraction))) =>
        if lowInteger == highInteger then Right(lowInteger + midpoint(lowFraction, Some(highFraction)))
        else
          // The neighbours count differently, so the next integer up may already fit between them.
          increment(lowInteger).filter(_ < highInteger + highFraction) match
            case Some(next) => Right(next)
            case None       => Right(lowInteger + midpoint(lowFraction, None))

  /** The next integer part up, or `None` when the head can grow no further. */
  private def increment(integer: String): Option[String] =
    val head = integer.charAt(0)
    val (body, carry) = step(integer.drop(1).toVector, up = true)
    if !carry then Some(head.toString + body.mkString)
    else if head == 'Z' then Some(integerZero) // the one place the two ranges meet
    else if head == 'z' then None
    else
      // Crossing into a longer positive number lengthens the body; climbing toward zero from the
      // negative side shortens it, because a smaller magnitude needs fewer digits. Which of the two
      // is happening is a question about the head that carried, not about the one it becomes.
      val widened = if head >= 'a' then body :+ zero else body.dropRight(1)
      Some((head + 1).toChar.toString + widened.mkString)

  /** The next integer part down, or `None` when the head can shrink no further. */
  private def decrement(integer: String): Option[String] =
    val head = integer.charAt(0)
    val (body, borrow) = step(integer.drop(1).toVector, up = false)
    if !borrow then Some(head.toString + body.mkString)
    else if head == 'a' then Some("Z" + top.toString) // the same meeting point, downward
    else if head == 'A' then None
    else
      // The mirror of [[increment]]: a number growing more negative needs more digits, and one
      // climbing back toward zero from the positive side needs fewer.
      val widened = if head <= 'Z' then body :+ top else body.dropRight(1)
      Some((head - 1).toChar.toString + widened.mkString)

  /** Adds or subtracts one across the body digits, reporting whether it carried past the end.
    *
    * Running off either end of the alphabet is the carry, and `lift` is what asks that question:
    * there is no comparison against a bound to get the wrong way round.
    */
  private def step(body: Vector[Char], up: Boolean): (Vector[Char], Boolean) =
    body.foldRight((Vector.empty[Char], true)):
      case (digit, (rest, true)) =>
        digits.lift(valueOf.getOrElse(digit, 0) + (if up then 1 else -1)) match
          case Some(moved) => (moved +: rest, false)
          case None        => ((if up then zero else top) +: rest, true)
      case (digit, (rest, false)) => (digit +: rest, false)

  /** A fraction strictly between `lower` (empty meaning "no lower bound") and `upper`.
    *
    * Two fractions agreeing on their first characters differ only in what follows, so the answer
    * is their shared prefix followed by an answer about the remainders. The prefix is taken in one
    * step rather than one character at a time, and what remains is a closed form: nothing here
    * recurses, so no arithmetic in it can be wrong in a way that fails to terminate.
    */
  private def midpoint(lower: String, upper: Option[String]): String = upper match
    case None => above(lower)
    case Some(u) =>
      val shared = commonPrefixLength(lower, u)
      u.take(shared) + within(lower.drop(shared), u.drop(shared))

  /** A fraction strictly between `lower` and `upper`, which differ at their first character. */
  private def within(lower: String, upper: String): String =
    val low = lower.headOption.flatMap(valueOf.get).getOrElse(0)
    upper.headOption.flatMap(valueOf.get) match
      case Some(high) if high - low > 1 => digits.charAt((low + high + 1) / 2).toString
      // Adjacent digits: no single digit fits between them, so the fraction has to grow by one
      // character. Truncating `upper` is the cheaper answer whenever it has something after its
      // first digit; otherwise the fraction extends `lower`, which always has room above it.
      case Some(_) if upper.length > 1 => upper.take(1)
      case _                           => digits.charAt(low).toString + above(lower.drop(1))

  /** A fraction strictly above `lower`, with nothing bounding it from the other side.
    *
    * Every leading `z` has to be kept — there is no room above the last digit — and the first
    * character with room takes the midpoint between it and the top. A `lower` that is all `z`s runs
    * past its own end, where the missing character reads as zero and leaves the whole range.
    */
  private def above(lower: String): String =
    val exhausted = lower.takeWhile(digit => valueOf.get(digit).contains(base - 1))
    val next = lower.lift(exhausted.length).flatMap(valueOf.get).getOrElse(0)
    exhausted + digits.charAt((next + base + 1) / 2).toString

  /** How many leading characters `lower` and `upper` share, reading a missing character as zero.
    *
    * The padding is what makes `midpoint("A", "A2")` see a shared `A` rather than a difference:
    * `"A"` is `"A0…"` read as a fraction, so the recursion goes on to compare `""` against `"2"`.
    */
  private def commonPrefixLength(lower: String, upper: String): Int =
    upper.indices.takeWhile(i => lower.lift(i).getOrElse(zero) == upper.charAt(i)).length
