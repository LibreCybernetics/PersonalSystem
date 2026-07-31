package dev.librecybernetics.noesis.vocab

import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.core.policy.{ModuleDefaults, PolicyBook, TermPolicy}
import dev.librecybernetics.noesis.core.projection.{AxiomRecord, KbState}
import dev.librecybernetics.noesis.core.verbalize.Templates
import dev.librecybernetics.noesis.lms.{ItemPolicy, ItemPolicyBook}

/** Resources and accounting, on ValueFlows (SPEC §8).
  *
  * ValueFlows' REA distinctions map onto personal needs exactly: ownership vs. custody *is*
  * lending and borrowing; commitments and claims *are* favors. The one alignment axiom `vf:Agent ≡
  * core:Agent` is what makes the Marco holding your drill the same Marco as in §7 — the reason to
  * adopt the vocabulary rather than invent one.
  *
  * The boundary rule from §3.6 applies here: quantitative history is event-sourced, not fluent-based,
  * and balances are a fold over events rather than mutable state.
  */
object ResourcesModule extends Module:
  val prefix = "vf"
  val version = "0.1.0"

  val Agent: Iri = iri("Agent")

  /** A place, as ValueFlows models one: "data that locates something relative to the Earth, usually
    * a somewhat fixed location", extending `geo:SpatialThing` from W3C Basic Geo.
    *
    * Imported rather than invented, for the same reason as `vf:Agent`: §8 already brings this
    * vocabulary in, and a `crm:Place` would be a second name for a thing that already has one.
    * Noesis coins no `vf:` term — the naming register makes that a rule rather than a habit — so
    * the property that *uses* a place lives in `crm:` (see `RelationshipsModule.location`). ValueFlows'
    * own location properties (`currentLocation`, `primaryLocation`, `toLocation`) attach to
    * resources and agents rather than to events, and none of them is imported until §8 needs one.
    */
  val SpatialThing: Iri = iri("SpatialThing")

  val EconomicResource: Iri = iri("EconomicResource")
  val EconomicEvent: Iri = iri("EconomicEvent")
  val ResourceSpecification: Iri = iri("ResourceSpecification")
  val Commitment: Iri = iri("Commitment")
  val Claim: Iri = iri("Claim")
  val Intent: Iri = iri("Intent")
  val Agreement: Iri = iri("Agreement")

  val action: Iri = iri("action")
  val provider: Iri = iri("provider")
  val receiver: Iri = iri("receiver")
  val resourceInventoriedAs: Iri = iri("resourceInventoriedAs")
  val quantity: Iri = iri("quantity")
  val time: Iri = iri("time")
  val primaryAccountable: Iri = iri("primaryAccountable")
  val conformsTo: Iri = iri("conformsTo")
  val fulfilledBy: Iri = iri("fulfilledBy")
  val triggeredBy: Iri = iri("triggeredBy")
  val clarifies: Iri = iri("clarifies")
  val due: Iri = iri("due")
  val balance: Iri = iri("balance")

  /** ValueFlows actions (SPEC §8). */
  object Action:
    val transfer = "transfer"
    val transferCustody = "transfer-custody"
    val transferAllRights = "transfer-all-rights"
    val use = "use"
    val consume = "consume"
    val produce = "produce"
    val work = "work"
    val deliverService = "deliver-service"
    val raise = "raise"
    val lower = "lower"

  /** W3C Basic Geo, which `vf:SpatialThing` extends. Only the three coordinate properties are used;
    * `geo:lat_long` and `geo:location` are not, and are therefore not declared.
    */
  object Geo:
    val SpatialThing: Iri = Iri("geo:SpatialThing")
    val lat: Iri = Iri("geo:lat")
    val long: Iri = Iri("geo:long")
    val alt: Iri = Iri("geo:alt")

  val ontology: List[Axiom] = List(
    // The single alignment axiom: vf:Agent ≡ core:Agent, expressed as mutual subsumption.
    Axiom.SubClassOf(Agent, CoreModule.Agent),
    Axiom.SubClassOf(CoreModule.Agent, Agent),
    // Upstream's own subsumption, restated because Noesis does not fetch the vocabulary at runtime.
    Axiom.SubClassOf(SpatialThing, Geo.SpatialThing),
    // Coordinates are data properties on a place. Declared without a range, which is what makes the
    // CLI type their values as literals rather than as references (see AGENTS.md).
    Axiom.PropertyDomain(Geo.lat, SpatialThing),
    Axiom.PropertyDomain(Geo.long, SpatialThing),
    Axiom.PropertyDomain(Geo.alt, SpatialThing),
    Axiom.PropertyDomain(provider, EconomicEvent),
    Axiom.PropertyRange(provider, Agent),
    Axiom.PropertyDomain(receiver, EconomicEvent),
    Axiom.PropertyRange(receiver, Agent),
    Axiom.PropertyDomain(resourceInventoriedAs, EconomicEvent),
    Axiom.PropertyRange(resourceInventoriedAs, EconomicResource),
    Axiom.PropertyDomain(primaryAccountable, EconomicResource),
    Axiom.PropertyRange(primaryAccountable, Agent),
    Axiom.PropertyDomain(conformsTo, EconomicResource),
    Axiom.PropertyRange(conformsTo, ResourceSpecification),
    Axiom.PropertyDomain(fulfilledBy, Commitment),
    Axiom.PropertyRange(fulfilledBy, EconomicEvent),
    Axiom.PropertyDomain(triggeredBy, Claim),
    Axiom.PropertyRange(triggeredBy, EconomicEvent)
  )

  override val policies: PolicyBook = PolicyBook.empty
    // A ledger is lookup data, so the module weight is low (~0.2) — §8's own number.
    .withModule(ModuleDefaults(prefix, Sensitivity.Personal, utilityWeight = 0.2))
    // Monetary amounts and balances are `sensitive`; §8 notes vf_balances is effectively never
    // disclosed for exactly this reason.
    .withProperty(balance, TermPolicy(sensitivity = Some(Sensitivity.Sensitive)))
    .withProperty(quantity, TermPolicy(escalateTo = Some(Sensitivity.Sensitive)))
    // Coordinates are the sharp end of the place model: a named place is what a briefing needs,
    // and a lat/long is what re-identifies a home. `sensitive` is undisclosable regardless of
    // grants (§3.3), and derived disclosure carries that to anything justified by one.
    .withProperty(Geo.lat, TermPolicy(sensitivity = Some(Sensitivity.Sensitive)))
    .withProperty(Geo.long, TermPolicy(sensitivity = Some(Sensitivity.Sensitive)))
    .withProperty(Geo.alt, TermPolicy(sensitivity = Some(Sensitivity.Sensitive)))
    // Open loans and open favor claims are the exception: medium utility, because they are the
    // things that matter in daily social life and surface in §7 briefings.
    .withProperty(due, TermPolicy.utility(0.55))
    .withClass(Commitment, TermPolicy.utility(0.55))
    .withClass(Claim, TermPolicy.utility(0.55))

  override val itemPolicies: ItemPolicyBook = ItemPolicyBook.empty
    // Ledger entries are not memory material; open obligations are.
    .withClass(EconomicEvent, ItemPolicy.Ignore)
    .withProperty(quantity, ItemPolicy.Ignore)
    .withProperty(balance, ItemPolicy.Ignore)
    .withClass(Commitment, ItemPolicy.DraftForReview)

  override val templates: Templates = Templates.empty
    .withProperty(provider, "{s} was provided by {o}")
    .withProperty(receiver, "{s} was received by {o}")
    .withProperty(primaryAccountable, "{o} is accountable for {s}")
    .withProperty(resourceInventoriedAs, "{s} concerns {o}")
    .withProperty(due, "{s} is due {o}")
    .withProperty(action, "{s} is a {o}")
    .withClass(EconomicResource, "{s} is a resource")
    .withClass(Commitment, "{s} is a promise")
    .withClass(Claim, "{s} is something owed")

/** Ledger projections (SPEC §8).
  *
  * Balances and custody are *folds over the event history*, never mutable state — the same
  * projection principle as §3.2. Storing a balance would make the ledger and the events able to
  * disagree, and the events are the truth.
  */
object Ledger:

  /** One economic event, read out of the graph into a shape a fold can consume. */
  final case class Transfer(
      event: Iri,
      resource: Iri,
      from: Option[Iri],
      to: Option[Iri],
      action: String
  )

  /** A flat index of the assertions in a state, so a ledger fold is a lookup rather than a scan. */
  private final class Facts(state: KbState):
    private val objects: Map[(Iri, Iri), Iri] =
      state.activeAxioms.toList.map(_.axiom).collect { case Axiom.ObjectAssertion(s, p, o) =>
        (s, p) -> o
      }.toMap

    private val data: Map[(Iri, Iri), Literal] =
      state.activeAxioms.toList.map(_.axiom).collect { case Axiom.DataAssertion(s, p, v) =>
        (s, p) -> v
      }.toMap

    /** Economic events in journal order.
      *
      * Custody and balances are folds over event *history* (SPEC §8), so the order events are
      * folded in decides the answer: a return event must land after the lend event it closes.
      * `assertedAt` is that history — journal SPEC §2 makes the sequence number the ordering
      * authority. Sorting is not incidental tidiness here. Without it the fold runs in
      * `Map`-iteration order over axiom identifiers, which is a content hash: stable, but with no
      * relationship to when anything happened.
      */
    val events: List[Iri] =
      state.activeAxioms.toList.collect {
        case AxiomRecord(_, Axiom.ClassAssertion(individual, cls), _, _, at)
            if cls == ResourcesModule.EconomicEvent =>
          at -> individual
      }.sortBy(_._1).map(_._2).distinct

    /** Resources with a recorded accountable party, and who that is. */
    val accountable: List[(Iri, Iri)] =
      state.activeAxioms.toList.map(_.axiom).collect {
        case Axiom.ObjectAssertion(resource, p, agent) if p == ResourcesModule.primaryAccountable =>
          resource -> agent
      }

    def obj(subject: Iri, property: Iri): Option[Iri] = objects.get((subject, property))

    def text(subject: Iri, property: Iri): Option[String] = data.get((subject, property)).map(_.text)

    def number(subject: Iri, property: Iri): Option[BigDecimal] =
      data.get((subject, property)).flatMap(_.asDecimal)

  /** Reads the economic events out of a state. */
  def transfers(state: KbState): List[Transfer] =
    val facts = new Facts(state)
    facts.events.map: event =>
      Transfer(
        event = event,
        resource = facts.obj(event, ResourcesModule.resourceInventoriedAs).getOrElse(event),
        from = facts.obj(event, ResourcesModule.provider),
        to = facts.obj(event, ResourcesModule.receiver),
        action = facts.text(event, ResourcesModule.action).getOrElse("")
      )

  /** The actions that move who is holding a resource. */
  private val custodyActions = Set(
    ResourcesModule.Action.transferCustody,
    ResourcesModule.Action.transfer,
    ResourcesModule.Action.transferAllRights
  )

  /** Current custodian of each resource: the receiver of its most recent custody transfer.
    *
    * Custody is derived rather than stored, which is what makes "out on loan" a query
    * (`primaryAccountable = me ∧ custodian ≠ me`) instead of a flag someone must remember to flip.
    */
  def custody(state: KbState): Map[Iri, Iri] =
    transfers(state)
      .filter(t => custodyActions.contains(t.action))
      .foldLeft(Map.empty[Iri, Iri]): (holders, transfer) =>
        transfer.to.fold(holders)(to => holders.updated(transfer.resource, to))

  /** Resources the owner is accountable for but does not hold — "out on loan" (SPEC §8). */
  def outOnLoan(state: KbState, owner: Iri): List[(Iri, Iri)] =
    val holders = custody(state)
    new Facts(state).accountable.collect:
      case (resource, accountable) if accountable == owner =>
        holders.get(resource).filter(_ != owner).map(resource -> _)
    .flatten

  /** Resources held by the owner but accounted to someone else — borrowed (SPEC §8). */
  def borrowed(state: KbState, owner: Iri): List[(Iri, Iri)] =
    val holders = custody(state)
    new Facts(state).accountable.collect:
      case (resource, accountable) if accountable != owner && holders.get(resource).contains(owner) =>
        resource -> accountable

  /** A resource's quantity as a fold over its raise, lower, produce and consume events (SPEC §8).
    *
    * This is the "balances are a fold" principle applied literally: no stored total exists, so no
    * stored total can drift out of agreement with the events.
    */
  def quantityOf(state: KbState, resource: Iri): BigDecimal =
    val facts = new Facts(state)
    transfers(state)
      .filter(_.resource == resource)
      .foldLeft(BigDecimal(0)): (total, transfer) =>
        val amount = facts.number(transfer.event, ResourcesModule.quantity).getOrElse(BigDecimal(0))
        transfer.action match
          case ResourcesModule.Action.raise | ResourcesModule.Action.produce => total + amount
          case ResourcesModule.Action.lower | ResourcesModule.Action.consume => total - amount
          case _                                                            => total
