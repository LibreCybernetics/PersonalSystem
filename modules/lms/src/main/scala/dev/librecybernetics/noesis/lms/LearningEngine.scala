package dev.librecybernetics.noesis.lms

import java.util.Locale

import cats.effect.{Clock, Ref, Sync}
import cats.syntax.all.*
import dev.librecybernetics.noesis.core.event.Event
import dev.librecybernetics.noesis.core.kb.KnowledgeBase
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.core.policy.{PolicyBook, PolicyCascade}
import dev.librecybernetics.noesis.core.projection.AxiomRecord

/** Per-property item drafting policy (SPEC §4.1).
  *
  * Resolved through the same cascade as annotations, so "quiz me on birthdays, never on phone
  * numbers" is configuration rather than code.
  */
final case class ItemPolicyBook(
    byProperty: Map[Iri, ItemPolicy] = Map.empty,
    byClass: Map[Iri, ItemPolicy] = Map.empty,
    default: ItemPolicy = ItemPolicy.DraftForReview
):
  def withProperty(property: Iri, policy: ItemPolicy): ItemPolicyBook =
    copy(byProperty = byProperty.updated(property, policy))

  def withClass(cls: Iri, policy: ItemPolicy): ItemPolicyBook =
    copy(byClass = byClass.updated(cls, policy))

  def ++(other: ItemPolicyBook): ItemPolicyBook =
    ItemPolicyBook(byProperty ++ other.byProperty, byClass ++ other.byClass, other.default)

  /** The policy for an axiom, property first then class, falling back to the default. */
  def policyFor(axiom: Axiom): ItemPolicy =
    axiom.assertedProperty
      .flatMap(byProperty.get)
      .orElse(axiom.signature.toList.sorted.collectFirst(Function.unlift(byClass.get)))
      .getOrElse(default)

object ItemPolicyBook:
  val empty: ItemPolicyBook = ItemPolicyBook()

/** In-memory store of items, questions and the review log.
  *
  * Items are a *projection* too: they are derived from axioms plus the review log, and SPEC §12.3
  * requires every review be logged from day one so belief parameters can be refit per owner. The
  * reviews are therefore the durable part; the item state is a fold over them.
  */
final class LearningStore[F[_]: Sync] private (
    items: Ref[F, Map[ItemId, Item]],
    questions: Ref[F, Map[ItemId, List[Question]]],
    reviews: Ref[F, Vector[Review]]
):
  def allItems: F[List[Item]] = items.get.map(_.values.toList.sortBy(_.id.value))
  def item(id: ItemId): F[Option[Item]] = items.get.map(_.get(id))
  def put(item: Item): F[Unit] = items.update(_.updated(item.id, item))
  def putAll(fresh: List[Item]): F[Unit] =
    items.update(current => current ++ fresh.map(i => i.id -> i))

  def questionsFor(id: ItemId): F[List[Question]] = questions.get.map(_.getOrElse(id, Nil))
  def putQuestions(id: ItemId, qs: List[Question]): F[Unit] = questions.update(_.updated(id, qs))

  def log(review: Review): F[Unit] = reviews.update(_ :+ review)
  def reviewLog: F[List[Review]] = reviews.get.map(_.toList)

  /** Items referencing a given axiom — used to retire and transform on retraction and change. */
  def itemsFor(axiom: AxiomId): F[List[Item]] =
    items.get.map(_.values.filter(_.axioms.contains(axiom)).toList.sortBy(_.id.value))

object LearningStore:
  def create[F[_]: Sync]: F[LearningStore[F]] =
    (
      Ref.of[F, Map[ItemId, Item]](Map.empty),
      Ref.of[F, Map[ItemId, List[Question]]](Map.empty),
      Ref.of[F, Vector[Review]](Vector.empty)
    ).mapN(new LearningStore[F](_, _, _))

/** The result of grading one review. */
final case class ReviewOutcome(item: Item, review: Review, events: List[Event])

/** The Learning Engine (SPEC §4).
  *
  * It reads the Knowledge Core but never writes to it: belief lives here, truth lives there, and
  * §4.2 is emphatic that the two are never mixed. Items are drafted in reaction to core events, so
  * the core needs no knowledge that a learning engine exists.
  */
final class LearningEngine[F[_]: {Sync, Clock}](
    kb: KnowledgeBase[F],
    store: LearningStore[F],
    itemPolicies: ItemPolicyBook,
    policies: PolicyBook
):

  /** Drafts items for the axioms in a commit, per the policy cascade (SPEC §4.1). */
  def onAxiomAdded(id: AxiomId, axiom: Axiom): F[List[Item]] =
    itemPolicies.policyFor(axiom) match
      case ItemPolicy.Ignore => List.empty[Item].pure[F]
      case policy =>
        for
          verbalizer <- kb.verbalizer
          closure <- kb.closure
          item = Item(
            id = ItemId.of(kindFor(axiom), Set(id)),
            kind = kindFor(axiom),
            axioms = Set(id),
            suspended = policy == ItemPolicy.DraftForReview,
            prompt = verbalizer.verbalize(axiom)
          )
          _ <- store.put(item)
          _ <- store.putQuestions(
            item.id,
            Questions.forAtomicFact(item, axiom, verbalizer, closure.view)
          )
        yield List(item)

  /** Retracting an axiom retires its items (SPEC §4.1). */
  def onAxiomRetracted(id: AxiomId): F[List[Item]] =
    store
      .itemsFor(id)
      .flatMap: affected =>
        val retired = affected.map(_.copy(suspended = true))
        store.putAll(retired).as(retired)

  /** Disputed knowledge is excluded from reasoning and therefore from active learning.
    *
    * Undisputing restores the module's drafting policy rather than blindly activating an item that
    * was originally a draft or ignored (SPEC §3.4, §4.1).
    */
  def onAxiomStatusChanged(id: AxiomId, status: AxiomStatus): F[List[Item]] =
    for
      affected <- store.itemsFor(id)
      state <- kb.state
      suspended = status match
        case AxiomStatus.Disputed | AxiomStatus.Retracted => true
        case AxiomStatus.Active =>
          state
            .axiom(id)
            .forall(record => itemPolicies.policyFor(record.axiom) != ItemPolicy.AutoActivate)
      updated = affected.map(_.copy(suspended = suspended))
      _ <- store.putAll(updated)
    yield updated

  /** Transforms items when a fluent's value changes (SPEC §3.6, §7.2).
    *
    * Two things happen, and both matter: the old value's item becomes historical rather than being
    * deleted (it may retain utility), and a *change* item for the new value is created at elevated
    * priority — because the entrenched old answer will actively interfere, so the change is what
    * needs drilling, not the new fact in isolation.
    */
  def onStateChanged(
      subject: Iri,
      property: Iri,
      previous: Option[Node],
      current: Option[Node]
  ): F[List[Item]] =
    val previousAxiom = previous.map(assertionOf(subject, property, _))
    val currentAxiom = current.map(assertionOf(subject, property, _))

    for
      verbalizer <- kb.verbalizer
      demoted <- previousAxiom.toList.flatTraverse: axiom =>
        store
          .itemsFor(axiom.id)
          .flatMap: affected =>
            val historical = affected.map(_.copy(origin = ItemOrigin.Historical, priorityBoost = 0.0))
            store.putAll(historical).as(historical)

      changeItems <- currentAxiom.toList.flatTraverse: axiom =>
        itemPolicies.policyFor(axiom) match
          case ItemPolicy.Ignore => List.empty[Item].pure[F]
          case policy =>
            val item = Item(
              id = ItemId.of(ItemKind.AtomicFact, Set(axiom.id)),
              kind = ItemKind.AtomicFact,
              axioms = Set(axiom.id),
              origin = ItemOrigin.StateChange,
              suspended = policy == ItemPolicy.DraftForReview,
              priorityBoost = changePriority(property),
              prompt =
                s"${verbalizer.label(subject)} — " +
                  s"${dev.librecybernetics.noesis.core.verbalize.Naming.humanize(property.local)} *now*?"
            )
            store.put(item).as(List(item))
    yield demoted ++ changeItems

  /** Name and pronoun changes get the highest priority: the entrenched old answer is exactly what
    * must be overwritten, and misnaming someone is the failure the system exists to prevent (§7.2).
    */
  private def changePriority(property: Iri): Double =
    val normalized = property.local.toLowerCase(Locale.ROOT)
    if normalized.contains("name") || normalized.contains("pronoun")
    then 1.0
    else 0.4

  /** Applies every event from a commit, so the engine stays a pure function of the core's events. */
  def handle(events: List[Event]): F[List[Item]] =
    events.flatTraverse:
      case Event.AxiomAdded(id, axiom)  => onAxiomAdded(id, axiom)
      case Event.AxiomRetracted(id, _)  => onAxiomRetracted(id)
      case Event.AxiomStatusChanged(id, status) => onAxiomStatusChanged(id, status)
      case Event.StateChanged(_, subject, property, previous, current) =>
        onStateChanged(subject, property, previous, current)
      case _ => List.empty[Item].pure[F]

  // ── Scheduling (SPEC §4.3) ────────────────────────────────────────────────

  /** Utility for an item: the max over its axioms' cascade-resolved recall utility. */
  def utilityOf(records: Map[AxiomId, AxiomRecord])(item: Item): Double =
    val utilities =
      item.axioms.toList.flatMap(records.get).map(PolicyCascade.recallUtility(_, policies))
    utilities.maxOption.getOrElse(0.5)

  /** Records for the cascade to resolve against, covering fluent-backed facts as well as axioms.
    *
    * Materialized fluent triples have no `AxiomRecord` — they are a projection, not journal entries —
    * so without this they would fall back to a neutral 0.5. That would silently mis-rank exactly the
    * properties the spec cares most about, since `worksAt`, `hasName` and `pronouns` are all
    * time-varying (§3.6) *and* the highest-utility facts in §7.4. A name-change item scheduled at 0.5
    * instead of 1.0 is the failure mode §7.2 is written to prevent.
    */
  private def utilityRecords(state: dev.librecybernetics.noesis.core.projection.KbState): Map[AxiomId, AxiomRecord] =
    val fromFluents = state.ongoingFluents.map { fluent =>
      val assertion = fluent.assertion
      assertion.id -> AxiomRecord(
        assertion.id,
        assertion,
        fluent.annotations,
        AxiomStatus.Active,
        assertedAt = 0L
      )
    }.toMap
    fromFluents ++ state.axioms

  def queue(mode: QueueMode = QueueMode.Mixed, limit: Int = 20): F[List[QueueEntry]] =
    for
      now <- Clock[F].realTimeInstant
      items <- store.allItems
      state <- kb.state
    yield Scheduler.queue(items, utilityOf(utilityRecords(state)), now, mode, limit)

  /** The question to ask about a queued item, least-asked first.
    *
    * A question whose source axioms have changed is **regenerated rather than asked** (SPEC §4.1).
    * `sourceHash` exists to make that detectable, and asking anyway would quietly test the owner on
    * a fact that no longer holds — then log the answer as evidence about their memory, which is the
    * one thing §12.3 needs to be able to trust.
    */
  def nextQuestion(entry: QueueEntry): F[Option[Question]] =
    val current = Question.hashOf(entry.item.axioms)
    for
      stored <- store.questionsFor(entry.item.id)
      usable <- stored.filterNot(_.isStale(current)) match
        case Nil if stored.nonEmpty => regenerate(entry.item)
        case fresh                  => fresh.pure[F]
    yield usable.minByOption(_.asked)

  /** Rebuilds an item's questions from the axioms as they now stand. */
  private def regenerate(item: Item): F[List[Question]] =
    for
      verbalizer <- kb.verbalizer
      closure <- kb.closure
      state <- kb.state
      axioms = item.axioms.toList.flatMap(state.axiom(_)).map(_.axiom)
      rebuilt = axioms.flatMap(Questions.forAtomicFact(item, _, verbalizer, closure.view))
      _ <- store.putQuestions(item.id, rebuilt)
    yield rebuilt

  // ── Reviews (SPEC §4.2, §4.6) ─────────────────────────────────────────────

  /** Records a review outcome and updates belief. */
  def review(
      id: ItemId,
      grade: Double,
      latencyMs: Long,
      question: Option[Question] = None
  ): F[Option[ReviewOutcome]] =
    for
      now <- Clock[F].realTimeInstant
      existing <- store.item(id)
      outcome <- existing.traverse: item =>
        val (updated, review) =
          Belief.update(item, grade, latencyMs, question.fold(1.0)(_.discrimination), now)
        val logged = review.copy(question = question.map(_.id))
        for
          _ <- store.put(updated)
          _ <- store.log(logged)
        yield ReviewOutcome(
          updated,
          logged,
          List(
            Event.ReviewCompleted(id.value, grade),
            Event.BeliefUpdated(id.value, updated.belief)
          )
        )
    yield outcome

  /** Grades a free-text response against a question's answer spec, then records it. */
  def answer(question: Question, response: String, latencyMs: Long): F[Option[ReviewOutcome]] =
    question.answer.grade(response) match
      case Some(grade) => review(question.item, grade, latencyMs, Some(question))
      // A rubric answer needs a judge; recording a guessed grade would corrupt the review log that
      // §12.3 depends on, so it is left for the owner to grade.
      case None => Option.empty[ReviewOutcome].pure[F]

  /** Decay-adjusted belief for a set of axioms — the browser's belief overlay (SPEC §4.6). */
  def beliefsFor(axioms: Set[AxiomId]): F[Map[AxiomId, Double]] =
    for
      now <- Clock[F].realTimeInstant
      items <- store.allItems
    yield axioms.toList
      .flatMap: id =>
        items
          .filter(_.axioms.contains(id))
          .map(item => id -> Belief.at(item, now))
      .toMap

  /** Belief in a fact the owner never asserted but could derive (SPEC §4.4). */
  def derivedBelief(axiom: Axiom, config: DerivedBelief.Config = DerivedBelief.Config.default)
      : F[Option[Double]] =
    for
      now <- Clock[F].realTimeInstant
      closure <- kb.closure
      state <- kb.state
      items <- store.allItems
      byAxiom = items.flatMap(item => item.axioms.map(_ -> item)).toMap
    yield DerivedBelief.ofSupports(
      axiom,
      closure,
      {
        case dev.librecybernetics.noesis.reasoner.Support.Asserted(id) =>
          byAxiom.get(id).map(Belief.at(_, now))
        case dev.librecybernetics.noesis.reasoner.Support.FromFluent(id) =>
          state
            .fluent(id)
            .flatMap(fluent => byAxiom.get(fluent.assertion.id))
            .map(Belief.at(_, now))
      },
      config
    )

  def items: F[List[Item]] = store.allItems

  def reviewLog: F[List[Review]] = store.reviewLog

  /** Rebuilds item state from a durable review log (SPEC §4.1, §12.3).
    *
    * Items are a projection of axioms plus reviews, so a process that starts cold — a CLI
    * invocation, a restart — recovers by replaying journal events through [[handle]] and then folding
    * the review log in here. The fold is exact rather than a re-simulation, because each `Review`
    * records the belief and stability it produced; re-running [[Belief.update]] would drift if its
    * parameters were ever refit, which §12.3 explicitly anticipates.
    */
  def restore(reviews: List[Review]): F[Unit] =
    val byItem = reviews.groupBy(_.item)
    store.allItems.flatMap: items =>
      items.traverse_ : item =>
        byItem.get(item.id) match
          case None => Sync[F].unit
          case Some(history) =>
            val ordered = history.sortBy(_.at)
            ordered.lastOption.fold(Sync[F].unit): last =>
              store.put(
                item.copy(
                  belief = last.beliefAfter,
                  stability = last.stabilityAfter,
                  lastReviewed = Some(last.at),
                  reviewCount = ordered.length,
                  lapseCount = ordered.count(_.grade < 0.6)
                )
              )
    *> reviews.traverse_(store.log)

  private def kindFor(axiom: Axiom): ItemKind =
    if axiom.isAssertional then ItemKind.AtomicFact else ItemKind.Concept

  private def assertionOf(subject: Iri, property: Iri, value: Node): Axiom = value match
    case Node.Ref(iri) => Axiom.ObjectAssertion(subject, property, iri)
    case Node.Lit(lit) => Axiom.DataAssertion(subject, property, lit)

object LearningEngine:
  def apply[F[_]: {Sync, Clock}](
      kb: KnowledgeBase[F],
      itemPolicies: ItemPolicyBook = ItemPolicyBook.empty,
      policies: PolicyBook = PolicyBook.empty
  ): F[LearningEngine[F]] =
    LearningStore.create[F].map(new LearningEngine[F](kb, _, itemPolicies, policies))
