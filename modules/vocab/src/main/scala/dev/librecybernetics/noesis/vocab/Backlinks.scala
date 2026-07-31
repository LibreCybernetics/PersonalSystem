package dev.librecybernetics.noesis.vocab

import dev.librecybernetics.noesis.core.projection.KbState
import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.reasoner.Closure

/** Everything written about a thing (SPEC §8.5.2).
  *
  * Backlinks are a **projection of the closure**, not an index maintained alongside the notes.
  * That is the whole argument for turning `[[links]]` into `note:mentions` axioms rather than
  * leaving them as text: an index has to be rebuilt and can be wrong, whereas a projection is
  * recomputed from the journal and cannot disagree with it (§3.2).
  *
  * Reading the closure rather than the asserted axioms is deliberate. A mention reached through a
  * subproperty or any other module-contributed rule counts as a mention, so a module that refines
  * the vocabulary does not have to teach this projection about itself (§5.1).
  *
  * Nothing here filters by sensitivity. §8.5.8 governs what may *leave*, and showing the owner
  * their own notes is not egress; a backlink view that quietly omitted the sensitive paragraphs
  * would be a place for facts to lurk unexamined, which is the outcome that section rejects.
  */
object Backlinks:

  /** One block that mentions the entity, in the note it belongs to. */
  final case class Mention(block: Iri, text: String)

  /** The mentions found in one note, in the order they appear on the page. */
  final case class InNote(note: Iri, title: Option[String], mentions: List[Mention]):
    def count: Int = mentions.length

  /** Every note mentioning `entity`, ordered by title so the list is stable between runs.
    *
    * The count is what an entity page shows first (§8.5.8): fourteen blocks about someone appear
    * as a number with a route to them, because pasting fourteen paragraphs into a contact card has
    * not disclosed more, it has stopped being usable.
    */
  def of(state: KbState, closure: Closure, entity: Iri): List[InNote] =
    val mentioning: Set[Iri] = closure.facts.keySet.collect {
      case Axiom.ObjectAssertion(block, property, mentioned)
          if property == NotesModule.mentions && mentioned == entity =>
        block
    }

    val noteOf = notesByBlock(state)

    mentioning.flatMap(noteOf.get).toList.distinct
      .map(note => Outline.of(state, note))
      .map: outline =>
        InNote(
          note = outline.id,
          title = outline.title,
          mentions = outline.blocks
            .filter(block => mentioning.contains(block.id))
            .map(block => Mention(block.id, block.text))
        )
      .sortBy(entry => (entry.title, entry.note.value))

  /** How many blocks mention the entity, across every note. */
  def total(found: List[InNote]): Int = found.map(_.count).sum

  /** Which note each block belongs to. `note:blockOf` is asserted once and never superseded
    * (§8.5.1), so this is a plain lookup rather than a question about a date.
    */
  private def notesByBlock(state: KbState): Map[Iri, Iri] =
    state.activeAxioms.map(_.axiom).collect {
      case Axiom.ObjectAssertion(block, property, note) if property == NotesModule.blockOf =>
        block -> note
    }.toMap
