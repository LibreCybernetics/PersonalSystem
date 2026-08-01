package dev.librecybernetics.noesis.cli

import munit.FunSuite

import dev.librecybernetics.noesis.vocab.{Modules, Vocabulary}

/** Confirming before writing, and finding a term (PRODUCT.md US-03, US-04; F2, F1).
  *
  * `Main.accepted` and the vocabulary rendering are the parts of those two stories that are pure
  * enough to pin here; the rest is exercised as a launcher scenario, since what matters about a
  * confirmation prompt is whether the journal grew.
  */
class ConfirmSuite extends FunSuite:

  private val terms = Vocabulary.of(Modules.all)

  test("nothing matching is said plainly, and says how to look"):
    val shown = Render.vocabMatches("zzzz", Nil).mkString("\n")
    assert(shown.contains("no term matches"), shown)
    assert(shown.contains("vocab search"), shown)

  test("a match shows how it reads, what it relates, and what to type"):
    // US-04's acceptance criterion, in the order the owner needs it.
    val found = Vocabulary.search(terms, "spouse")
    val shown = Render.vocabMatches("spouse", found).mkString("\n")
    assert(shown.contains("crm:spouseOf"), shown)
    assert(shown.contains("is married to"), shown)
    assert(shown.contains("domain"), shown)
    assert(shown.contains("range"), shown)
    assert(shown.contains("noesis assert"), shown)

  test("a term with no declared range says so rather than leaving the line out"):
    // Omitting it would read as "this tool does not show ranges" instead of "the vocabulary does
    // not declare one", and an absent range is what makes a value a string instead of a reference.
    val birthday = Vocabulary.find(terms, "crm:birthday").toList
    assert(Render.vocabMatches("birthday", birthday).mkString("\n").contains("(none declared)"))

  test("showing a term gives the defaults the owner cannot otherwise see"):
    val term = Vocabulary.find(terms, "crm:birthday").getOrElse(fail("expected crm:birthday"))
    val shown = Render.vocabTerm(term).mkString("\n")
    assert(shown.contains("sensitivity:"), shown)
    assert(shown.contains("utility:"), shown)
    assert(shown.contains("example:"), shown)

  test("a time-varying term warns that asserting it opens a state"):
    val text = Vocabulary.find(terms, "note:text").getOrElse(fail("expected note:text"))
    assert(Render.vocabTerm(text).mkString("\n").contains("time-varying"))

  test("an escalating term shows the escalation, not only the default"):
    val note = Vocabulary.find(terms, "crm:healthNote").getOrElse(fail("expected crm:healthNote"))
    assert(Render.vocabTerm(note).mkString("\n").contains("escalates"))
