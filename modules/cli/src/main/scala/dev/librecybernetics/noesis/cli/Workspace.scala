package dev.librecybernetics.noesis.cli

/** Compatibility name for the CLI adapter; ownership moved to `noesis-app` (SPEC §2.1). */
type Workspace = dev.librecybernetics.noesis.app.Workspace

object Workspace:
  export dev.librecybernetics.noesis.app.Workspace.*
