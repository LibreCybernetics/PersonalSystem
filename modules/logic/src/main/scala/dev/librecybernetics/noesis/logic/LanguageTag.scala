package dev.librecybernetics.noesis.logic

/** Language tags per BCP 47 (RFC 5646).
  *
  * RDF 1.1 §3.3 requires the language tag of an `rdf:langString` to be well-formed under BCP 47,
  * and SPEC §6 leans on tags much harder than most systems do: `ll:Language` keys the whole
  * polyglot module, and the verbalizer selects templates by tag. A tag that is merely "two letters,
  * maybe a suffix" is not good enough for either.
  *
  * This checks *well-formedness* — that a tag matches the grammar — not *validity*, which would
  * additionally require every subtag to appear in the IANA Language Subtag Registry. The registry
  * changes independently of any release, so validity is a data question, deliberately left out.
  */
object LanguageTag:

  // RFC 5646 §2.1. `language` merges the 4ALPHA and 5*8ALPHA alternatives, which are disjoint from
  // the 2*3ALPHA-with-extlang case by length.
  private val language = """[A-Za-z]{2,3}(?:-[A-Za-z]{3}){0,3}|[A-Za-z]{4,8}"""
  private val script = """(?:-[A-Za-z]{4})?"""
  private val region = """(?:-(?:[A-Za-z]{2}|[0-9]{3}))?"""
  private val variant = """(?:-(?:[0-9A-Za-z]{5,8}|[0-9][0-9A-Za-z]{3}))*"""
  // A singleton is any single alphanumeric except `x`, which introduces the private-use sequence.
  private val extension = """(?:-[0-9A-WYZa-wyz](?:-[0-9A-Za-z]{2,8})+)*"""
  private val privateUse = """(?:-[Xx](?:-[0-9A-Za-z]{1,8})+)?"""
  private val privateOnly = """[Xx](?:-[0-9A-Za-z]{1,8})+"""

  private val grammar =
    s"""(?:(?:$language)$script$region$variant$extension$privateUse)|(?:$privateOnly)"""

  /** Does `tag` match the BCP 47 grammar?
    *
    * The irregular grandfathered tags of RFC 5646 §2.2.8 (`i-klingon`, `en-GB-oed`, …) are not
    * accepted; they are a closed, deprecated list with modern replacements. Recorded as a deviation.
    */
  def isWellFormed(tag: String): Boolean = tag.matches(grammar)

  /** The conventional casing of RFC 5646 §2.1.1: language lowercase, script title case, two-letter
    * regions upper case, everything else left alone. Formatting only — tags compare
    * case-insensitively regardless.
    */
  def canonical(tag: String): String =
    tag.split("-").toList match
      case Nil => tag
      case head :: rest =>
        val subtags = rest.map: subtag =>
          if subtag.length == 4 && subtag.forall(_.isLetter) then
            subtag.take(1).toUpperCase(java.util.Locale.ROOT) +
              subtag.drop(1).toLowerCase(java.util.Locale.ROOT)
          else if subtag.length == 2 && subtag.forall(_.isLetter) then
            subtag.toUpperCase(java.util.Locale.ROOT)
          else subtag.toLowerCase(java.util.Locale.ROOT)
        (head.toLowerCase(java.util.Locale.ROOT) :: subtags).mkString("-")
