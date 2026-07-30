package noesis.conformance

import scala.io.Source
import scala.util.Using

import io.circe.parser.decode
import io.circe.{Decoder, Json}

/** Where a block of cases comes from, so a failure names the clause it violates rather than just a
  * test index.
  */
final case class Citation(specification: String, clause: String, source: String):
  def cite(id: String): String = s"$specification §$clause [$id] — $source"

object Citation:
  given Decoder[Citation] = Decoder.forProduct3("specification", "clause", "source")(apply)

/** A corpus: provenance plus the cases it authorizes.
  *
  * Cases are data rather than code so that adding coverage means adding a vector, and so that the
  * clause each vector comes from travels with it. `A` is the per-corpus case shape.
  */
final case class Manifest[A](citation: Citation, cases: List[A])

object Manifest:
  given [A: Decoder]: Decoder[Manifest[A]] =
    Decoder.forProduct2[Manifest[A], Citation, List[A]]("provenance", "cases")(Manifest.apply)

  /** Loads a manifest from the test classpath, failing loudly if it is missing or malformed.
    *
    * A conformance suite that silently runs zero cases is worse than one that fails: it reports
    * success for work it never did.
    */
  def load[A: Decoder](resource: String): Manifest[A] =
    val text = Using
      .Manager: use =>
        val stream = Option(getClass.getResourceAsStream(s"/$resource"))
          .getOrElse(sys.error(s"conformance corpus not found on the classpath: $resource"))
        use(Source.fromInputStream(stream, "UTF-8")).mkString
      .fold(err => sys.error(s"could not read $resource: ${err.getMessage}"), identity)

    decode[Manifest[A]](text) match
      case Right(manifest) if manifest.cases.nonEmpty => manifest
      case Right(_)   => sys.error(s"conformance corpus $resource contains no cases")
      case Left(err)  => sys.error(s"conformance corpus $resource is malformed: ${err.getMessage}")

/** One RFC 8785 canonicalization vector: arbitrary JSON in, one exact string out. */
final case class JcsCase(id: String, input: Json, expected: String) derives Decoder

/** One XSD 1.1 Part 2 vector: is `lexical` in `datatype`'s lexical space, and what does it reduce
  * to? `canonical` is absent exactly when `valid` is false.
  */
final case class XsdCase(
    id: String,
    datatype: String,
    lexical: String,
    valid: Boolean,
    canonical: Option[String] = None
) derives Decoder

/** One ISO/IEC 21778 / RFC 8259 vector: is `text` a conforming JSON text? */
final case class JsonSyntaxCase(id: String, text: String, conforming: Boolean) derives Decoder

/** One RFC 7493 vector. Every `text` here is already a conforming JSON text — the question is
  * whether it is also an I-JSON message — so each case names the clause that decides it.
  */
final case class IjsonCase(id: String, text: String, conforming: Boolean, clause: String) derives Decoder

/** One namespace's naming convention, in the form ISO/IEC 11179-5 §2.2.2 requires a conforming
  * system to document it: the six rule kinds of §9.2–§9.7, plus the machine-checkable part of the
  * syntactic and lexical rules as a pattern per role.
  *
  * The prose and the patterns are one artefact rather than two so they cannot drift.
  * `modules/vocab/NAMING.md` explains the choices; this is what the suite enforces.
  */
final case class NamingConvention(
    prefix: String,
    kind: String,
    scope: String,
    authority: String,
    semantic: String,
    syntactic: String,
    lexical: String,
    uniqueness: String,
    classPattern: Option[String] = None,
    propertyPattern: Option[String] = None,
    individualPattern: Option[String] = None,
    datatypePattern: Option[String] = None
) derives Decoder:

  /** The documented rules, by the clause that requires each. A conforming system documents all six;
    * a *strictly* conforming one is the same list with `shall` in place of `should` (§2.2.1–2.2.2).
    */
  def documented: List[(String, String)] = List(
    "9.2 scope" -> scope,
    "9.3 authority" -> authority,
    "9.4 semantic" -> semantic,
    "9.5 syntactic" -> syntactic,
    "9.6 lexical" -> lexical,
    "9.7 uniqueness" -> uniqueness
  )

/** One RFC 3987 / compact-name syntax vector. */
final case class IriCase(id: String, value: String, valid: Boolean) derives Decoder

/** One BCP 47 well-formedness vector, optionally with its conventional casing. */
final case class LanguageTagCase(
    id: String,
    tag: String,
    wellFormed: Boolean,
    canonical: Option[String] = None
) derives Decoder
