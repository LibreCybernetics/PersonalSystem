package noesis.cli

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

/** Rendering a stored instant in the zone the owner reads it in (SPEC §3.2, §12.12).
  *
  * Kept out of `noesis-logic` deliberately. A zone is not a property of the fact: the journal
  * records UTC instants because `seq` orders replay and belief decay measures elapsed time, and
  * neither has a zone to be wrong about. Only the *reader* has one, so the conversion belongs at the
  * surface that has a reader — this one.
  *
  * The output is an RFC 3339 timestamp with an offset, which is what
  * [[https://www.rfc-editor.org/rfc/rfc9557 RFC 9557]] calls the base of an IXDTF string. The
  * `[Area/Location]` suffix that would name the zone is not appended: it would repeat what the
  * `--zone` flag already fixed for every line, and an RFC 3339 reader — including this system's own
  * `Instant.parse` — rejects it. Data that must *carry* a zone is a different question, recorded in
  * SPEC §12.12.
  */
object Timestamps:
  private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")

  def show(at: Instant, zone: ZoneId): String = format.format(at.atZone(zone))
