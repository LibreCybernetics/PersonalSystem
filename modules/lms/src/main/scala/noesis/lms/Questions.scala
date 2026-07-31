package noesis.lms

import noesis.logic.*
import noesis.reasoner.ClosureView
import noesis.core.verbalize.{Naming, Verbalizer}

/** Question generation (SPEC §4.1).
  *
  * The generators are templates over the formal representation, not prompts — an MVP without an LLM
  * can still ask real questions because the KB knows what the subject, property and value are. What
  * an LLM adds later (§4.3) is *cases* for COMPOSITE and CONCEPT items, not the atomic questions.
  */
object Questions:

  /** Questions for an atomic fact, asked in both directions (SPEC §4.1).
    *
    * Bidirectionality matters: recognizing that Lía's birthday is 12 May is not the same skill as
    * recalling whose birthday 12 May is, and §6 makes the same point per direction for languages.
    */
  def forAtomicFact(
      item: Item,
      axiom: Axiom,
      verbalizer: Verbalizer,
      view: ClosureView
  ): List[Question] =
    val hash = Question.hashOf(item.axioms)

    axiom match
      case Axiom.DataAssertion(subject, property, value) =>

        val forward = Question(
          id = s"${item.id.value}:fwd",
          item = item.id,
          format = QuestionFormat.ShortAnswer,
          prompt = s"${label(verbalizer, subject)} — ${Naming.humanize(property.local)}?",
          answer = AnswerSpec.Exact(value.text),
          sourceHash = hash
        )
        val reverse = Question(
          id = s"${item.id.value}:rev",
          item = item.id,
          format = QuestionFormat.ShortAnswer,
          prompt = s"whose ${Naming.humanize(property.local)} is ${value.text}?",
          answer = AnswerSpec.Exact(label(verbalizer, subject)),
          sourceHash = hash
        )

        List(forward, reverse)

      case Axiom.ObjectAssertion(subject, property, obj) =>
        val distractors = ontologyDistractors(obj, property, view)
        List(
          Question(
            id = s"${item.id.value}:fwd",
            item = item.id,
            format =
              if distractors.nonEmpty then QuestionFormat.MultipleChoice else QuestionFormat.ShortAnswer,
            prompt = s"${label(verbalizer, subject)} — ${Naming.humanize(property.local)}?",
            answer = AnswerSpec.Exact(label(verbalizer, obj)),
            distractors = distractors.map(label(verbalizer, _)),
            sourceHash = hash
          ),
          Question(
            id = s"${item.id.value}:rev",
            item = item.id,
            format = QuestionFormat.ShortAnswer,
            prompt = s"who ${Naming.humanize(property.local)} ${label(verbalizer, obj)}?",
            answer = AnswerSpec.Exact(label(verbalizer, subject)),
            sourceHash = hash
          )
        )

      case Axiom.ClassAssertion(individual, cls) =>
        List(
          Question(
            id = s"${item.id.value}:type",
            item = item.id,
            format = QuestionFormat.ShortAnswer,
            prompt = s"what kind of thing is ${label(verbalizer, individual)}?",
            answer = AnswerSpec.Exact(cls.local),
            sourceHash = hash
          )
        )

      case other =>
        // Schema axioms are CONCEPT material; a cloze over the verbalization is the honest fallback
        // until an LLM can compose a case for them (SPEC §4.3).
        List(
          Question(
            id = s"${item.id.value}:concept",
            item = item.id,
            format = QuestionFormat.Cloze,
            prompt = cloze(verbalizer.verbalize(other)),
            answer = AnswerSpec.Rubric(verbalizer.verbalize(other)),
            sourceHash = hash
          )
        )

  /** Ontology-grounded distractors (SPEC §4.1).
    *
    * Siblings under the same class and same-property values of similar individuals — plausible and
    * diagnostic *because* the KB is formal. A random other entity would be a giveaway; a sibling
    * organization is a real confusion the review actually tests.
    */
  def ontologyDistractors(
      correct: Iri,
      property: Iri,
      view: ClosureView,
      limit: Int = 3
  ): List[Iri] =
    val classes = view.classesOf.getOrElse(correct, Nil).map(_._1).toSet

    val siblings = classes.toList
      .flatMap(cls => view.instancesOf.getOrElse(cls, Nil).map(_._1))
      .filterNot(_ == correct)

    val sameProperty = view.objectByProperty
      .getOrElse(property, Nil)
      .map(_._2)
      .filterNot(_ == correct)

    (siblings ++ sameProperty).distinct.sortBy(_.value).take(limit)

  /** Turns a verbalization into a cloze by blanking its last significant word.
    *
    * Visible to the module so its short-text guard can be tested directly: every axiom form the
    * verbalizer renders today is at least three words long, so the fallback is unreachable through
    * [[forAtomicFact]] — and unreachable defensive code is exactly the kind that rots.
    */
  private[lms] def cloze(text: String): String =
    val words = text.split(' ').toList
    if words.length < 3 then s"$text — fill in the blank"
    else (words.dropRight(1) :+ "___").mkString(" ")

  private def label(verbalizer: Verbalizer, iri: Iri): String = verbalizer.label(iri)
