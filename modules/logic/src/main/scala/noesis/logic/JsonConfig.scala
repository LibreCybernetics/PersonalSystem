package noesis.logic

import io.circe.derivation.Configuration

/** The single JSON shape used for the journal and every exported structure.
  *
  * SPEC §10 requires the journal be replayable and auditable, so the serialization is deliberately
  * plain: a `type` discriminator on sum types, and defaults honored on decode so adding an
  * optional field never invalidates existing journal lines. A journal line is meant to be readable
  * with `head` and diffable in git, not just machine-parseable — writers drop nulls at the call
  * site to keep lines tidy.
  */
given Configuration =
  Configuration.default.withDiscriminator("type").withDefaults
