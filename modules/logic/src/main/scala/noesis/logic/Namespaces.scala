package noesis.logic

/** Bindings from compact-name prefixes to the namespaces they abbreviate.
  *
  * Noesis stores **absolute IRIs**. Compact names are an input and display convenience only: they
  * are expanded the moment an `Iri` is constructed and re-abbreviated only for rendering. That is
  * what makes every stored identifier an RDF term in its own right (RDF 1.1 §3.2), so no surface
  * that emits RDF has to remember to expand — and none can forget.
  *
  * The bindings therefore serve three jobs: expansion at construction, abbreviation for display,
  * and the `@prefix` block of a Turtle document.
  */
final case class Namespaces(byPrefix: Map[String, String]):

  def withPrefix(prefix: String, namespace: String): Namespaces =
    Namespaces(byPrefix.updated(prefix, namespace))

  /** Resolves a written compact name to its absolute form, or `None` if the prefix is not bound.
    *
    * Works on the raw string rather than on an [[Iri]] because it runs *during* `Iri` construction,
    * before there is an `Iri` to work on.
    */
  def expandName(value: String): Option[String] =
    // Split at the *first* colon and no further: everything after it is the local part, colons
    // included, so `crm:a:b` abbreviates `…#a:b` rather than failing to find a prefix `crm:a`.
    value.split(":", 2) match
      case Array(prefix, local) if local.nonEmpty => byPrefix.get(prefix).map(_ + local)
      case _                                      => None

  /** Resolves a compact name to its absolute IRI, or `None` if the prefix is not bound. */
  def expand(iri: Iri): Option[Iri] = expandName(iri.value).map(Iri.absolute)

  /** Splits an absolute IRI into the bound prefix and local part that abbreviate it, preferring the
    * longest matching namespace so that a namespace nested inside another does not lose to it.
    */
  def split(iri: Iri): Option[(String, String)] =
    byPrefix.toList
      .filter((_, namespace) => iri.value.startsWith(namespace) && iri.value != namespace)
      .sortBy((_, namespace) => -namespace.length)
      .headOption
      .map((prefix, namespace) => (prefix, iri.value.drop(namespace.length)))

  /** Abbreviates an absolute IRI as `prefix:local`.
    *
    * Returns a *rendering*, not an `Iri`: a compact name is a way of writing an identifier, and
    * wrapping one back up as an `Iri` would immediately re-expand it.
    */
  def compact(iri: Iri): Option[String] = split(iri).map((prefix, local) => s"$prefix:$local")

object Namespaces:
  val rdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  val rdfs = "http://www.w3.org/2000/01/rdf-schema#"
  val xsd = "http://www.w3.org/2001/XMLSchema#"
  val owl = "http://www.w3.org/2002/07/owl#"

  /** Noesis's own namespaces. `noesis:` is where minted entity IRIs live; the rest are the module
    * vocabularies of SPEC §5–§8.
    */
  val base = "https://noesis.librecybernetics.ws/"

  val default: Namespaces = Namespaces(
    Map(
      "rdf" -> rdf,
      "rdfs" -> rdfs,
      "xsd" -> xsd,
      "owl" -> owl,
      "noesis" -> base,
      "core" -> s"${base}ns/core#",
      "crm" -> s"${base}ns/crm#",
      "ll" -> s"${base}ns/ll#",
      "vf" -> s"${base}ns/vf#",
      "ref" -> s"${base}ns/ref#"
    )
  )
