package dev.librecybernetics.noesis.cli

import java.time.Instant

import munit.FunSuite

import dev.librecybernetics.noesis.lms.*

/** What the owner sees when asked (SPEC §4.1, PRODUCT.md US-07).
  *
  * The one thing that must hold everywhere here: the answer is not on screen before the owner has
  * answered. A review loop that shows the fact and asks for a self-grade records self-assessment,
  * which is not evidence about memory and cannot support the refitting §12.3 depends on.
  */
class QuizSuite extends FunSuite:

  private val item = ItemId.of(ItemKind.AtomicFact, Set.empty)

  private def question(
      answer: AnswerSpec = AnswerSpec.Exact("--05-12"),
      format: QuestionFormat = QuestionFormat.ShortAnswer,
      distractors: List[String] = Nil,
      id: String = "q1"
  ): Question =
    Question(
      id = id,
      item = item,
      format = format,
      prompt = "Lía García — birthday?",
      answer = answer,
      distractors = distractors,
      sourceHash = "h"
    )

  private def entry(q: Question): QueueEntry =
    QueueEntry(
      item = Item(id = item, kind = ItemKind.AtomicFact, axioms = Set.empty, prompt = q.prompt),
      mode = QueueMode.Retention,
      weight = 1.0,
      belief = 0.5,
      utility = 0.9,
      reason = "belief 0.50 below target 0.92"
    )

  // ── The answer is withheld ────────────────────────────────────────────────

  test("a short-answer question shows the prompt and nothing that gives it away"):
    val q = question()
    val shown = Quiz.ask(1, 3, entry(q), q).mkString("\n")
    assert(shown.contains("Lía García — birthday?"), shown)
    assert(!shown.contains("--05-12"), s"the answer was on screen before it was asked for: $shown")

  test("a multiple-choice question shows the choices, one of which is right"):
    val q = question(format = QuestionFormat.MultipleChoice, distractors = List("--01-01", "--07-04"))
    val shown = Quiz.ask(1, 1, entry(q), q)
    assertEquals(shown.count(_.matches("""^  [a-z]\) .*""")), 3)
    assert(Quiz.options(q).contains("--05-12"), "the right answer has to be among them")

  test("the order of the choices is stable for one question and differs between questions"):
    // A fixed position would be learnable, and a fresh shuffle on every asking would make "the
    // third one" mean nothing when the owner comes back to it.
    val q = question(format = QuestionFormat.MultipleChoice, distractors = List("b", "c", "d", "e"))
    assertEquals(Quiz.options(q), Quiz.options(q))

    val others = (1 to 20).map(n => Quiz.options(q.copy(id = s"q$n")))
    assert(others.distinct.length > 1, "every question laid its answer in the same place")

  // ── Answering ─────────────────────────────────────────────────────────────

  test("a multiple-choice answer may be typed as its letter or written out"):
    val q = question(format = QuestionFormat.MultipleChoice, distractors = List("--01-01"))
    val choices = Quiz.options(q)
    val correctLetter = ('a' + choices.indexOf("--05-12")).toChar.toString
    assertEquals(Quiz.chosen(q, correctLetter), "--05-12")
    assertEquals(Quiz.chosen(q, "--05-12"), "--05-12")
    assertEquals(Quiz.chosen(q, " --05-12 "), "--05-12", "surrounding space is not an answer")

  test("a letter naming no choice is taken as the answer itself, not as a miss"):
    // Otherwise a one-character answer to a multiple-choice question would silently become
    // whichever option that letter happened to index.
    val q = question(format = QuestionFormat.MultipleChoice, distractors = List("--01-01"))
    assertEquals(Quiz.chosen(q, "z"), "z")

  test("a letter is not read as a choice when there are no choices"):
    assertEquals(Quiz.chosen(question(), "b"), "b")

  // ── Verdicts ──────────────────────────────────────────────────────────────

  private def outcome(grade: Double): ReviewOutcome =
    val reviewed = Item(id = item, kind = ItemKind.AtomicFact, axioms = Set.empty, prompt = "p")
    ReviewOutcome(
      reviewed,
      Review(
        item = item,
        question = None,
        at = Instant.EPOCH,
        grade = grade,
        latencyMs = 900,
        beliefBefore = 0.5,
        beliefAfter = if grade >= 1.0 then 0.7 else 0.3,
        stabilityAfter = 2.0
      ),
      Nil
    )

  test("being right says so, and being wrong says what the answer was"):
    // Being told only "wrong" leaves the review having taught nothing.
    val q = question()
    assert(Quiz.verdict(q, outcome(1.0)).mkString.contains("correct"))
    assert(Quiz.verdict(q, outcome(0.0)).mkString.contains("--05-12"), "the answer is owed now")

  test("a set-valued answer shows every accepted form when it was missed"):
    val q = question(answer = AnswerSpec.AnyOf(Set("perro", "can")))
    val shown = Quiz.verdict(q, outcome(0.0)).mkString
    assert(shown.contains("perro") && shown.contains("can"), shown)

  // ── What cannot be asked ──────────────────────────────────────────────────

  test("a rubric answer declines to be graded rather than guessing a number"):
    // §4.3's grader needs a model. A fabricated grade would enter the review log that §12.3 refits
    // the belief parameters from, which is the one place a made-up number does lasting damage.
    val q = question(answer = AnswerSpec.Rubric("names both mechanisms"))
    assertEquals(
      Quiz.unaskable(Some(q)),
      Some(Quiz.Skipped.NeedsJudge("names both mechanisms"))
    )
    assert(Quiz.Skipped.NeedsJudge("x").render.contains("noesis review"), "it says what to do instead")

  test("an item with no question says so instead of being skipped in silence"):
    assertEquals(Quiz.unaskable(None), Some(Quiz.Skipped.NoQuestion))
    assert(Quiz.Skipped.NoQuestion.render.nonEmpty)

  test("an ordinary question is askable"):
    assertEquals(Quiz.unaskable(Some(question())), None)
    assertEquals(Quiz.unaskable(Some(question(answer = AnswerSpec.AnyOf(Set("a"))))), None)

  // ── The session ───────────────────────────────────────────────────────────

  test("the summary counts what was asked, not what was queued"):
    assertEquals(Quiz.summary(4, 3, 0), "4 asked, 3 correct (75%)")
    assertEquals(Quiz.summary(2, 0, 3), "2 asked, 0 correct (0%), 3 skipped")
    assertEquals(Quiz.summary(0, 0, 1), "0 asked, 0 correct (0%), 1 skipped", "and does not divide by zero")
