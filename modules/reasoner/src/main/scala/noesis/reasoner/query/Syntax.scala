package noesis.reasoner.query

import noesis.logic.*

/** The textual surface for basic graph patterns.
  *
  * Kept apart from [[Query]] deliberately. Evaluation is defined against the *algebra* —
  * [[BasicGraphPattern]] — and that is the part with a standard behind it: SPARQL 1.1 §5 defines
  * BGP matching, and §18 defines the algebra it evaluates over. This syntax is Noesis's own
  * shorthand and has no standard behind it at all. Separating them means a real SPARQL parser can
  * be put in front of the same evaluator without touching evaluation, and means the SPARQL BGP
  * conformance tests can drive the evaluator directly rather than through a private notation.
  */
object PatternSyntax:

  /** Parses `?x` as a variable, a quoted span as a literal, anything else as an IRI. */
  def term(token: String): Term =
    if token.startsWith("?") then Term.Var(token.drop(1))
    else if token.startsWith("\"") && token.endsWith("\"") && token.length >= 2 then
      Term.Lit(Literal.parse(token.drop(1).dropRight(1)))
    else Term.Ref(Iri(token))

  /** Parses a whitespace-separated, `.`-terminated pattern list:
    * `?p rdf:type crm:Person . ?p crm:worksAt ?org`
    */
  def parse(text: String): Either[String, BasicGraphPattern] =
    val clauses = text.split('.').map(_.trim).filter(_.nonEmpty).toList
    val parsed = clauses.traverseEither: clause =>
      clause.split("\\s+").toList match
        case s :: p :: rest if rest.nonEmpty =>
          Right(Pattern(term(s), term(p), term(rest.mkString(" "))))
        case _ => Left(s"expected 'subject property object', got: $clause")
    parsed.map(BasicGraphPattern.apply)

  extension [A](list: List[A])
    private def traverseEither[E, B](f: A => Either[E, B]): Either[E, List[B]] =
      list.foldRight[Either[E, List[B]]](Right(Nil)): (a, acc) =>
        for
          rest <- acc
          b <- f(a)
        yield b :: rest
