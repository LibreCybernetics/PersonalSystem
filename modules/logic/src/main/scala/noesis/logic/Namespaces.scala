package noesis.logic

/** Bindings from compact-name prefixes to the namespaces they abbreviate.
  *
  * Noesis stores compact names (`crm:worksAt`, `noesis:e/<uuid>`) rather than absolute IRIs. That
  * keeps journal lines legible, CLI arguments typable and axiom identifiers stable, and costs
  * nothing internally because these identifiers are never dereferenced. It does mean the stored
  * value is not by itself an RDF term: RDF 1.1 §3.2 requires an absolute IRI. This map is where
  * that gap is closed — at export, at the MCP boundary, and in conformance tests — rather than by
  * making every value in the system longer.
  */
final case class Namespaces(byPrefix: Map[String, String]):

  def withPrefix(prefix: String, namespace: String): Namespaces =
    Namespaces(byPrefix.updated(prefix, namespace))

  /** Resolves a compact name to its absolute IRI, or `None` if the prefix is not bound. */
  def expand(iri: Iri): Option[Iri] =
    iri.splitCompact.flatMap: (prefix, local) =>
      byPrefix.get(prefix).map(namespace => Iri(namespace + local))

  /** Abbreviates an absolute IRI, preferring the longest matching namespace so that a namespace
    * nested inside another does not lose to it.
    */
  def compact(iri: Iri): Option[Iri] =
    byPrefix.toList
      .filter((_, namespace) => iri.value.startsWith(namespace) && iri.value != namespace)
      .sortBy((_, namespace) => -namespace.length)
      .headOption
      .map((prefix, namespace) => Iri(s"$prefix:${iri.value.drop(namespace.length)}"))

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
