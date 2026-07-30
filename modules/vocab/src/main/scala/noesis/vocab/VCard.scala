package noesis.vocab

import cats.data.NonEmptyList
import noesis.core.capture.Intent
import noesis.logic.*

final case class VCardField(
    value: String,
    label: Option[String] = None,
    kinds: Set[String] = Set.empty
)

final case class VCardAddress(
    formatted: Option[String],
    street: Option[String],
    extended: Option[String],
    locality: Option[String],
    region: Option[String],
    postalCode: Option[String],
    countryCode: Option[String],
    label: Option[String]
)

final case class VCardContact(
    uid: Option[String],
    formattedName: String,
    structuredName: List[String],
    nicknames: List[String],
    birthday: Option[PartialDate],
    anniversary: Option[PartialDate],
    emails: List[VCardField],
    phones: List[VCardField],
    accounts: List[VCardField],
    urls: List[VCardField],
    addresses: List[VCardAddress],
    organization: Option[String],
    title: Option[String],
    role: Option[String],
    related: List[VCardField],
    note: Option[String],
    categories: List[String]
)

/** vCard 4.0 contact interchange (SPEC §7.3).
  *
  * The supported subset is explicit and round-trippable. Unknown properties are ignored rather
  * than guessed; malformed content lines reject the card before any intent reaches the journal.
  */
object VCard:
  private final case class ContentLine(name: String, params: Map[String, List[String]], value: String)

  def parse(document: String): Either[List[String], List[VCardContact]] =
    val lines = unfold(document)
    val cards = splitCards(lines)
    val framingProblems =
      Option.when(cards.isEmpty)("no BEGIN:VCARD … END:VCARD block found").toList
    if framingProblems.nonEmpty then Left(framingProblems)
    else
      val parsed = cards.zipWithIndex.map: (card, index) =>
        parseCard(card).left.map(_.map(problem => s"card ${index + 1}: $problem"))
      val problems = parsed.collect { case Left(found) => found }.flatten
      if problems.nonEmpty then Left(problems)
      else Right(parsed.collect { case Right(card) => card })

  def importIntents(document: String): Either[List[String], List[(Iri, NonEmptyList[Intent])]] =
    parse(document).flatMap: cards =>
      val converted = cards.zipWithIndex.map: (card, index) =>
        intents(card).left.map(_.map(problem => s"card ${index + 1}: $problem"))
      val problems = converted.collect { case Left(found) => found }.flatten
      if problems.nonEmpty then Left(problems)
      else Right(converted.collect { case Right(value) => value })

  def intents(card: VCardContact): Either[List[String], (Iri, NonEmptyList[Intent])] =
    val contact =
      PrmIds.record("contact", card.uid.getOrElse(s"name:${card.formattedName}"))
    val name = card.structuredName.padTo(5, "")
    val base = PrmCapture.contact(
      ContactInput(
        contact,
        card.formattedName,
        familyName = nonBlank(name(0)),
        givenName = nonBlank(name(1)),
        additionalName = nonBlank(name(2)),
        honorificPrefix = nonBlank(name(3)),
        honorificSuffix = nonBlank(name(4))
      )
    )
    base.flatMap: contactIntents =>
      val birthday = card.birthday.toList.map(date =>
        Intent.Assert(
          Axiom.DataAssertion(contact, RelationshipsModule.birthday, Literal.date(date))
        )
      )
      val methods =
        card.emails.zipWithIndex.map: (field, index) =>
          PrmCapture.method(
            ContactMethodInput(
              PrmIds.child(contact, "email", s"$index\u0000${field.value}"),
              contact,
              ContactKind.Email,
              field.value,
              field.label.orElse(field.kinds.headOption),
              purpose(field.kinds)
            )
          )
        ++ card.phones.zipWithIndex.map: (field, index) =>
          PrmCapture.method(
            ContactMethodInput(
              PrmIds.child(contact, "phone", s"$index\u0000${field.value}"),
              contact,
              ContactKind.Phone,
              field.value,
              field.label.orElse(field.kinds.headOption),
              purpose(field.kinds)
            )
          )
        ++ card.accounts.zipWithIndex.map: (field, index) =>
          PrmCapture.method(
            ContactMethodInput(
              PrmIds.child(contact, "account", s"$index\u0000${field.value}"),
              contact,
              accountKind(field.value),
              field.value,
              field.label.orElse(field.kinds.headOption),
              purpose(field.kinds)
            )
          )
        ++ card.urls.zipWithIndex.map: (field, index) =>
          PrmCapture.method(
            ContactMethodInput(
              PrmIds.child(contact, "url", s"$index\u0000${field.value}"),
              contact,
              ContactKind.Website,
              field.value,
              field.label.orElse(field.kinds.headOption),
              purpose(field.kinds)
            )
          )
      val addresses = card.addresses.zipWithIndex.map: (address, index) =>
        val formatted = address.formatted.getOrElse(
          List(
            address.street,
            address.extended,
            address.locality,
            address.region,
            address.postalCode,
            address.countryCode
          ).flatten.mkString(", ")
        )
        PrmCapture.address(
          PostalAddressInput(
            PrmIds.child(contact, "address", s"$index\u0000$formatted"),
            contact,
            formatted,
            address.street,
            address.extended,
            address.locality,
            address.region,
            address.postalCode,
            address.countryCode,
            address.label
          )
        )
      val alternativeNames = card.nicknames.distinct.map(name =>
        PrmCapture.alternativeName(contact, name)
      )
      val relatedRecords = card.related.zipWithIndex.map: (related, index) =>
        val relatedContact =
          PrmIds.record("contact", s"vcard-related\u0000${related.value}")
        val relationship = PrmIds.child(
          contact,
          "relationship",
          s"$index\u0000${related.value}"
        )
        for
          relatedContactIntents <- PrmCapture.contact(
            ContactInput(relatedContact, related.value)
          )
          relationshipIntents <- PrmCapture.relationship(
            RelationshipInput(
              relationship,
              List(contact, relatedContact),
              related.kinds.headOption.getOrElse("related")
            )
          )
        yield relatedContactIntents.concat(relationshipIntents.toList)
      val circles = card.categories.distinct.map: category =>
        PrmCapture.circle(
          CircleInput(PrmIds.record("circle", category), category, List(contact))
        )
      val anniversaryReminders = card.anniversary.toList.map: date =>
        PrmCapture.reminder(
          ReminderInput(
            PrmIds.child(contact, "reminder", s"anniversary\u0000${date.render}"),
            contact,
            date,
            "anniversary",
            Some("yearly")
          )
        )
      val converted =
        methods ++ addresses ++ alternativeNames ++ relatedRecords ++ circles ++
          anniversaryReminders
      val conversionProblems = converted.collect { case Left(found) => found }.flatten
      if conversionProblems.nonEmpty then Left(conversionProblems)
      else
        val methodIntents =
          converted.collect { case Right(found) => found.toList }.flatten
        val organizationIntents = card.organization.toList.flatMap: name =>
          val organization = PrmIds.record("organization", name)
          val organizationRecord =
            PrmCapture.contact(
              ContactInput(organization, name, ContactEntityKind.Organization, "professional")
            ).fold(_ => Nil, _.toList)
          val employment = PrmCapture.employment(
            EmploymentInput(
              PrmIds.child(contact, "employment", organization.value),
              contact,
              organization,
              card.title.orElse(card.role)
            )
          )
          organizationRecord ++ employment.toList
        val noteIntents = card.note.toList.flatMap: body =>
          PrmCapture
            .note(NoteInput(PrmIds.child(contact, "note", body), contact, body))
            .fold(_ => Nil, _.toList)
        val identifierIntents = card.uid.toList.flatMap: uid =>
          val identifier = PrmIds.child(contact, "identifier", s"vcard\u0000$uid")
          val scheme = Iri("noesis:e/identifier-scheme-vcard-uid")
          List(
            Intent.Assert(Axiom.ClassAssertion(identifier, RelationshipsModule.ExternalIdentifier)),
            Intent.Assert(Axiom.ClassAssertion(scheme, RelationshipsModule.IdentifierScheme)),
            Intent.Assert(
              Axiom.ObjectAssertion(identifier, RelationshipsModule.identifierFor, contact)
            ),
            Intent.Assert(
              Axiom.ObjectAssertion(identifier, RelationshipsModule.identifierScheme, scheme)
            ),
            Intent.Assert(
              Axiom.DataAssertion(
                identifier,
                RelationshipsModule.identifierValue,
                Literal.string(uid)
              )
            )
          )
        Right(
          contact ->
            NonEmptyList.fromListUnsafe(
              contactIntents.toList ++ birthday ++ methodIntents ++ organizationIntents ++
                noteIntents ++ identifierIntents
            )
        )

  def write(card: ContactCard, label: Iri => String): String =
    val properties = List.newBuilder[String]
    properties += "BEGIN:VCARD"
    properties += "VERSION:4.0"
    properties += s"UID:${escape(card.contact.value)}"
    properties += s"FN:${escape(card.displayName)}"
    val structured = card.structuredName.fold(List(card.displayName, "", "", "", "")): name =>
      List(name.family, name.givenName, name.additional, name.prefix, name.suffix)
        .map(_.getOrElse(""))
    properties += s"N:${structured.map(escape).mkString(";")}"
    card.birthday.foreach(date => properties += s"BDAY:${date.render}")
    card.methods.foreach: method =>
      val params = method.label.fold("")(value => s";LABEL=${escapeParam(value)}")
      method.kind match
        case "email" =>
          properties += s"EMAIL$params:${escape(method.value)}"
        case "phone" | "sms" =>
          properties += s"TEL$params:${escape(method.value)}"
        case "postal" =>
          method.address.foreach: address =>
            val adr = List(
              "",
              address.extended.getOrElse(""),
              address.street.getOrElse(""),
              address.locality.getOrElse(""),
              address.region.getOrElse(""),
              address.postalCode.getOrElse(""),
              address.countryCode.getOrElse("")
            ).map(escape).mkString(";")
            val addressParams = address.formatted
              .pipe(value => s";LABEL=${escapeParam(value)}")
            properties += s"ADR$addressParams:$adr"
        case "website" =>
          properties += s"URL$params:${escape(method.value)}"
        case _ =>
          properties += s"IMPP$params:${escape(method.value)}"
    card.employments.foreach: employment =>
      properties += s"ORG:${escape(label(employment.organization))}"
      employment.title.foreach(value => properties += s"TITLE:${escape(value)}")
    foldLines((properties.result() :+ "END:VCARD").mkString("\r\n") + "\r\n")

  private def parseCard(lines: List[String]): Either[List[String], VCardContact] =
    val parsed = lines.map(parseContentLine)
    val problems = parsed.collect { case Left(problem) => problem }
    if problems.nonEmpty then Left(problems)
    else
      val content = parsed.collect { case Right(line) => line }
      val version = values(content, "VERSION").headOption
      val formattedName = values(content, "FN").take(1).mkString
      val required = List.concat(
        Option.when(version != Some("4.0"))("VERSION must be 4.0"),
        Option.when(formattedName.trim.isEmpty)("FN is required")
      )
      if required.nonEmpty then Left(required)
      else
        val addresses = content.filter(_.name == "ADR").map: line =>
          val parts = splitEscaped(line.value, ';').padTo(7, "")
          VCardAddress(
            line.params.get("LABEL").flatMap(_.headOption),
            nonBlank(parts(2)),
            nonBlank(parts(1)),
            nonBlank(parts(3)),
            nonBlank(parts(4)),
            nonBlank(parts(5)),
            nonBlank(parts(6)),
            line.params.get("LABEL").flatMap(_.headOption)
          )
        Right(
          VCardContact(
            values(content, "UID").headOption,
            formattedName,
            rawValues(content, "N").headOption.map(splitEscaped(_, ';')).getOrElse(Nil),
            rawValues(content, "NICKNAME").flatMap(splitEscaped(_, ',')),
            values(content, "BDAY").headOption.flatMap(PartialDate.parse(_).toOption),
            values(content, "ANNIVERSARY").headOption.flatMap(PartialDate.parse(_).toOption),
            fields(content, "EMAIL"),
            fields(content, "TEL"),
            fields(content, "IMPP"),
            fields(content, "URL"),
            addresses,
            rawValues(content, "ORG").headOption.map(splitEscaped(_, ';').mkString(" ")),
            values(content, "TITLE").headOption,
            values(content, "ROLE").headOption,
            fields(content, "RELATED"),
            values(content, "NOTE").headOption,
            rawValues(content, "CATEGORIES").flatMap(splitEscaped(_, ','))
          )
        )

  private def parseContentLine(raw: String): Either[String, ContentLine] =
    splitAtUnescaped(raw, ':').toRight(s"content line has no ':' separator: $raw").map:
      (left, value) =>
        val segments = left.split(';').toList
        val name = segments.take(1).mkString.toUpperCase(java.util.Locale.ROOT)
        val params = segments.drop(1).flatMap: segment =>
          segment.split("=", 2).toList match
            case key :: rawValue :: Nil =>
              Some(
                key.toUpperCase(java.util.Locale.ROOT) ->
                  splitEscaped(rawValue, ',').map(unescape)
              )
            case _ => None
        .toMap
        ContentLine(name, params, value)

  private def fields(content: List[ContentLine], name: String): List[VCardField] =
    content.filter(_.name == name).map: line =>
      VCardField(
        unescape(line.value),
        line.params.get("LABEL").flatMap(_.headOption),
        line.params.getOrElse("TYPE", Nil).map(_.toLowerCase(java.util.Locale.ROOT)).toSet
      )

  private def values(content: List[ContentLine], name: String): List[String] =
    rawValues(content, name).map(unescape)

  private def rawValues(content: List[ContentLine], name: String): List[String] =
    content.filter(_.name == name).map(_.value)

  private def unfold(document: String): List[String] =
    document
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .split('\n')
      .toList
      .foldLeft(List.empty[String]):
        case (previous :: rest, line) if line.startsWith(" ") || line.startsWith("\t") =>
          (previous + line.drop(1)) :: rest
        case (acc, line) => line :: acc
      .reverse
      .filter(_.nonEmpty)

  private def splitCards(lines: List[String]): List[List[String]] =
    val (_, cards) = lines.foldLeft((Option.empty[List[String]], List.empty[List[String]])):
      case ((None, cards), "BEGIN:VCARD") => (Some(Nil), cards)
      case ((Some(current), cards), "END:VCARD") => (None, cards :+ current.reverse)
      case ((Some(current), cards), line) => (Some(line :: current), cards)
      case (state, _) => state
    cards

  private def splitAtUnescaped(value: String, separator: Char): Option[(String, String)] =
    val index = value.indices.find(index =>
      value.charAt(index) == separator && (index == 0 || value.charAt(index - 1) != '\\')
    )
    index.map(i => (value.take(i), value.drop(i + 1)))

  private def splitEscaped(value: String, separator: Char): List[String] =
    val (parts, current, _) = value.foldLeft((List.empty[String], new StringBuilder, false)):
      case ((parts, current, true), char) =>
        (parts, current.append('\\').append(char), false)
      case ((parts, current, false), '\\') => (parts, current, true)
      case ((parts, current, false), char) if char == separator =>
        (parts :+ unescape(current.toString), new StringBuilder, false)
      case ((parts, current, false), char) => (parts, current.append(char), false)
    parts :+ unescape(current.toString)

  private def unescape(value: String): String =
    value
      .replace("\\n", "\n")
      .replace("\\N", "\n")
      .replace("\\,", ",")
      .replace("\\;", ";")
      .replace("\\:", ":")
      .replace("\\\\", "\\")

  private def escape(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\n", "\\n")
      .replace(",", "\\,")
      .replace(";", "\\;")

  private def escapeParam(value: String): String =
    escape(value).replace(":", "\\:")

  private def nonBlank(value: String): Option[String] = Option.when(value.nonEmpty)(value)

  private[vocab] def purpose(kinds: Set[String]): Option[String] =
    kinds.find(kind => kind == "home" || kind == "work")

  private def accountKind(value: String): ContactKind =
    val lower = value.toLowerCase(java.util.Locale.ROOT)
    if lower.contains("signal") then ContactKind.Signal
    else if lower.contains("telegram") then ContactKind.Telegram
    else if lower.contains("matrix") then ContactKind.Matrix
    else ContactKind.Social

  private def foldLines(document: String): String =
    document.split("\r\n", -1).toList.map(foldLine).mkString("\r\n")

  private[vocab] def foldLine(line: String): String =
    val (parts, current, _) = line.codePoints().toArray.foldLeft(
      (List.empty[String], new StringBuilder, 0)
    ):
      case ((parts, current, bytes), codePoint) =>
        val text = Character.toChars(codePoint).mkString
        val size = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        val limit = if parts.isEmpty then 75 else 74
        if bytes + size > limit then
          (parts :+ current.toString, new StringBuilder(text), size)
        else (parts, current.append(text), bytes + size)
    (parts :+ current.toString).mkString("\r\n ")

  extension [A](value: A) private def pipe[B](f: A => B): B = f(value)
