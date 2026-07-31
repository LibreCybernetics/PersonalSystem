package dev.librecybernetics.noesis.logic

import cats.Order
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

/** An entity, class or property identifier.
  *
  * Per SPEC §3.1 entity IRIs are opaque UUIDs — names are data (§7.2) — so a rename never breaks a
  * reference. Vocabulary terms (classes, properties) keep readable curie-style IRIs because they are
  * schema, not data, and are what capture and verbalization prompts reason about.
  */
opaque type Iri = String

object Iri:
  /** The IRI denoted by a written name, expanding a bound prefix.
    *
    * `Iri("crm:worksAt")` *is* `Iri("https://noesis.librecybernetics.dev/ns/crm#worksAt")` — compact
    * names are a way of writing an IRI, never a way of storing one. Expanding here rather than at
    * each boundary is what makes "everything stored is an absolute IRI" an invariant instead of a
    * convention: there is one constructor, so there is nowhere to forget.
    *
    * A prefix that is not bound is left alone, so `https://…`, `urn:…` and `mailto:…` pass through
    * untouched. Input from a person or an agent should go through [[parse]] first.
    */
  def apply(value: String): Iri = Namespaces.default.expandName(value).getOrElse(value)

  /** Wraps a value already known to be an absolute IRI, with no expansion attempted. */
  def absolute(value: String): Iri = value

  /** Mint an opaque entity IRI. Used for individuals, never for vocabulary terms. */
  def fresh[F[_]: UUIDGen: cats.Functor]: F[Iri] =
    UUIDGen[F].randomUUID.map(uuid => absolute(s"${Namespaces.base}e/$uuid"))

  /** Characters RFC 3987 excludes from an IRI outright: the gen-delims that must be percent-encoded
    * plus the "unwise" set. Whitespace and controls are rejected separately.
    */
  private val excluded: Set[Char] = Set('<', '>', '"', '{', '}', '|', '^', '`', '\\')

  private val schemeCharacters = "+-."

  /** Validates a written identifier and returns the IRI it denotes, expanding a bound prefix.
    *
    * The syntax check is the same for both spellings — an absolute IRI and a compact name are both
    * `scheme-or-prefix ":" rest`, and RFC 3987 constrains the scheme and the character repertoire
    * identically. Which spelling it was stops mattering once [[apply]] has expanded it.
    */
  def parse(value: String): Either[String, Iri] =
    val colon = value.indexOf(':')
    if value.isEmpty then Left("empty IRI")
    else if colon <= 0 then Left(s"IRI has no scheme or prefix: $value")
    else if value.length == colon + 1 then Left(s"IRI has no local part: $value")
    else
      val scheme = value.take(colon)
      if !scheme.head.isLetter then Left(s"IRI scheme must start with a letter: $value")
      else if !scheme.forall(c => c.isLetterOrDigit || schemeCharacters.contains(c)) then
        Left(s"illegal character in IRI scheme: $value")
      else if value.exists(c => c.isWhitespace || c.isControl) then
        Left(s"whitespace or control character in IRI: $value")
      else
        value.find(excluded.contains) match
          case Some(c) => Left(s"illegal character '$c' in IRI: $value")
          case None    => Right(apply(value))

  extension (iri: Iri)
    def value: String = iri

    /** The prefix this IRI's namespace is bound to, when it is a vocabulary term.
      *
      * `noesis:` is deliberately excluded: minted entities have no readable prefix worth showing,
      * and every caller here is a display path.
      */
    def prefix: Option[String] =
      Namespaces.default.split(iri).map(_._1).filter(_ != "noesis")

    /** The part after the bound namespace — `worksAt`, `e/alice`.
      *
      * Falls back to the segment after the last separator for an IRI in no bound namespace, so an
      * external identifier still renders as something readable rather than in full.
      */
    def local: String =
      Namespaces.default
        .split(iri)
        .map(_._2)
        .getOrElse(iri.substring(iri.lastIndexOf('/').max(iri.lastIndexOf('#')).max(iri.lastIndexOf(':')) + 1))

    /** True for minted entity IRIs, false for vocabulary terms. */
    def isOpaque: Boolean = iri.startsWith(s"${Namespaces.base}e/")

    /** The shortest unambiguous way to write this IRI for a person: its compact name where the
      * namespace is bound, the absolute IRI otherwise.
      *
      * Storage is absolute so that every identifier is an RDF term; that is a decision about
      * correctness, not about what an owner should have to read in a contradiction report or a
      * validation error. Every message that names an IRI goes through here. Serializations do not:
      * for them, abbreviating is a decision about a grammar.
      */
    def display: String = Namespaces.default.compact(iri).getOrElse(iri)

  given Order[Iri] = Order.by(_.value)
  given Ordering[Iri] = Order[Iri].toOrdering
  given Encoder[Iri] = Encoder.encodeString.contramap(_.value)
  given Decoder[Iri] = Decoder.decodeString.map(Iri(_))
  given KeyEncoder[Iri] = KeyEncoder.encodeKeyString.contramap(_.value)
  given KeyDecoder[Iri] = KeyDecoder.decodeKeyString.map(Iri(_))
