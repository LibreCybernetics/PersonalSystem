package dev.librecybernetics.noesis.vocab

import munit.FunSuite

/** Sibling ordering (SPEC §8.5.1, PRODUCT.md PD-08).
  *
  * The property under test is one sentence — the key returned sorts strictly between its
  * neighbours — but it has to keep holding after arbitrary editing, so the cases that earn their
  * place are the ones where a plausible implementation stops holding: the two open ends, repeated
  * insertion into one gap, and every boundary of the integer part, which is where a scheme that
  * counts differs from one that only halves.
  */
class FractionalIndexSuite extends FunSuite:

  private def key(lower: Option[String], upper: Option[String]): String =
    FractionalIndex
      .between(lower, upper)
      .fold(problem => fail(s"no key between $lower and $upper: $problem"), identity)

  /** Generates a key and checks the one property that matters before returning it. */
  private def assertBetween(lower: Option[String], upper: Option[String]): String =
    val result = key(lower, upper)
    lower.foreach(l => assert(l < result, s"$result should sort after $l"))
    upper.foreach(u => assert(result < u, s"$result should sort before $u"))
    result

  private val largestInteger = "z" + "z" * 26
  private val smallestInteger = "A" + "0" * 26

  // ── The property, under each way of editing an outline ────────────────────

  test("the first block of a note gets a key with room on both sides"):
    val only = assertBetween(None, None)
    val above = assertBetween(Some(only), None)
    val below = assertBetween(None, Some(only))
    assertEquals(List(below, only, above).sorted, List(below, only, above))

  test("appending stays ordered however many times it happens"):
    val keys = List.iterate(assertBetween(None, None), 2000)(k => assertBetween(Some(k), None))
    assertEquals(keys.sorted, keys, "appended keys must already be in order")
    assertEquals(keys.distinct.length, keys.length, "every block needs its own position")

  test("prepending stays ordered however many times it happens"):
    val keys = List.iterate(assertBetween(None, None), 2000)(k => assertBetween(None, Some(k)))
    assertEquals(keys.sorted, keys.reverse, "each prepended key sorts before the last")
    assertEquals(keys.distinct.length, keys.length)

  test("inserting into the same gap repeatedly keeps splitting it"):
    // A thousand times in a row, someone adds a line directly under the same one. Integer
    // positions would renumber the note on every one of them.
    val low = assertBetween(None, None)
    val high = assertBetween(Some(low), None)
    val inserted = List.iterate(high, 1000)(upper => assertBetween(Some(low), Some(upper)))
    assertEquals(inserted.distinct.length, inserted.length)
    assert(inserted.forall(k => low < k && k <= high))

  // ── What the integer part is for (PD-08) ──────────────────────────────────

  test("appending and prepending do not grow keys, which is the point of counting"):
    // Measured before the integer part existed: a plain fractional midpoint reached 201 characters
    // after a thousand appends, because halving the remaining space is all it can do. Appending is
    // what a dated page is made of, so it is the case that must stay flat.
    def lengthAfter(n: Int)(step: String => String): Int =
      List.iterate(assertBetween(None, None), n + 1)(step).lastOption.map(_.length).getOrElse(0)

    val appended = lengthAfter(2000)(k => assertBetween(Some(k), None))
    val prepended = lengthAfter(2000)(k => assertBetween(None, Some(k)))
    assert(appended <= 4, s"two thousand appends grew the key to $appended characters")
    assert(prepended <= 4, s"two thousand prepends grew the key to $prepended characters")

  test("growth is confined to repeated insertion into one gap, where it is unavoidable"):
    // No scheme that refuses to renumber can keep this flat: each insertion has to name a position
    // strictly inside the last one. What matters is that it stays proportional to insertions into
    // *that gap* rather than to the size of the note.
    val low = assertBetween(None, None)
    val grown = List
      .iterate(assertBetween(Some(low), None), 1000)(upper => assertBetween(Some(low), Some(upper)))
      .lastOption
      .getOrElse(fail("expected a thousandth key"))
    assert(grown.length < 200, s"a key grew to ${grown.length} characters")

  test("counting up past the end of a body lengthens the integer part"):
    // 'a' heads a two-character integer part, so "az" is the last of its length and the next key
    // has to widen rather than wrap.
    assertEquals(assertBetween(Some("az"), None), "b00")
    assertEquals(assertBetween(None, Some("b00")), "az", "and widening is reversible")

  test("counting crosses between the negative and positive ranges exactly once"):
    // "Zz" is the last negative integer and "a0" the first non-negative one; they are adjacent.
    assertEquals(assertBetween(Some("Zz"), None), "a0")
    assertEquals(assertBetween(None, Some("a0")), "Zz")

  test("counting down past the start of a body lengthens the integer part the other way"):
    // A longer negative number is a smaller one, so its head moves earlier in the alphabet.
    assertEquals(assertBetween(None, Some("Z0")), "Yzz")
    assertEquals(assertBetween(Some("Yzz"), None), "Z0", "and that too is reversible")

  test("widening and narrowing keep the body the length the head promises"):
    // The head is the only thing saying where an integer part ends, so a body that disagrees with
    // it produces a key that reads as a different number entirely. `X` promises three body digits.
    assertEquals(assertBetween(Some("Xzzz"), None), "Y00")
    assertEquals(assertBetween(None, Some("Y00")), "Xzzz")
    assertEquals(assertBetween(Some("czzz"), None), "d0000")
    assertEquals(assertBetween(None, Some("d0000")), "czzz")

  test("the fraction takes over when the integer part can count no higher"):
    val beyond = assertBetween(Some(largestInteger), None)
    assert(beyond.startsWith(largestInteger), beyond)
    assert(beyond.length > largestInteger.length, "the fraction is where the room is now")

  test("the fraction takes over when the integer part can count no lower"):
    val within = assertBetween(None, Some(smallestInteger + "V"))
    assert(within.startsWith(smallestInteger), within)

  test("below the smallest key there is nothing, and that is said rather than guessed"):
    assertEquals(
      FractionalIndex.between(None, Some(smallestInteger)),
      Left(FractionalIndex.Problem.Exhausted(smallestInteger))
    )

  test("a gap between two adjacent integers is answered by the fraction"):
    // "a0" and "a1" are consecutive, so nothing between them can be an integer part alone.
    val inner = assertBetween(Some("a0"), Some("a1"))
    assert(inner.startsWith("a0"), inner)

  test("a gap wide enough for a whole integer is answered by one, not by a fraction"):
    assertEquals(assertBetween(Some("a0"), Some("a5")), "a1")

  // ── Inside the fraction ───────────────────────────────────────────────────
  //
  // Two keys sharing an integer part reduce to a question about their fractions alone, which is the
  // only way to reach these paths now that counting handles the ends. The expected values are
  // written out rather than merely checked for order: several of the branches below produce a
  // correctly-ordered key from the wrong reasoning, and only the exact answer separates them.

  private def fractionBetween(lower: String, upper: String): String =
    assertBetween(Some(s"a0$lower"), Some(s"a0$upper")).drop(2)

  test("a fraction with room to spare is answered by the digit in the middle of it"):
    // Half-up, so the answer leans toward the upper bound rather than the lower.
    assertEquals(fractionBetween("1", "4"), "3")
    assertEquals(fractionBetween("1", "z"), "V")

  test("two adjacent digits push the answer into another character"):
    // Nothing sits between "1" and "2", so the answer descends into "1…" instead.
    assertEquals(fractionBetween("1", "2"), "1V")

  test("when the upper fraction has more to give, the answer borrows its first digit"):
    // "2" is below "21" and above "1", so there is no need to descend at all.
    assertEquals(fractionBetween("1", "21"), "2")

  test("descending carries the whole lower fraction, not just its head"):
    // The recursion continues below "1", so what matters is what follows the 1 in the lower bound.
    assertEquals(fractionBetween("15", "2"), "1Y")
    assertEquals(fractionBetween("11", "2"), "1W")

  test("a shared prefix is kept and the question moves to what follows it"):
    assertEquals(fractionBetween("V1", "V4"), "V3")
    assertEquals(fractionBetween("VV1", "VV4"), "VV3")

  test("a missing character reads as zero, so a short fraction is inside its own gap"):
    // "V" is "V0…" as a fraction, which is why the answer stays under "V" rather than leaving it.
    assertEquals(fractionBetween("V", "V4"), "V2")

  test("the fraction below the smallest integer is measured from nothing, not from a guess"):
    assertEquals(assertBetween(None, Some(smallestInteger + "V")), smallestInteger + "G")

  // ── Refusals ──────────────────────────────────────────────────────────────

  test("neighbours out of order are refused rather than resolved arbitrarily"):
    assertEquals(
      FractionalIndex.between(Some("a5"), Some("a1")),
      Left(FractionalIndex.Problem.OutOfOrder("a5", "a1"))
    )
    assertEquals(
      FractionalIndex.between(Some("a1"), Some("a1")),
      Left(FractionalIndex.Problem.OutOfOrder("a1", "a1")),
      "two siblings cannot share a position"
    )

  test("a key this object could not have produced is refused"):
    val cases = List(
      "" -> "the empty string heads nothing",
      "V" -> "the head promises six characters and only one is here",
      "a-" -> "a hyphen has no place in the order",
      "a0V0" -> "a fraction ending in zero has no room below it",
      "00" -> "a digit is not a head",
      "[0" -> "a character between the two head ranges heads neither"
    )
    cases.foreach: (bad, why) =>
      assertEquals(
        FractionalIndex.between(Some(bad), None),
        Left(FractionalIndex.Problem.NotAKey(bad)),
        why
      )
      assertEquals(
        FractionalIndex.between(None, Some(bad)),
        Left(FractionalIndex.Problem.NotAKey(bad)),
        s"$why (as an upper bound)"
      )

  // ── Self-consistency ──────────────────────────────────────────────────────

  test("a produced key is always a legal neighbour for the next insertion"):
    // Every key the generator emits has to be one it will accept back. A fraction ending in zero
    // would break that, and is what the recursion is arranged to avoid.
    val appended = List.iterate(assertBetween(None, None), 300)(k => assertBetween(Some(k), None))
    val prepended = List.iterate(assertBetween(None, None), 300)(k => assertBetween(None, Some(k)))
    val low = assertBetween(None, None)
    val split = List.iterate(assertBetween(Some(low), None), 300)(u => assertBetween(Some(low), Some(u)))

    (appended ++ prepended ++ split).foreach: k =>
      assert(
        FractionalIndex.between(Some(k), None).isRight,
        s"the generator produced a key it will not accept: $k"
      )

  test("an outline built by inserting anywhere reads back in the order it was built"):
    // The end-to-end claim: arbitrary insertions, then a plain string sort, and the result is the
    // sequence the editor showed. Nothing carries a comparator alongside the keys.
    val ordered = (1 to 400).foldLeft(List(assertBetween(None, None))): (keys, n) =>
      val at = n % (keys.length + 1)
      val (before, after) = keys.splitAt(at)
      val fresh = assertBetween(before.lastOption, after.headOption)
      before ++ List(fresh) ++ after

    assertEquals(ordered.sorted, ordered, "a string sort must reproduce the outline")
    assertEquals(ordered.distinct.length, ordered.length)
