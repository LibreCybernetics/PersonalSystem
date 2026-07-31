package dev.librecybernetics.noesis.vocab

import java.util.Locale
import scala.util.matching.Regex

import dev.librecybernetics.noesis.core.capture.Intent
import dev.librecybernetics.noesis.core.verbalize.NamingContext
import dev.librecybernetics.noesis.logic.*

/** `[[wiki links]]` in block text, and what they denote (SPEC §8.5.2).
  *
  * A confirmed link becomes a `note:mentions` axiom, which is why "everything I have written about
  * Lía" is a graph-pattern query rather than a text search, and why backlinks are a projection of
  * the closure rather than an index that can fall out of date.
  *
  * **Resolution never mints an entity.** An unmatched link is a clarification prompt (§3.5.3), and
  * an ambiguous one is a choice; neither is resolved by picking something. This is the same rule
  * F4 records as broken in `Workspace.iri`, which maps any bare token to an entity that may not
  * exist — writing prose is exactly where that mistake would be cheapest to make and hardest to
  * notice, since nothing in a sentence looks like an identifier.
  *
  * Matching is by **current** name (§7.2), so a link written before a rename resolves to the same
  * person afterwards. Former names default to `sensitive` and never reach the naming context, so
  * they cannot resolve here either — recovering them is a deliberate act, not a side effect of
  * typing an old name into a note.
  */
object NoteLinks:

  /** A `[[…]]` span, with where it sits in the text it came from.
    *
    * The offsets are kept because a proposal has to be shown in context — "which Lía did you
    * mean, in this sentence" — and because a later confirmation step needs to point at the span
    * rather than at the whole block.
    */
  final case class Link(name: String, start: Int, end: Int)

  /** What a link denotes, once the current names have been consulted. */
  enum Resolution:
    case Resolved(link: Link, entity: Iri)

    /** More than one entity currently goes by this name. Presented as a choice, never guessed:
      * two people really can share a name, and picking the older record would be wrong half the
      * time and silent every time.
      */
    case Ambiguous(link: Link, candidates: List[Iri])

    /** Nothing goes by this name. The prompt asks; it does not create. */
    case Unresolved(link: Link)

    def link: Link

  /** The spans, in the order they appear.
    *
    * A link runs to the first `]]` after it, so an unclosed `[[` is text rather than the start of
    * a link that swallows the rest of the paragraph. An empty `[[]]` names nothing and is not a
    * link at all.
    */
  def parse(text: String): List[Link] =
    NoteLinks.pattern
      .findAllMatchIn(text)
      .map(found => Link(found.group(1).trim, found.start, found.end))
      .filter(_.name.nonEmpty)
      .toList

  private val pattern: Regex = """\[\[([^\[\]]*)\]\]""".r

  /** Resolves each link against the entities that currently go by a name.
    *
    * `naming.labels` holds exactly the named entities — an entity with no name is absent rather
    * than present under a placeholder — so nothing resolves to a handle the owner never chose.
    */
  def resolve(naming: NamingContext, links: List[Link]): List[Resolution] =
    val byName: Map[String, List[Iri]] =
      naming.labels.toList
        .groupMap((_, label) => normalize(label))((entity, _) => entity)
        .view
        .mapValues(_.sortBy(_.value))
        .toMap

    links.map: link =>
      byName.getOrElse(normalize(link.name), Nil) match
        case Nil            => Resolution.Unresolved(link)
        case single :: Nil  => Resolution.Resolved(link, single)
        case many           => Resolution.Ambiguous(link, many)

  /** The mentions to commit: the resolved links, and nothing else.
    *
    * Ambiguous and unresolved links contribute no axiom, so a block whose links were never
    * answered is still written down — losing the sentence would be the worse failure — and the
    * questions outlive the commit rather than being resolved by it.
    */
  def mentions(block: Iri, resolutions: List[Resolution]): List[Intent] =
    resolutions
      .collect { case Resolution.Resolved(_, entity) => entity }
      .distinct
      .map(entity => Intent.Assert(Axiom.ObjectAssertion(block, NotesModule.mentions, entity)))

  /** The links still owed an answer, for the clarification prompt. */
  def unanswered(resolutions: List[Resolution]): List[Resolution] =
    resolutions.filter:
      case Resolution.Resolved(_, _) => false
      case _                         => true

  /** Names match on case and spacing rather than exactly, since a link is typed by a person and
    * `[[lía garcía]]` means the same as `[[Lía García]]`.
    */
  private def normalize(name: String): String =
    name.trim.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
