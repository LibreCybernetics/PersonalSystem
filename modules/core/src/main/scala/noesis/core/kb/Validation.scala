package noesis.core.kb

import noesis.core.projection.KbState
import noesis.reasoner.Closure

/** A declarative record-shape check contributed through knowledge-base configuration.
  *
  * Validators inspect the same scratch state and closure used for consistency checking. They run
  * before journal append, so a domain record is never partially persisted (SPEC §3.5.4, §5.1).
  */
trait StateValidator:
  def name: String

  def validate(state: KbState, closure: Closure): List[String]
