package noesis.lms

import munit.FunSuite
import noesis.core.verbalize.{NamingContext, Verbalizer}
import noesis.logic.*
import noesis.reasoner.{ClosureView, Justification}

/** Template question generation (SPEC §4.1).
  *
  * The claim under test is that a formal KB can ask real questions without a model in the loop: the
  * prompts, the answer specs and the distractors all have to come out of the ontology. Prompts are
  * pinned verbatim because they are what the owner actually sees.
  */
class QuestionsSuite extends FunSuite:

  private val lia = Iri("noesis:e/lia")
  private val sarah = Iri("noesis:e/sarah")
  private val molina = Iri("noesis:e/molina")
  private val acme = Iri("noesis:e/acme")

  private val Person = Iri("crm:Person")
  private val Agent = Iri("crm:Agent")
  private val Organization = Iri("crm:Organization")
  private val birthday = Iri("crm:birthday")
  private val worksAt = Iri("crm:worksAt")

  private val verbalizer = new Verbalizer(
    NamingContext(
      Map(
        lia -> "Lía",
        sarah -> "Sarah",
        molina -> "Molina Labs",
        acme -> "Acme",
        Person -> "person",
        Agent -> "agent"
      )
    )
  )

  private val noOntology = new ClosureView(Map.empty)

  private def viewOf(axioms: Axiom*): ClosureView =
    new ClosureView(axioms.map(a => a -> Set(Justification.asserted(a.id))).toMap)

  private def itemFor(axiom: Axiom): Item =
    Item(
      id = ItemId.of(ItemKind.AtomicFact, Set(axiom.id)),
      kind = ItemKind.AtomicFact,
      axioms = Set(axiom.id)
    )

  // ── Atomic facts, asked both ways (SPEC §4.1) ─────────────────────────────

  test("a data assertion is asked in both directions"):
    // Recalling Lía's birthday and recalling whose birthday it is are different skills, so one
    // assertion owes the owner two questions.
    val axiom = Axiom.DataAssertion(lia, birthday, Literal.string("05-12"))
    val item = itemFor(axiom)
    val questions = Questions.forAtomicFact(item, axiom, verbalizer, noOntology)

    assertEquals(questions.map(_.id), List(s"${item.id.value}:fwd", s"${item.id.value}:rev"))
    assertEquals(
      questions.map(_.prompt),
      List("Lía — birthday?", "whose birthday is 05-12?")
    )
    assertEquals(
      questions.map(_.answer),
      List(AnswerSpec.Exact("05-12"), AnswerSpec.Exact("Lía")): List[AnswerSpec]
    )
    assertEquals(questions.map(_.format), List.fill(2)(QuestionFormat.ShortAnswer))
    assert(questions.forall(_.sourceHash == Question.hashOf(item.axioms)), "both carry the source")

  test("an object assertion is asked both ways and names entities, never IRIs"):
    val axiom = Axiom.ObjectAssertion(lia, worksAt, molina)
    val item = itemFor(axiom)
    val questions = Questions.forAtomicFact(item, axiom, verbalizer, noOntology)

    assertEquals(questions.map(_.id), List(s"${item.id.value}:fwd", s"${item.id.value}:rev"))
    assertEquals(
      questions.map(_.prompt),
      List("Lía — works at?", "who works at Molina Labs?")
    )
    assertEquals(
      questions.map(_.answer),
      List(AnswerSpec.Exact("Molina Labs"), AnswerSpec.Exact("Lía")): List[AnswerSpec]
    )

  test("without ontology siblings there is nothing to choose between, so the question stays open"):
    val axiom = Axiom.ObjectAssertion(lia, worksAt, molina)
    val questions = Questions.forAtomicFact(itemFor(axiom), axiom, verbalizer, noOntology)

    assertEquals(questions.map(_.format), List.fill(2)(QuestionFormat.ShortAnswer))
    assertEquals(questions.flatMap(_.distractors), Nil)

  test("an object assertion with plausible confusions becomes multiple choice"):
    val axiom = Axiom.ObjectAssertion(lia, worksAt, molina)
    val view = viewOf(
      Axiom.ClassAssertion(molina, Organization),
      Axiom.ClassAssertion(acme, Organization)
    )
    val questions = Questions.forAtomicFact(itemFor(axiom), axiom, verbalizer, view)

    assertEquals(
      questions.map(_.format),
      List(QuestionFormat.MultipleChoice, QuestionFormat.ShortAnswer),
      "only the forward direction has options to offer"
    )
    assertEquals(questions.flatMap(_.distractors), List("Acme"))

  test("a class assertion asks what kind of thing the individual is"):
    val axiom = Axiom.ClassAssertion(lia, Person)
    val item = itemFor(axiom)
    val questions = Questions.forAtomicFact(item, axiom, verbalizer, noOntology)

    assertEquals(questions.map(_.id), List(s"${item.id.value}:type"))
    assertEquals(questions.map(_.prompt), List("what kind of thing is Lía?"))
    assertEquals(questions.map(_.answer), List(AnswerSpec.Exact("Person")): List[AnswerSpec])

  test("a schema axiom falls back to a cloze over its verbalization"):
    // SPEC §4.3 leaves cases for COMPOSITE and CONCEPT items to a future LLM; until then a blank
    // over the rendered axiom is the honest question, graded by rubric rather than guessed at.
    val axiom = Axiom.SubClassOf(Person, Agent)
    val item = itemFor(axiom)
    val questions = Questions.forAtomicFact(item, axiom, verbalizer, noOntology)
    val question = questions.headOption.getOrElse(fail("expected one concept question"))

    assertEquals(questions.length, 1)
    assertEquals(question.id, s"${item.id.value}:concept")
    assertEquals(question.format, QuestionFormat.Cloze)
    assertEquals(question.prompt, "every person is an ___")
    assertEquals(question.answer, AnswerSpec.Rubric("every person is an agent"): AnswerSpec)

  // ── Cloze construction ────────────────────────────────────────────────────

  test("a cloze blanks the last word and keeps the rest of the sentence intact"):
    assertEquals(Questions.cloze("every person is an agent"), "every person is an ___")
    assertEquals(Questions.cloze("knows implies likes"), "knows implies ___", "three words suffice")

  test("a verbalization too short to blank asks for the whole sentence back"):
    // Blanking the only content word of a two-word sentence leaves nothing to recall it from, so
    // the fallback asks for the sentence rather than emitting an unanswerable prompt.
    assertEquals(Questions.cloze("knows implies"), "knows implies — fill in the blank")
    assertEquals(Questions.cloze("agent"), "agent — fill in the blank")

  // ── Distractors (SPEC §4.1) ───────────────────────────────────────────────

  test("distractors are ontology siblings and same-property values, never the answer itself"):
    // A random entity would give the answer away; a sibling organization is a confusion the review
    // genuinely tests.
    val view = viewOf(
      Axiom.ClassAssertion(molina, Organization),
      Axiom.ClassAssertion(acme, Organization),
      Axiom.ObjectAssertion(sarah, worksAt, Iri("noesis:e/other"))
    )
    val distractors = Questions.ontologyDistractors(molina, worksAt, view)

    assert(!distractors.contains(molina), "offering the answer as a distractor gives it away")
    assertEquals(distractors.toSet, Set(acme, Iri("noesis:e/other")))

  test("distractors are capped so a multiple-choice question stays answerable"):
    val instances = (1 to 6).map(i => Axiom.ClassAssertion(Iri(s"noesis:e/org$i"), Organization))
    val distractors =
      Questions.ontologyDistractors(Iri("noesis:e/org1"), worksAt, viewOf(instances*), limit = 3)

    assertEquals(distractors.length, 3)
    assert(!distractors.contains(Iri("noesis:e/org1")))
