package dev.librecybernetics.noesis.vocab

import java.time.LocalDate

import dev.librecybernetics.noesis.core.projection.KbState
import dev.librecybernetics.noesis.logic.*

/** The outline of a note, projected from state (SPEC §8.5.1).
  *
  * A note has no stored structure. Its shape is read back out of the fluents that hold a block's
  * text, its parent and its position — which is what makes `as-of` a projection over a different
  * set of fluents rather than a second code path. [[of]] and [[asOf]] differ in one argument.
  */
object Outline:

  /** One block, with the children it had at the moment being projected. */
  final case class Block(id: Iri, text: String, order: String, children: List[Block]):
    /** This block and everything under it, parents before children. */
    def selfAndDescendants: List[Block] = this :: children.flatMap(_.selfAndDescendants)

  /** A note as it stood, with the blocks that could not be placed in its tree.
    *
    * `detached` is not a failure mode to be silenced. `note:parentBlock` is irreflexive, so no
    * block is its own parent, but the axiom language cannot say that a longer chain is a tree — and
    * a projection that walked one would not terminate. Blocks in a cycle, and blocks whose parent
    * belongs to another note, are placed at the root so that nothing written disappears, and are
    * named here so that a caller can say what happened instead of quietly rearranging the page.
    */
  final case class Note(
      id: Iri,
      title: Option[String],
      roots: List[Block],
      detached: List[Iri]
  ):
    def blocks: List[Block] = roots.flatMap(_.selfAndDescendants)

    /** Each block's parent as the note currently stands, absent for a block at the top level.
      *
      * The tree carries this implicitly; an editor comparing a saved buffer against the note needs
      * it explicitly, to tell a line that was re-indented from one that merely moved.
      */
    def parentOf: Map[Iri, Option[Iri]] =
      def walk(parent: Option[Iri], level: List[Block]): List[(Iri, Option[Iri])] =
        level.flatMap(block => (block.id -> parent) :: walk(Some(block.id), block.children))
      walk(None, roots).toMap

    /** Each block's current text and order key, for the same reason. */
    def stateOf: Map[Iri, (String, String)] =
      blocks.map(block => block.id -> (block.text, block.order)).toMap

  /** The note as it stands now. */
  def of(state: KbState, note: Iri): Note = project(state, note, state.ongoingFluents)

  /** The note as it stood on `date`, from §3.6's machinery and nothing else.
    *
    * A block whose text was superseded shows the wording that held then, in the arrangement that
    * held then, because text, parent and order are all fluents and all answer `heldOn` the same
    * way. Nothing about this is note-specific, which was the point of §8.5.1.
    */
  def asOf(state: KbState, note: Iri, date: LocalDate): Note =
    project(state, note, state.fluentsHeldOn(date))

  private def project(state: KbState, note: Iri, holding: Iterable[Fluent]): Note =
    val members = blocksOf(state, note)
    val values = Values(holding, members)

    // A parent outside the note is no parent here: a block belongs to the note it was asserted
    // into, so an outline never reaches across pages to arrange itself.
    val parentOf = members.view.map(id => id -> values.parent(id).filter(members.contains)).toMap
    val placed = reachable(members, parentOf)

    /** The subtree under `parent`, drawn from `available` and never from what is already placed
      * above it.
      *
      * Descending on what remains rather than on the whole note is what makes this terminate
      * structurally: every level removes the blocks it consumed, so depth is bounded by the number
      * of blocks no matter what `parentOf` says. Termination therefore does not depend on the
      * arrangement being a tree — which the axiom language cannot promise (see `detached`), and
      * which a projection must not assume.
      */
    def childrenOf(parent: Option[Iri], available: Set[Iri]): List[Block] =
      val here = available.filter(id => parentOf.getOrElse(id, None) == parent)
      val remaining = available -- here
      here.toList
        .map(id => Block(id, values.text(id), values.order(id), childrenOf(Some(id), remaining)))
        .sortBy(block => (block.order, block.id.value))

    val detached = members.toList.filterNot(placed.contains).sortBy(_.value)
    val orphans = detached
      .map(id => Block(id, values.text(id), values.order(id), Nil))
      .sortBy(block => (block.order, block.id.value))

    Note(
      id = note,
      title = titleOf(state, note),
      roots = (childrenOf(None, placed) ++ orphans).sortBy(block => (block.order, block.id.value)),
      detached = detached
    )

  /** Blocks asserted into this note.
    *
    * `note:blockOf` is an ordinary axiom rather than a fluent (§8.5.1), so membership is read from
    * the axioms and does not vary with the date being projected. A block that moved to another note
    * is a different block, because everything pointing at it points at it *in* a note.
    */
  private def blocksOf(state: KbState, note: Iri): Set[Iri] =
    state.activeAxioms.map(_.axiom).collect {
      case Axiom.ObjectAssertion(block, property, owner)
          if property == NotesModule.blockOf && owner == note =>
        block
    }.toSet

  private def titleOf(state: KbState, note: Iri): Option[String] =
    state.activeAxioms.map(_.axiom).collectFirst {
      case Axiom.DataAssertion(subject, property, value)
          if subject == note && property == NotesModule.title =>
        value.text
    }

  /** The blocks whose parent chain terminates, so that a cycle cannot be walked forever.
    *
    * Reachability grows outward from the roots rather than following each block's chain upward: a
    * chain walk needs its own visited set per block and arrives at the same answer. One pass per
    * member is more than enough, since each pass admits at least one further level and no outline
    * is deeper than it is large.
    */
  private def reachable(members: Set[Iri], parentOf: Map[Iri, Option[Iri]]): Set[Iri] =
    val roots = members.filter(id => parentOf.getOrElse(id, None).isEmpty)
    val childrenOf = members
      .groupBy(id => parentOf.getOrElse(id, None))
      .collect { case (Some(parent), children) => parent -> children }

    members.foldLeft(roots): (found, _) =>
      found ++ found.flatMap(id => childrenOf.getOrElse(id, Set.empty))

  /** The current value of each block-shaped fluent, read once instead of scanned per block. */
  private final class Values(holding: Iterable[Fluent], members: Set[Iri]):
    private val byProperty: Map[(Iri, Iri), Node] =
      holding
        .filter(fluent => members.contains(fluent.statedSubject))
        .map(fluent => (fluent.statedSubject, fluent.statedProperty) -> fluent.statedValue)
        .toMap

    private def literal(block: Iri, property: Iri): Option[String] =
      byProperty.get((block, property)).collect { case Node.Lit(value) => value.text }

    /** Absent text is the empty block a new line starts as, not a missing one. */
    def text(block: Iri): String = literal(block, NotesModule.text).getOrElse("")

    /** A block with no recorded position sorts last, since every recorded key precedes `~`, which
      * is not a digit of the order alphabet.
      */
    def order(block: Iri): String = literal(block, NotesModule.order).getOrElse("~")

    def parent(block: Iri): Option[Iri] =
      byProperty.get((block, NotesModule.parentBlock)).flatMap(_.asIri)
