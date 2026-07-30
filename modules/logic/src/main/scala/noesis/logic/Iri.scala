package noesis.logic

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
  /** Wraps a value already known to be well-formed — vocabulary constants and decoded journal
    * content. Input that came from a person or an agent should go through [[parse]] instead.
    */
  def apply(value: String): Iri = value

  /** Mint an opaque entity IRI. Used for individuals, never for vocabulary terms. */
  def fresh[F[_]: UUIDGen: cats.Functor]: F[Iri] =
    UUIDGen[F].randomUUID.map(uuid => s"noesis:e/$uuid")

  /** Characters RFC 3987 excludes from an IRI outright: the gen-delims that must be percent-encoded
    * plus the "unwise" set. Whitespace and controls are rejected separately.
    */
  private val excluded: Set[Char] = Set('<', '>', '"', '{', '}', '|', '^', '`', '\\')

  private val schemeCharacters = "+-."

  /** Validates a written identifier as either an absolute IRI (RFC 3987) or a compact name.
    *
    * Both forms are `scheme-or-prefix ":" rest`, and they are not distinguishable by syntax alone —
    * `crm:worksAt` is a well-formed IRI with scheme `crm` *and* a well-formed compact name. Which
    * one it is depends on whether the prefix is declared, which is [[Namespaces]]'s job, not
    * syntax's. So this checks only what both forms must satisfy.
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
          case None    => Right(value)

  extension (iri: Iri)
    def value: String = iri

    /** Splits at the *first* colon into prefix and local part, the boundary a namespace binding
      * applies at. Distinct from [[prefix]]/[[local]], which serve display and hide `noesis`.
      */
    def splitCompact: Option[(String, String)] =
      val colon = iri.indexOf(':')
      Option.when(colon > 0 && colon < iri.length - 1)((iri.take(colon), iri.drop(colon + 1)))

    /** The `prefix` of `prefix:local`, when the IRI is a vocabulary term. */
    def prefix: Option[String] =
      val i = iri.indexOf(':')
      if i <= 0 then None
      else
        val p = iri.substring(0, i)
        if p == "noesis" then None else Some(p)

    def local: String =
      // `lastIndexOf` returns -1 when no separator exists, so adding one also gives the correct
      // substring origin for an unqualified name without a semantically redundant branch.
      iri.substring(iri.lastIndexOf(':') + 1)

    /** True for minted entity IRIs, false for vocabulary terms. */
    def isOpaque: Boolean = iri.startsWith("noesis:e/")

  given Order[Iri] = Order.by(_.value)
  given Ordering[Iri] = Order[Iri].toOrdering
  given Encoder[Iri] = Encoder.encodeString.contramap(_.value)
  given Decoder[Iri] = Decoder.decodeString.map(Iri(_))
  given KeyEncoder[Iri] = KeyEncoder.encodeKeyString.contramap(_.value)
  given KeyDecoder[Iri] = KeyDecoder.decodeKeyString.map(Iri(_))
