package noesis.journal

import noesis.logic.*

/** RDF 1.1 Turtle: the writing half of the journal's serialization duties.
  *
  * Turtle is what SPEC §10 promises for export — "full export anytime, no lock-in" — so the output
  * has to be a document other tools accept, not something that merely resembles one. Two rules do
  * most of the work:
  *
  *   1. Every prefix the document uses is declared, and declared with the namespace it actually
  *      abbreviates. A hand-maintained prefix block drifts from the bindings it claims to mirror,
  *      so the block is generated from [[Namespaces]] and lists exactly the prefixes used.
  *   2. A term is abbreviated only when the abbreviation is a legal `PNAME_LN`. Where the local
  *      part cannot be spelled as one, the absolute IRI is written in angle brackets instead —
  *      longer, always valid, and never a guess.
  */
object Turtle:

  /** Characters `PN_LOCAL_ESC` (Turtle §6.5) permits a backslash to escape inside a local name. */
  private val escapable = Set('_', '~', '.', '-', '!', '$', '&', '\'', '(', ')', '*', '+', ',',
    ';', '=', '/', '?', '#', '@', '%')

  /** The ASCII subset of `PN_CHARS` that needs no escape. Restricted to ASCII on purpose: the full
    * production spans a dozen Unicode ranges, and writing an absolute IRI instead is always
    * correct, so the conservative test costs verbosity rather than validity.
    */
  private def isPlain(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-'

  def write(triples: List[Triple], namespaces: Namespaces = Namespaces.default): String =
    val terms = triples.flatMap: triple =>
      val objectTerm = triple.obj match
        case Node.Ref(iri)     => List(iri)
        case Node.Lit(literal) => List(literal.datatype).filterNot(_ == Xsd.string)
      List(triple.subject, triple.property) ++ objectTerm

    val used = terms.flatMap(iri => namespaces.split(iri).filter((_, local) => abbreviates(local)))
    val prefixes = used
      .map(_._1)
      .distinct
      .sorted
      .flatMap(prefix => namespaces.byPrefix.get(prefix).map(ns => s"@prefix $prefix: <$ns> ."))

    val statements = triples
      .sortBy(t => (t.subject.value, t.property.value, t.obj.render))
      .map(t => s"${term(t.subject, namespaces)} ${term(t.property, namespaces)} ${node(t.obj, namespaces)} .")

    (prefixes ++ List("") ++ statements).mkString("", "\n", "\n")

  /** A term: a prefixed name where one can legally be spelled, an absolute IRI otherwise. */
  def term(iri: Iri, namespaces: Namespaces = Namespaces.default): String =
    namespaces
      .split(iri)
      .collect { case (prefix, local) if abbreviates(local) => s"$prefix:${escapeLocal(local)}" }
      .getOrElse(s"<${iri.value}>")

  private def node(value: Node, namespaces: Namespaces): String = value match
    case Node.Ref(iri)     => term(iri, namespaces)
    case Node.Lit(literal) => RdfTerms.literal(literal, term(_, namespaces))

  /** Can this local part be written as a `PN_LOCAL`?
    *
    * Every character must be either plain or escapable, and the first must not be a `-`, which
    * `PN_LOCAL` admits in later positions only. A trailing `.` is excluded because Turtle would
    * read it as the statement terminator.
    */
  private def abbreviates(local: String): Boolean =
    local.forall(c => isPlain(c) || escapable.contains(c)) &&
      !local.startsWith("-") &&
      !local.endsWith(".")

  private def escapeLocal(local: String): String =
    local.flatMap(c => if isPlain(c) then c.toString else s"\\$c")
