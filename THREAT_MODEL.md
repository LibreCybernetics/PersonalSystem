# Noesis threat model

**Scope:** the current single-user CLI, local workspace, archive workflow, interchange exporters and
isolated coding-agent tooling. HTTP, MCP, synchronization, hosted inference and calendar
integration are not implemented; adding any of them changes the trust boundaries and requires this
model to be revised before release.

## Security objectives and assets

| Asset | Required properties |
|---|---|
| Knowledge journal | Confidentiality; append-only integrity; complete, deterministic replay; durable atomic commits |
| Review log | Confidentiality and integrity; stable association with the learning item reviewed |
| Effective annotations and provenance | Integrity and completeness, because they govern disclosure, belief and contradiction explanations |
| Derived views and exports | No undisclosed facts, names, contact data or provenance; explicit indication when reasoning is incomplete |
| Archives | Complete, inspectable, tamper-detectable copies; verified restoration without overwriting live data |
| Owner and agent credentials | Isolation from unrelated processes and tasks; least authority; revocability where the external provider supports it |
| Source tree and build inputs | Integrity and reproducibility; no unreviewed agent changes or undeclared dependencies |

Loss of confidentiality can expose relationships, contact methods, notes, beliefs and other
sensitive personal data. Loss of integrity can be equally harmful: a forged assertion, review,
annotation or truncated provenance can change what Noesis discloses, believes or teaches.

## Actors and assumptions

- The owner controls the local account and chooses workspace, archive and export destinations.
- Ordinary local applications running as other OS users are untrusted.
- Other processes running as the owner are not contained by filesystem permissions; malware or a
  compromised same-user process can read and modify the workspace.
- Import documents, journal/archive bytes, CLI arguments and future model output are untrusted
  input.
- Export recipients and coding agents are untrusted beyond their explicitly granted data and
  capabilities.
- The OS kernel, JVM, Nix store and pinned build toolchain are trusted dependencies. A hostile
  kernel or privileged administrator is outside the current protection model.

## Trust boundaries and controls

### Local persistence

The workspace boundary is the owner-only directory containing `journal.jsonl` and `reviews.jsonl`.
Opening it rejects symlinked persistence paths, requires regular files, and tightens POSIX modes to
`0700` for the directory and `0600` for files. This removes dependence on the process umask and
limits accidental access by other users.

Journal commits use a JVM lock and an OS file lock, store each bundle in one versioned frame with a
SHA-256 checksum, validate contiguous sequences and content-derived axiom identifiers, and fsync
before returning. An incomplete final frame is removed on open; corruption in a complete frame
fails closed. Knowledge-base append is conditional on the durable sequence used for validation, so
another writer forces the whole semantic pre-flight to run again.

The review log uses the same private-file, symlink, locking and fsync controls, but remains a plain
JSON Lines stream. It does not yet have checksummed frames.

### Reasoning and disclosure

Raw state, journal and closure projections are package-internal capabilities. Generic consumers use
`DisclosureView`, created from a restricted state and closure with restricted naming and
justifications. Exporters receive the same restricted `ExportContext`; vCard contact data and FOAF
social edges require explicit options. A permitted fact cannot borrow a label from a separate
undisclosed naming fact.

Unresolvable provenance is classified as `sensitive`. A justification removed by a resource cap is
represented by an incomplete marker, disclosure ignores it, consistency pre-flight rejects an
incomplete closure, and query/entailment/explanation return a typed incomplete result.

### Archive and restore

Archive capture holds the journal and review-log locks together. The transparent directory contains
both raw logs, a current Turtle projection, and a canonical manifest with format identifiers,
lengths and SHA-256 checksums. Verification validates both logs, replays the journal and recomputes
the Turtle projection. Restore verifies first and writes only to a path that does not already exist.
Archive files are owner-only on POSIX filesystems.

Checksums detect accidental or unkeyed modification; they do not authenticate an archive against an
attacker who can rewrite both payloads and manifest.

### Imports, capture and learning

Structured capture validates IRIs, annotation ranges, module constraints and consistency before an
atomic append. Import identity matches remain candidates rather than automatic identity merges.
Review grades must be finite and within `[0,1]`, and latency cannot be negative.

Learning and question identifiers use versioned, length-delimited SHA-256 inputs, avoiding JVM hash
collisions and ambiguous concatenation. This is identity stability, not a secret or authorization
mechanism.

### Isolated coding agents

Agent sessions receive a synthetic repository for one selected revision, provider-specific
credentials, a private filesystem, bounded resources and an allowlisted HTTPS proxy. The host
checkout, history, home directory, default Noesis workspace and unrelated credentials are not
mounted. Results cross back only through an explicit diff export and apply step.

This boundary is process isolation over the host kernel, not a virtual machine. An allowed model or
package endpoint is an intentional egress path. See
[DESIGN.md](DESIGN.md#isolated-agent-security-model) for operational details.

## Principal threats and disposition

| Threat | Current disposition |
|---|---|
| Another local user reads personal data | Reduced by automatic owner-only directory/file modes |
| Symlink redirects a persistence or archive write | Rejected for workspace files, archive payloads and target parents |
| Two processes validate and append conflicting facts | Conditional sequence append forces semantic revalidation; file locks serialize frames |
| Crash leaves half of an accepted multi-operation commit | One checksummed frame per commit; incomplete final fragment is discarded |
| Complete journal line is modified or reordered | Checksums, sequence validation and assertion-ID validation fail closed |
| Provenance cap makes a private derivation appear public | Incomplete marker; disclosure ignores it and reports incomplete reasoning |
| Export leaks a hidden name, contact method or social edge | Restricted state/naming context; contact and social fields are opt-in |
| Archive is incomplete or altered | Length/checksum validation, journal/review parsing, replay and projection comparison |
| Restore destroys current data | Restore refuses an existing destination |
| Hash collisions merge unrelated learning items | Versioned length-delimited SHA-256 identifiers |
| Malicious import creates identity or semantic corruption | Candidate-based identity matching and pre-commit validation |
| Coding agent reads host secrets or arbitrary network | Mount isolation, separate credentials and destination allowlist |

## Residual risks and required follow-up

- Same-user malware and privileged host actors can read or rewrite workspace data.
- Workspace and archives are not encrypted at rest. Device encryption and protected backups remain
  deployment responsibilities; synchronization and end-to-end encryption are unimplemented.
- Journal/archive SHA-256 values are not keyed signatures. Authenticity against a writer with
  filesystem access requires an external signed or keyed root.
- The plain review log lacks per-record checksums and crash-tail recovery. A malformed tail fails
  startup rather than silently recovering.
- File locks and POSIX permissions depend on filesystem and platform semantics. Non-POSIX stores do
  not receive an equivalent ACL hardening guarantee.
- The naive reasoner has bounded resources and does not meet the production performance target.
  Incompleteness is explicit, but denial of service through adversarially expensive valid input
  remains possible.
- CLI owner access is inherited from the OS account; there is no application login, audit trail for
  reads, or protection from shoulder surfing and terminal history.
- Scala visibility and restricted context types constrain ordinary integrations but are not a
  sandbox. Arbitrary code loaded into the same JVM, reflection, or code deliberately placed in the
  `dev.librecybernetics.noesis` package must be treated as trusted application code.
- Archive creation leaves an incomplete new directory if the process fails before writing the
  manifest. Such a directory fails verification and must not be treated as a valid backup.
- No scheduled snapshot, retention policy or automatic restore drill exists.
- The future HTTP/MCP/sync surfaces still require authentication, authorization, token storage,
  revocation, rate limiting, request-size limits, transport security, audit policy and a revised
  data-flow analysis.
- **The local model process of SPEC §8.5.7 is an unanalyzed boundary until it is built.** It is a
  separate process that receives prompt context and may log or retain it, and the model behind it can
  be replaced without Noesis observing the change. The specified controls — assembling context from a
  `DisclosureView` so `sensitive` never enters a prompt, configuring no remote provider, and
  recording the model and digest with every proposal — are design commitments, not implemented ones.
  Two of them need this document rewritten rather than extended when the module lands: loopback TCP
  is reachable by **any** local process, which a Unix socket's file permissions would prevent
  (deferred only because [ollama/ollama#8072](https://github.com/ollama/ollama/pull/8072) is
  unmerged); and reading sessions add source text as a *transient* asset whose required property is
  that it never becomes a durable one.

## Review triggers

Revisit this document when adding a persistence format, network listener, synchronization, remote
model call, credential, new exporter/importer, background process, filesystem capability, agent
mount or allowlisted network destination. Security-relevant implementation changes must update this
document and their current-state operational documentation in the same change.
