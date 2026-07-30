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
  def apply(value: String): Iri = value

  /** Mint an opaque entity IRI. Used for individuals, never for vocabulary terms. */
  def fresh[F[_]: UUIDGen: cats.Functor]: F[Iri] =
    UUIDGen[F].randomUUID.map(uuid => s"noesis:e/$uuid")

  extension (iri: Iri)
    def value: String = iri

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
