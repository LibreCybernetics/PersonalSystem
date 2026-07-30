package noesis.journal

import noesis.logic.*

/** Term syntax shared by the RDF serializations.
  *
  * N-Triples and Turtle write literals identically and differ only in how they spell a datatype
  * IRI, so the shape lives here once rather than being transcribed twice and drifting.
  */
object RdfTerms:

  /** A literal in the form both grammars share: a quoted lexical form, then *either* a language tag
    * or a datatype, never both, and neither when the datatype is the implicit `xsd:string`.
    */
  def literal(value: Literal, datatypeSyntax: Iri => String): String =
    val quoted = s"\"${escape(value.lexical)}\""
    value.language match
      case Some(tag)                            => s"$quoted@$tag"
      case None if value.datatype == Xsd.string => quoted
      case None                                 => s"$quoted^^${datatypeSyntax(value.datatype)}"

  /** ECHAR escaping (RDF 1.1 Turtle §6.4, N-Triples §6).
    *
    * The reverse solidus has to go first: escaping it after the others would double the backslashes
    * they just introduced.
    */
  def escape(value: String): String =
    value.flatMap:
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c    => c.toString
