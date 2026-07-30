package noesis.vocab

import cats.data.NonEmptyList
import noesis.core.capture.Intent
import noesis.journal.{NTriples, Turtle}
import noesis.logic.*

final case class FoafExportOptions(
    includeContactData: Boolean = false,
    includeSocialGraph: Boolean = false
)

/** FOAF import/export as a guarded interoperability projection (SPEC §7.3).
  *
  * FOAF identifiers are preserved as ExternalIdentifier records. They are never translated into
  * `SameIndividual`, even for inverse-functional FOAF properties.
  */
object Foaf:
  val Agent: Iri = Iri("foaf:Agent")
  val Person: Iri = Iri("foaf:Person")
  val Organization: Iri = Iri("foaf:Organization")
  val Group: Iri = Iri("foaf:Group")
  val OnlineAccount: Iri = Iri("foaf:OnlineAccount")
  val name: Iri = Iri("foaf:name")
  val nick: Iri = Iri("foaf:nick")
  val mbox: Iri = Iri("foaf:mbox")
  val phone: Iri = Iri("foaf:phone")
  val account: Iri = Iri("foaf:account")
  val accountName: Iri = Iri("foaf:accountName")
  val accountServiceHomepage: Iri = Iri("foaf:accountServiceHomepage")
  val knows: Iri = Iri("foaf:knows")
  val member: Iri = Iri("foaf:member")
  val birthday: Iri = Iri("foaf:birthday")
  val homepage: Iri = Iri("foaf:homepage")
  val page: Iri = Iri("foaf:page")

  private val imported = AxiomAnnotations(
    truthConfidence = Some(0.7),
    provenance = Provenance(proposedBy = Some("foaf-import"))
  )

  def importIntents(document: String): Either[List[String], List[(Iri, NonEmptyList[Intent])]] =
    parseRdf(document).flatMap(intents)

  def intents(triples: List[Triple]): Either[List[String], List[(Iri, NonEmptyList[Intent])]] =
    val graph = triples.toSet
    val explicitPeople = typed(graph, Person)
    val explicitOrganizations = typed(graph, Organization)
    val relationshipPeople = graph.collect:
      case Triple(subject, property, Node.Ref(obj)) if property == knows => Set(subject, obj)
    .flatten
    val accountOwners = graph.collect:
      case Triple(subject, property, Node.Ref(_)) if property == account => subject
    val groupMembers = graph.collect:
      case Triple(_, property, Node.Ref(obj)) if property == member => obj
    val agents =
      (explicitPeople ++ explicitOrganizations ++ relationshipPeople ++ accountOwners ++ groupMembers)
        .toList
        .distinct
        .sorted
    val localByExternal = agents.map(external => external -> local(external)).toMap

    val contacts = agents.map: external =>
      val contact = localByExternal(external)
      val display =
        data(graph, external, name).headOption
          .orElse(data(graph, external, nick).headOption)
          .getOrElse(external.local)
      val kind =
        if explicitOrganizations.contains(external) then ContactEntityKind.Organization
        else ContactEntityKind.Person
      val base = PrmCapture.contact(ContactInput(contact, display, kind))
      base.flatMap: captured =>
        val alternativeNames = data(graph, external, nick)
          .filterNot(_ == display)
          .distinct
          .flatMap(value =>
            PrmCapture.alternativeName(contact, value).fold(_ => Nil, _.toList)
          )
        val mailboxes = refs(graph, external, mbox).zipWithIndex.map: (mailbox, index) =>
          val value = stripScheme(mailbox.value, "mailto:")
          PrmCapture.method(
            ContactMethodInput(
              PrmIds.child(contact, "email", s"$index\u0000$value"),
              contact,
              ContactKind.Email,
              value
            )
          )
        val phones = refs(graph, external, phone).zipWithIndex.map: (number, index) =>
          val value = stripScheme(number.value, "tel:")
          PrmCapture.method(
            ContactMethodInput(
              PrmIds.child(contact, "phone", s"$index\u0000$value"),
              contact,
              ContactKind.Phone,
              value
            )
          )
        val onlineAccounts = refs(graph, external, account).zipWithIndex.map: (remote, index) =>
          val accountValue =
            data(graph, remote, accountName).headOption.getOrElse(remote.value)
          PrmCapture.method(
            ContactMethodInput(
              PrmIds.child(contact, "account", s"$index\u0000${remote.value}"),
              contact,
              ContactKind.Social,
              accountValue,
              refs(graph, remote, accountServiceHomepage).headOption.map(_.value)
            )
          )
        val methods = mailboxes ++ phones ++ onlineAccounts
        val methodProblems = methods.collect { case Left(found) => found }.flatten
        if methodProblems.nonEmpty then Left(methodProblems)
        else
          val methodIntents = methods.collect { case Right(found) => found.toList }.flatten
          val dates = data(graph, external, birthday).headOption
            .flatMap(raw => PartialDate.parse(raw).toOption)
            .toList
            .map(date =>
              Intent.Assert(
                Axiom.DataAssertion(contact, RelationshipsModule.birthday, Literal.date(date)),
                imported
              )
            )
          val sites =
            (refs(graph, external, homepage) ++ refs(graph, external, page))
              .distinct
              .zipWithIndex
              .flatMap: (site, index) =>
                PrmCapture
                  .method(
                    ContactMethodInput(
                      PrmIds.child(contact, "url", s"$index\u0000${site.value}"),
                      contact,
                      ContactKind.Website,
                      site.value,
                      purpose =
                        Option.when(refs(graph, external, homepage).contains(site))("homepage")
                    )
                  )
                  .fold(_ => Nil, _.toList)
          val known = refs(graph, external, knows).flatMap(localByExternal.get).map(target =>
            Intent.Assert(
              Axiom.ObjectAssertion(contact, RelationshipsModule.knows, target),
              imported
            )
          )
          val identifier = externalIdentifier(contact, external)
          val all =
            captured.toList.map(markImported) ++ alternativeNames.map(markImported) ++
              methodIntents.map(markImported) ++ dates ++ sites.map(markImported) ++ known ++
              identifier
          Right(contact -> NonEmptyList.fromListUnsafe(all))

    val contactProblems = contacts.collect { case Left(found) => found }.flatten
    if contactProblems.nonEmpty then Left(contactProblems)
    else
      val groups = typed(graph, Group).toList.sorted.map: external =>
        val circle = local(external)
        val members = refs(graph, external, member).flatMap(localByExternal.get)
        val assertions = List(
          Intent.Assert(Axiom.ClassAssertion(circle, RelationshipsModule.Circle), imported),
          Intent.Assert(
            Axiom.DataAssertion(
              circle,
              Vocab.label,
              Literal.string(data(graph, external, name).headOption.getOrElse(external.local))
            ),
            imported
          )
        ) ++ members.map(contact =>
          Intent.Assert(Axiom.ObjectAssertion(circle, RelationshipsModule.member, contact), imported)
        )
        circle -> NonEmptyList.fromListUnsafe(assertions)
      Right(contacts.collect { case Right(found) => found } ++ groups)

  def write(
      card: ContactCard,
      socialConnections: List[Iri] = Nil,
      options: FoafExportOptions = FoafExportOptions()
  ): String =
    val classIri = if card.organization then Organization else Person
    val core = List(
      Triple(card.contact, Vocab.rdfType, Node.Ref(classIri)),
      Triple(card.contact, name, Node.Lit(Literal.string(card.displayName)))
    )
    val birth = card.birthday.toList.map: date =>
      val lexical = (date.month, date.day) match
        case (Some(month), Some(day)) => f"$month%02d-$day%02d"
        case _                       => date.render
      Triple(card.contact, birthday, Node.Lit(Literal.string(lexical)))
    val contactData =
      if !options.includeContactData then Nil
      else
        card.methods.flatMap: method =>
          method.kind match
            case "email" =>
              List(
                Triple(
                  card.contact,
                  mbox,
                  Node.Ref(Iri.absolute(s"mailto:${method.value}"))
                )
              )
            case "phone" | "sms" =>
              List(
                Triple(
                  card.contact,
                  phone,
                  Node.Ref(Iri.absolute(s"tel:${Prm.normalizePhone(method.value)}"))
                )
              )
            case "website" =>
              val predicate = if method.purpose.contains("homepage") then homepage else page
              Iri.parse(method.value).toOption.toList.map(site =>
                Triple(card.contact, predicate, Node.Ref(site))
              )
            case "postal" => Nil
            case _ =>
              val accountTriples = List(
                Triple(card.contact, account, Node.Ref(method.id)),
                Triple(method.id, Vocab.rdfType, Node.Ref(OnlineAccount)),
                Triple(method.id, accountName, Node.Lit(Literal.string(method.value)))
              )
              val service = method.label.flatMap(Iri.parse(_).toOption).toList.map(site =>
                Triple(method.id, accountServiceHomepage, Node.Ref(site))
              )
              accountTriples ++ service
    val social =
      if options.includeSocialGraph && !card.organization then
        socialConnections.distinct.map(connection =>
          Triple(card.contact, knows, Node.Ref(connection))
        )
      else Nil
    Turtle.write(core ++ birth ++ contactData ++ social)

  /** Reads N-Triples or the one-triple-per-statement Turtle subset emitted by [[write]]. */
  def parseRdf(document: String): Either[List[String], List[Triple]] =
    if document.linesIterator.exists(_.trim.startsWith("@prefix")) then parseTurtle(document)
    else NTriples.parse(document).left.map(List(_))

  private def parseTurtle(document: String): Either[List[String], List[Triple]] =
    val lines = document.linesIterator.toList
    val prefixes = lines.foldLeft(Namespaces.default.byPrefix): (known, line) =>
      line.trim match
        case Prefix(prefix, namespace) => known.updated(prefix, namespace)
        case _                         => known
    val statements = lines.zipWithIndex.filterNot: (line, _) =>
      val trimmed = line.trim
      trimmed.isEmpty || trimmed.startsWith("@prefix") || trimmed.startsWith("#")
    val parsed = statements.map: (line, index) =>
      expandStatement(line.trim, prefixes)
        .left.map(problem => s"line ${index + 1}: $problem")
        .flatMap(expanded =>
          NTriples
            .parseLine(expanded)
            .left.map(problem => s"line ${index + 1}: $problem")
            .map(_.toList)
        )
    val problems = parsed.collect { case Left(problem) => problem }
    if problems.nonEmpty then Left(problems)
    else Right(parsed.collect { case Right(triples) => triples }.flatten)

  private def expandStatement(
      statement: String,
      prefixes: Map[String, String]
  ): Either[String, String] =
    for
      (subject, afterSubject) <- token(statement, 0)
      (predicate, afterPredicate) <- token(statement, afterSubject)
      (obj, afterObject) <- token(statement, afterPredicate)
      _ <-
        Either.cond(
          statement.drop(afterObject).trim == ".",
          (),
          "only one triple per Turtle statement is supported"
        )
      expandedSubject <- expandIriToken(subject, prefixes)
      expandedPredicate <- expandIriToken(predicate, prefixes)
      expandedObject <-
        if obj.startsWith("\"") then expandLiteralToken(obj, prefixes)
        else expandIriToken(obj, prefixes)
    yield s"$expandedSubject $expandedPredicate $expandedObject ."

  private[vocab] def token(input: String, from: Int): Either[String, (String, Int)] =
    val start = input.indexWhere(!_.isWhitespace, from)
    if start < 0 then Left("unexpected end of statement")
    else if input.charAt(start) == '"' then literalToken(input, start)
    else if input.charAt(start) == '<' then
      input.indexOf('>', start + 1) match
        case -1    => Left("unterminated IRI")
        case close => Right((input.substring(start, close + 1), close + 1))
    else
      val end = input.indexWhere(_.isWhitespace, start) match
        case -1    => input.length
        case found => found
      Right((input.substring(start, end), end))

  private[vocab] def literalToken(input: String, start: Int): Either[String, (String, Int)] =
    def closing(index: Int): Option[Int] =
      if index >= input.length then None
      else
        input.charAt(index) match
          case '"'                            => Some(index)
          case '\\'                            => closing(index + 2)
          case _                              => closing(index + 1)
    closing(start + 1).map: quote =>
      val suffixStart = quote + 1
      val end =
        if input.startsWith("^^", suffixStart) then
          input.indexWhere(_.isWhitespace, suffixStart) match
            case -1    => input.length
            case found => found
        else if input.startsWith("@", suffixStart) then
          input.indexWhere(_.isWhitespace, suffixStart) match
            case -1    => input.length
            case found => found
        else suffixStart
      (input.substring(start, end), end)
    .toRight("unterminated literal")

  private[vocab] def expandIriToken(
      token: String,
      prefixes: Map[String, String]
  ): Either[String, String] =
    if token.startsWith("<") && token.endsWith(">") then Right(token)
    else
      token.split(":", 2).toList match
        case prefix :: localName :: Nil =>
          prefixes
            .get(prefix)
            .map(namespace => s"<$namespace${unescapeLocal(localName)}>")
            .toRight(s"unknown prefix '$prefix'")
        case _ => Left(s"expected an IRI, found '$token'")

  private[vocab] def expandLiteralToken(
      token: String,
      prefixes: Map[String, String]
  ): Either[String, String] =
    token.lastIndexOf("^^") match
      case -1 => Right(token)
      case datatype =>
        expandIriToken(token.drop(datatype + 2), prefixes)
          .map(expanded => token.take(datatype + 2) + expanded)

  private def typed(graph: Set[Triple], cls: Iri): Set[Iri] =
    graph.collect:
      case Triple(subject, property, Node.Ref(found))
          if property == Vocab.rdfType && found == cls =>
        subject

  private def refs(graph: Set[Triple], subject: Iri, property: Iri): List[Iri] =
    graph.collect:
      case Triple(found, predicate, Node.Ref(value))
          if found == subject && predicate == property =>
        value
    .toList
    .sorted

  private def data(graph: Set[Triple], subject: Iri, property: Iri): List[String] =
    graph.collect:
      case Triple(found, predicate, Node.Lit(value))
          if found == subject && predicate == property =>
        value.text
    .toList
    .sorted

  private def local(external: Iri): Iri = PrmIds.record("foaf", external.value)

  private def externalIdentifier(contact: Iri, external: Iri): List[Intent] =
    val identifier = PrmIds.child(contact, "identifier", s"foaf\u0000${external.value}")
    val scheme = Iri("noesis:e/identifier-scheme-foaf-iri")
    List(
      Intent.Assert(Axiom.ClassAssertion(identifier, RelationshipsModule.ExternalIdentifier), imported),
      Intent.Assert(Axiom.ClassAssertion(scheme, RelationshipsModule.IdentifierScheme), imported),
      Intent.Assert(
        Axiom.ObjectAssertion(identifier, RelationshipsModule.identifierFor, contact),
        imported
      ),
      Intent.Assert(
        Axiom.ObjectAssertion(identifier, RelationshipsModule.identifierScheme, scheme),
        imported
      ),
      Intent.Assert(
        Axiom.DataAssertion(
          identifier,
          RelationshipsModule.identifierValue,
          Literal.string(external.value)
        ),
        imported
      )
    )

  private def markImported(intent: Intent): Intent = intent match
    case Intent.Assert(axiom, annotations) =>
      Intent.Assert(
        axiom,
        annotations.copy(
          truthConfidence = Some(0.7),
          provenance = imported.provenance
        )
      )
    case Intent.OpenState(subject, property, value, from, annotations) =>
      Intent.OpenState(
        subject,
        property,
        value,
        from,
        annotations.copy(
          truthConfidence = Some(0.7),
          provenance = imported.provenance
        )
      )
    case other => other

  private[vocab] def stripScheme(value: String, scheme: String): String =
    if value.toLowerCase(java.util.Locale.ROOT).startsWith(scheme) then value.drop(scheme.length)
    else value

  private def unescapeLocal(localName: String): String =
    localName.replaceAll("""\\(.)""", "$1")

  private val Prefix = """@prefix\s+([A-Za-z][A-Za-z0-9_-]*):\s*<([^>]+)>\s*\.""".r
