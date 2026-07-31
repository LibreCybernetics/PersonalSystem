package dev.librecybernetics.noesis.cli

import scala.util.Random

import dev.librecybernetics.noesis.lms.{AnswerSpec, Question, QuestionFormat, QueueEntry, ReviewOutcome}

/** Asking a question, rather than showing the answer (SPEC §4.1, §4.3; PRODUCT.md US-07, F7).
  *
  * The review loop's whole claim is that the log records *recall*. A loop that displays the fact and
  * asks the owner to score themselves records self-assessment instead, which is not evidence about
  * memory and cannot support the refitting §12.3 depends on. Everything needed to ask properly was
  * already built and tested in `modules/lms` — a typed answer, ontology-grounded distractors,
  * staleness against the source fact — and unreachable.
  *
  * Rendering only. What counts as correct is `AnswerSpec.grade`, and what happens to belief is
  * `Belief.update`; neither is re-decided here.
  */
object Quiz:

  /** Why an entry produced no question, in terms the owner can act on. */
  enum Skipped:
    /** No question was ever generated — the item's kind has no template (SPEC §4.1). */
    case NoQuestion

    /** A rubric answer with no judge. §4.3's grader needs a model, and guessing a grade would put
      * a fabricated number in the review log.
      */
    case NeedsJudge(criteria: String)

    def render: String = this match
      case NoQuestion => "  no question for this item yet — its kind has no template"
      case NeedsJudge(criteria) =>
        s"  needs a rubric judge, which requires a model: $criteria\n" +
          "  answer it yourself with `noesis review <itemId> <grade>`"

  /** The prompt as the owner sees it, with the answer withheld.
    *
    * Choices are shuffled from a seed derived from the question, so that the position of the right
    * answer is stable for one question and unpredictable across questions — a fixed position would
    * be learnable, and a fresh shuffle per asking would make "the third one" mean nothing.
    */
  def ask(index: Int, total: Int, entry: QueueEntry, question: Question): List[String] =
    val heading = s"[$index/$total] ${question.prompt}"

    val choices = question.format match
      case QuestionFormat.MultipleChoice => options(question).zipWithIndex.map((choice, at) =>
          s"  ${('a' + at).toChar}) $choice"
        )
      case _ => Nil

    (heading :: choices) ++ List(f"  (belief ${entry.belief}%.2f · ${entry.reason})")

  /** The choices in their stable shuffled order, correct answer included. */
  def options(question: Question): List[String] =
    val correct = question.answer match
      case AnswerSpec.Exact(value)  => List(value)
      case AnswerSpec.AnyOf(values) => values.toList.sorted.take(1)
      case AnswerSpec.Rubric(_)     => Nil

    Random(question.id.hashCode.toLong).shuffle(correct ++ question.distractors)

  /** Resolves a letter to the choice it names, so a multiple-choice answer can be typed as `b`. */
  def chosen(question: Question, response: String): String =
    val trimmed = response.trim
    val letter = Option.when(trimmed.length == 1)(trimmed.charAt(0) - 'a').filter(_ >= 0)
    question.format match
      case QuestionFormat.MultipleChoice =>
        letter.flatMap(options(question).lift).getOrElse(trimmed)
      case _ => trimmed

  /** What to tell the owner after grading. The right answer is shown either way: being told only
    * "wrong" leaves the review having taught nothing.
    */
  def verdict(question: Question, outcome: ReviewOutcome): List[String] =
    val correct = outcome.review.grade >= 1.0
    val mark = if correct then "correct" else s"not correct — ${expected(question)}"
    List(
      s"  $mark",
      f"  belief ${outcome.review.beliefBefore}%.2f → ${outcome.review.beliefAfter}%.2f, " +
        f"next in ${outcome.review.stabilityAfter}%.1f days"
    )

  private def expected(question: Question): String = question.answer match
    case AnswerSpec.Exact(value)  => value
    case AnswerSpec.AnyOf(values) => values.toList.sorted.mkString(" / ")
    case AnswerSpec.Rubric(criteria) => criteria

  /** Why a question could not be asked, if it could not. */
  def unaskable(question: Option[Question]): Option[Skipped] = question match
    case None                                          => Some(Skipped.NoQuestion)
    case Some(q) =>
      q.answer match
        case AnswerSpec.Rubric(criteria) => Some(Skipped.NeedsJudge(criteria))
        case _                           => None

  def summary(asked: Int, correct: Int, skipped: Int): String =
    val accuracy = if asked == 0 then 0.0 else correct.toDouble / asked
    f"$asked asked, $correct correct (${accuracy * 100}%.0f%%)" +
      (if skipped > 0 then s", $skipped skipped" else "")
