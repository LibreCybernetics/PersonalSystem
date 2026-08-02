# Noesis design

This document records the principles and constraints of the current implementation. It complements
[SPEC.md](SPEC.md), which is the authority on intended design and deliberately describes more than
the MVP implements. A disagreement between implementation and spec is a finding rather than an
implicit reason to change either one. [README.md](README.md) describes the current product and its
operation; [TESTING.md](TESTING.md) describes its verification practices. [PRODUCT.md](PRODUCT.md)
records who the system serves and which journeys it must support, and [UX.md](UX.md) records how the
owner-facing surface behaves; the principles below constrain both, and where a friction can only be
removed by weakening an invariant here, the invariant wins.

## Architecture

```
logic  ← journal
  ↑
reasoner

logic + journal + reasoner  ← core  ← lms  ← vocab  ← app  ← cli
                                                        ↖ gui
```

Dependencies point one way:

- `logic` is the persisted semantic language and depends on no Noesis module.
- `journal` and `reasoner` depend only on `logic`; neither knows about application policy.
- `core` composes those foundations. It knows nothing about learning, vocabulary modules or an owner
  surface
  and never imports from them.
- `lms` reads the Knowledge Core and reacts to its events; it never writes to the KB.
- `lms` remains independent of domain vocabularies.
- `vocab` declares vocabulary as data. A module is a value implementing `Module`, not a plugin with
  lifecycle hooks.
- `app` assembles workspace replay, learning restoration and presentation-neutral owner use cases.
  It depends on the full domain but on no toolkit or command parser.
- `cli` and `gui` are sibling adapters. The CLI retains specialist/audit operations; the GUI covers
  J16's daily loop. Neither invokes the other or owns a second persistence lifecycle.

Modules extend the system through declarative seams: `Rule` for inference; `PolicyBook` and
`ItemPolicyBook` for annotation and item defaults; `Templates` and `Naming.Scheme` for
verbalization; `StateValidator` for pre-commit record validation; and document importers,
document exporters and agenda producers for application projections. A module-specific branch
inside `core` indicates that a generic seam is missing.

Key implementation points:

| Concern | File |
|---|---|
| Axiom language | `modules/logic/src/main/scala/dev/librecybernetics/noesis/logic/Axiom.scala` |
| Journal operations | `modules/journal/src/main/scala/dev/librecybernetics/noesis/journal/Operation.scala`, `Journal.scala` |
| Coordinated log snapshots | `modules/journal/src/main/scala/dev/librecybernetics/noesis/journal/JournalArchive.scala` |
| Journal → state fold | `modules/core/src/main/scala/dev/librecybernetics/noesis/core/projection/KbState.scala` |
| State projections | `modules/core/src/main/scala/dev/librecybernetics/noesis/core/projection/Projections.scala` |
| Reasoner graph | `modules/reasoner/src/main/scala/dev/librecybernetics/noesis/reasoner/Graph.scala` |
| Inference rules | `modules/reasoner/src/main/scala/dev/librecybernetics/noesis/reasoner/Rule.scala` (+ module rules in `vocab/`) |
| Fixpoint, closure | `modules/reasoner/src/main/scala/dev/librecybernetics/noesis/reasoner/Reasoner.scala` |
| Consistency, EL profile | `modules/reasoner/src/main/scala/dev/librecybernetics/noesis/reasoner/Consistency.scala` |
| Annotation cascade | `modules/core/src/main/scala/dev/librecybernetics/noesis/core/policy/Policy.scala` |
| Disclosure rule | `modules/core/src/main/scala/dev/librecybernetics/noesis/core/policy/Disclosure.scala` |
| Intent → operations | `modules/core/src/main/scala/dev/librecybernetics/noesis/core/capture/Capture.scala` |
| Pre-commit validation | `modules/core/src/main/scala/dev/librecybernetics/noesis/core/kb/Validation.scala` |
| Interchange and agenda seams | `modules/core/src/main/scala/dev/librecybernetics/noesis/core/module/Extensions.scala` |
| Service surface | `modules/core/src/main/scala/dev/librecybernetics/noesis/core/kb/KnowledgeBase.scala` |
| Shared owner session | `modules/app/src/main/scala/dev/librecybernetics/noesis/app/OwnerSession.scala`, `Workspace.scala` |
| Desktop reducer and effect loop | `modules/gui/src/main/scala/dev/librecybernetics/noesis/gui/Model.scala`, `ReactiveController.scala` |
| GTK/libadwaita renderer | `modules/gui/src/main/scala/dev/librecybernetics/noesis/gui/DesktopView.scala` |
| Portable archive workflow | `modules/cli/src/main/scala/dev/librecybernetics/noesis/cli/Archive.scala` |
| Command-surface derivation | `modules/cli/src/main/scala/dev/librecybernetics/noesis/cli/meta/CommandSurface.scala` |
| Belief, derived belief | `modules/lms/src/main/scala/dev/librecybernetics/noesis/lms/Belief.scala` |
| Scheduling | `modules/lms/src/main/scala/dev/librecybernetics/noesis/lms/Scheduler.scala` |
| Module contract | `modules/vocab/src/main/scala/dev/librecybernetics/noesis/vocab/Module.scala` |
| Naming convention register | `modules/vocab/NAMING.md` (rules in `modules/conformance/src/test/resources/mdr/naming.json`) |
| PRM capture and projections | `modules/vocab/src/main/scala/dev/librecybernetics/noesis/vocab/PrmCapture.scala`, `Prm.scala` |
| PRM interchange | `modules/vocab/src/main/scala/dev/librecybernetics/noesis/vocab/VCard.scala`, `Foaf.scala` |

### Desktop reactive boundary

The desktop is a Model–View–Update adapter, not another service layer. `Model` contains immutable
presentation data and no GTK object; `Update` is total and pure over `(Model, Event)` and declares
effects as values. `ReactiveController` consumes one fs2 queue, applies transitions serially and
interprets those effects through `OwnerSession`. Completed snapshots are scheduled onto GLib's main
context for rendering. This makes button duplication, stale feedback and cancellation behavior
properties of the reducer rather than timing assumptions in callbacks.

`OwnerSession` reopens the two logs for each effect. That preserves the CLI's cold-replay semantics
inside a long-lived window and makes a specialist CLI write visible without shared mutable caches;
its lightweight position stream refreshes the current Today/Learn projection after an external
sequence or review-log change. Journal commits and review writes are uncancellable once begun, and
the window refuses close while an owner action is in flight.

### Architectural ownership rules

- **The verbalizer owns naming.** Display names come from `Verbalizer.label`, never from an IRI's
  local part. Former names are `sensitive` and must not reach output (§7.2). This is an
  architectural rule with an experience consequence, and [UX.md](UX.md) §1 depends on it: no surface
  may render a display name it derived itself.

## Scala 3 implementation style

Noesis targets Scala 3.8.4 with `-source:3.8`. The
[Scala 3 Reference](https://docs.scala-lang.org/scala3/reference/) is the language authority; use
stable features from that source level when they make an invariant or data flow clearer, not merely
to make the code look more advanced.

- **Model distinctions in types.** Use
  [enums and algebraic data types](https://docs.scala-lang.org/scala3/reference/enums/adts.html) for
  closed alternatives, and
  [opaque type aliases](https://docs.scala-lang.org/scala3/reference/other-new-features/opaques.html)
  for identifiers and scalar domains that must not be mixed. Put construction, validation, codecs
  and focused extension methods in the companion so the representation cannot leak by accident.
- **Make capabilities explicit.** Use `given`/`using` for canonical contextual capabilities, not as
  a general dependency-hiding mechanism. Prefer a context bound when the witness is only forwarded;
  use a [named context bound](https://docs.scala-lang.org/scala3/reference/contextual/context-bounds.html)
  or an explicit `using` parameter when its value is referenced. Keep givens near the type or
  assembly boundary that owns them.
- **Use the indentation and control syntax consistently.** Prefer `:` blocks, `if … then`,
  `while … do`, and multiline `for` expressions. Braces remain appropriate when they delimit an
  expression more clearly than indentation; optional braces are a readability tool, not a target
  metric. See [optional braces](https://docs.scala-lang.org/scala3/reference/other-new-features/indentation.html)
  and [control syntax](https://docs.scala-lang.org/scala3/reference/other-new-features/control-syntax.html).
- **Name structural data before it becomes cryptic.** A
  [named tuple](https://docs.scala-lang.org/scala3/reference/other-new-features/named-tuples.html) is
  a good fit for a private, short-lived row passed through a collection pipeline; use field access
  instead of nested `_1`/`_2`. Use a case class for a public, persisted, domain-level or
  behavior-bearing record. Keep a named-tuple type consistent through collection signatures:
  forgetting names is not automatically lifted through containers such as `List`.
- **Keep transformations linear.** Use a `for` comprehension when later steps depend on earlier
  values, and bind a meaningful intermediate value once rather than repeating a lookup or
  calculation. Scala 3.8's
  [better `for` comprehensions](https://docs.scala-lang.org/scala3/reference/other-new-features/better-fors.html)
  permit aliases at the start. For independent collection work, choose the combinator that states
  the intent (`collect`, `flatMap`, `groupMap`, `Option.when`) and build an index once when several
  operations query the same source.
- **Expose intention-revealing views.** If callers need only values, give them a value-only lookup
  rather than making them unpack an internal tuple that also carries provenance. Conversely, do not
  discard provenance in reasoning and policy code: use the supported index row there. This keeps
  call sites concise without weakening the provenance invariant.
- **Use concise construction where it stays obvious.** Scala's
  [universal apply methods](https://docs.scala-lang.org/scala3/reference/other-new-features/creator-applications.html)
  make `Workspace(...)` sufficient for an ordinary Scala class. Retain `new` for Java constructors,
  anonymous implementations and places where it communicates something useful. Rely on
  [automatic eta expansion](https://docs.scala-lang.org/scala3/reference/changed-features/eta-expansion.html)
  instead of adding adapter lambdas that only forward their arguments.
- **Be explicit at boundaries, infer inside them.** Public methods, recursive definitions,
  persistence codecs and non-obvious intermediate structures get result types. Straightforward
  local values and lambdas may be inferred. Exhaustive matches over enums are preferable to
  wildcard cases because adding a case should make every affected policy visible to the compiler.
- **Let the compiler enforce the style's safety claims.** Keep warnings as errors, unused/value
  checks and [`-Wsafe-init`](https://docs.scala-lang.org/scala3/reference/other-new-features/safe-initialization.html)
  enabled. Avoid initialization-order dependencies between module objects; prefer methods or lazy
  indices when construction depends on another value. A refactor is not an improvement until the
  owning tests show that the clearer form preserves the observable contract.

### Type-driven boundaries

Apply [“Parse, don't validate”](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/)
at every boundary where less-structured input becomes domain data. A successful check should return
a type that preserves what was learned; a validator returning `Unit`, or a processor that accepts
the original broad type and repeats the check, throws that information away.

- **Separate boundary input from trusted domain values.** CLI arguments, imported text, JSON and raw
  axioms may be strings and integers. Parse them once into enums, opaque refinements, non-empty
  collections or domain records before planning journal operations. Functions below that boundary
  accept the refined type and should be total with respect to the discharged invariant.
- **Strengthen arguments before weakening results.** If a function can fail only because a cadence
  might be non-positive, accept `PositiveDays` instead of returning an `Either` from an `Int`.
  Reserve `Either` for failures that remain genuinely possible with the strongest practical input
  type. Do not wrap a total result in `Right` merely to preserve an obsolete signature.
- **Use products for “and” and sums for “or”.** Independent `Option` or `Boolean` fields often admit
  combinations with no domain meaning. Replace them with an enum whose cases carry exactly the data
  needed by each legal alternative. `GiftParties`, for example, represents recipient-only,
  giver-only and two-party gifts without representing a gift with nobody involved.
- **Hide scalar refinements behind smart constructors.** An opaque type such as `NonBlank` or
  `PositiveDays` keeps runtime representation and codecs simple while preventing unchecked values
  from being constructed elsewhere. Its companion owns parsing, error vocabulary and the minimal
  extensions required to consume it; it must not expose a public unchecked constructor.
- **Keep validation at genuinely untyped boundaries.** Structured PRM capture consumes refined
  values, but the state validator still checks raw axioms because generic assertions and imported
  ontology data can bypass that capture API. Duplicate-looking checks are justified only when they
  defend distinct trust boundaries, not when processing code has forgotten the proof it received.
- **Prefer one source of truth.** Derive projections and indexes from authoritative values rather
  than storing a second flag or normalized copy that can disagree. If a denormalized form is needed
  for performance, keep it private to the abstraction that updates both forms atomically.

## System design principles

1. **Local-first software.** Noesis treats the owner's local data as primary, rather than as a cache
   of server-owned state. Capture, reasoning, learning and export work without a network service;
   the knowledge journal and review log remain available in ordinary local files. This follows the
   ownership, offline operation, longevity, privacy and user-control ideals introduced by Kleppmann,
   Wiggins, van Hardenberg and McGranaghan in
   [*Local-First Software: You Own Your Data, in Spite of the Cloud*](https://www.inkandswitch.com/local-first/)
   ([open-access paper](https://martin.kleppmann.com/papers/local-first.pdf),
   [ACM DOI](https://doi.org/10.1145/3359591.3359737)). The current MVP realizes the single-device
   part of that model; multi-device synchronization and collaboration remain unimplemented.
2. **Explicit trust and least authority (Zero Trust).** No component, caller or network location
   receives authority merely because it is inside a perimeter. Access across a trust boundary is
   explicit, narrowly scoped and validated; disclosure fails closed, and isolated agents receive
   only the repository revision, credentials, tools and network destinations required for their
   task. The current MVP applies this principle at its implemented disclosure and agent-execution
   boundaries; authentication and authorization for the unimplemented HTTP, MCP and synchronization
   surfaces remain future work.
3. **Risk management and threat modeling.** Security decisions start with the assets at risk, the
   relevant actors, trust boundaries, plausible failure and abuse paths, and the impact of
   compromise. Risks are eliminated where practical, otherwise reduced through controls such as
   least authority, isolation, validation and explicit egress; residual risks and assumptions are
   documented rather than hidden. [THREAT_MODEL.md](THREAT_MODEL.md) records the application-wide
   model, while the [isolated-agent security model](#isolated-agent-security-model) expands that
   boundary. Both must be revisited when a boundary or its exposed assets change.
4. **Deterministic and auditable replay.** Given the same journal prefix, review log, module
   configuration and explicit evaluation context, reconstruction must produce the same observable
   state. Content-derived identifiers, exact journal-backed provenance and deterministic local
   tests make changes explainable and replay defects reproducible; hidden mutable service state
   must not affect a projection.
5. **Interoperability and freedom to exit.** The owner can copy the ordinary local journal and
   review files, create a checksummed transparent archive with a replay-verified restore path, and
   export the implemented semantic and contact views through Turtle, vCard and mapped FOAF/RDF.
   Persisted formats are compatibility boundaries, and extension seams isolate interchange
   adapters. A proprietary remote service must never become the sole holder of the owner's
   knowledge or the only way to retrieve it.
6. **Data minimization and purpose limitation.** Each boundary receives only the data and
   capabilities required for its named operation. Disclosure policies project permitted axioms
   rather than handing an agent the whole graph, while isolated-agent sessions expose one selected
   revision and an allowlisted network. New capture, export, model and telemetry surfaces must state
   their purpose and must not silently broaden reuse or egress.
7. **Evidence and uncertainty are explicit.** The system records the evidence it has, distinguishes
   different kinds of uncertainty and declines judgments it cannot support. The current product has
   no LLM calls or API key; a rubric-graded answer returns `None` instead of a guessed grade because
   a fabricated grade would corrupt the review log used by §12.3. Future model-backed judgments
   must preserve that distinction rather than presenting generated confidence as evidence.
8. **Correctness is type-driven.** Less-structured data is parsed at trust boundaries into the most
   precise practical representation before the system acts on it. Enums express closed choices,
   opaque types retain scalar refinements, non-empty collections retain cardinality proofs, and sum
   types encode legal alternatives without admitting meaningless combinations. Processing below a
   boundary is total with respect to those proofs; validation remains only at independent untyped
   boundaries such as generic raw-axiom assertion. This makes invalid state transitions harder to
   express and turns a weakened invariant into a compile-time failure at its consumers.

## Implementation invariants

1. **Durable state is journaled; all other state is derived.** The knowledge journal and review log
   are the durable sources of truth. State, reasoner closure, learning items and ledger balances are
   projections, cached in memory and invalidated on commit. Cross-process use reconstructs them
   instead of persisting a derived value as truth. `cli/Workspace.open` contains the reconstruction
   pattern.
2. **Journal commits preserve validity and atomicity.** `KnowledgeBase.commit` plans, checks
   consistency on a scratch projection, conditionally appends against that exact durable prefix,
   invalidates, then emits. A versioned commit frame contains the whole bundle and its SHA-256
   checksum; cross-process file locking, contiguous sequence validation and fsync make accepted
   commits durable and prevent separately opened writers from interleaving. Rejected bundles do not
   reach the journal, and an accepted bundle lands whole or not at all.
3. **Provenance is part of every derived fact.** Disclosure filtering (§3.3.1), derived belief
   (§4.4) and contradiction messages (§3.4) read the same justification data. Inference preserves
   exact journal-backed premises up to configured resource bounds. Crossing a bound adds an
   explicit incomplete marker and makes the closure incomplete; it never silently drops provenance
   and presents the result as exhaustive. Facts that remain correct while their provenance is
   silently coarsened still break the privacy and learning models.
   `Justification.empty` applies only to conclusions without real premises.
4. **Sensitivity fails closed.** Unlabeled assertions default to `personal`, an unresolvable
   premise resolves to `sensitive`, and `sensitive` is undisclosable regardless of grants. No path
   defaults to `public`.
5. **Belief is not truth confidence.** `belief` measures how well the owner remembers a fact;
   `truthConfidence` measures how likely the fact is to be true. They are never combined.
6. **Inference rules are monotone.** A `Rule` may add facts but never remove them; otherwise the
   fixpoint need not terminate.
7. **Persistence boundaries are explicit.** Journal-serialized sum types are `enum`s that derive
   `ConfiguredCodec` and use the `given Configuration` in
   `modules/logic/src/main/scala/dev/librecybernetics/noesis/logic/JsonConfig.scala`
   (discriminator `type`, defaults honored). Identifiers such as `Iri`, `AxiomId`, `FluentId` and
   `ItemId` are opaque types with explicit Circe instances in their companions. Learning item and
   question source identifiers are domain-separated, length-delimited SHA-256 values. These shapes
   prevent unrelated identifiers from being mixed and make collision behavior independent of a
   runtime's `hashCode`.
8. **Generic reads are disclosure-scoped.** Raw journal, state, closure and diagnostic projections
   are implementation capabilities under the `dev.librecybernetics.noesis` package. General
   consumers receive a `DisclosureView` whose state, closure, names and justifications have already
   crossed one policy boundary. Entailment, explanation and query return `ReasoningResult`, so a
   sound partial answer cannot be confused with a complete negative or exhaustive result.
9. **Local persistence is private by construction.** Opening a workspace rejects symlinked
   persistence paths and tightens POSIX modes to `0700`/`0600`, independent of the caller's umask.
   Archives are created with the same owner-only modes, carry SHA-256 checksums for every payload,
   and restore only into a new path.
10. **A file is never the truth about a note.** Block text, position and parent are fluents, so the
    Markdown mirror and the `$EDITOR` buffer are both renderings. The mirror is rewritten wholesale
    from state and is never read back; the buffer is read back only through
    `NoteEditor.parse`/`align`/`plan`, which resolve every line to an existing block or an explicit
    new one. Nothing infers a block identity from a file's position alone, because every extracted
    fact, quote and link addresses a block id — a diff that mints ids freely does not corrupt the
    note, it detaches the knowledge from it while the note still looks right.
11. **An edit emits only what changed.** `NoteEditor.plan` returns no intents for an untouched
    buffer, and supersedes a fluent only where its value differs. This is an invariant rather than
    an optimization: superseding unconditionally would write an edit history in which the owner
    rewrote every block each time the editor opened, and would fire `state.changed` for all of them.

## Testing principles

- Test names state behavioral claims, for example `"a conclusion derivable from public facts alone
  is public, whatever other derivation paths exist"`, rather than implementation labels such as
  `"testDisclosure3"`.
- Tests cover observable contracts and invariants. Properties are more stable than magic constants
  when the specification still treats a model as provisional.
- A relevant `SPEC.md` section appears in a suite or section comment when the rationale for a group
  of cases is not otherwise evident.
- Tests are deterministic and local. They contain no LLM calls or API keys, and Cats Effect
  resources manage temporary files and other acquired state.
- Failure behavior and preserved state receive the same attention as successful behavior. Rejected
  commits, malformed persisted input and undisclosable data demonstrate fail-closed guarantees.
- Provenance is part of an inferred-fact assertion alongside the conclusion and its journal-backed
  justification.
- Persisted formats are compatibility boundaries. Exact round trips or golden values cover them,
  while wire-format changes include old and new fixtures plus an explicit migration strategy.
- A contract is tested in its lowest owning module, with an integration case where it crosses a
  module seam. Realistic shapes from shipped vocabularies represent behavior that depends on those
  shapes.

### Conformance testing

Conformance testing and product testing answer different questions. `modules/conformance` checks
whether Noesis's interpretation matches external normative references; other module suites check
whether the implementation matches Noesis's intended behavior. A failing conformance case is either
a bug or an explicit entry in `modules/conformance/DEVIATIONS.md`; it is never silently skipped.

The conformance module stays outside the Stryker matrix. Broad external corpora kill mutants
incidentally and would make a 100% score stop meaning that precise unit tests pin product behavior.
Its manifest loading and readers are also test scaffolding rather than product contracts.
`DEVIATIONS.md`, rather than mutation coverage, is its gate.

### Mutation testing

Mutation testing serves the design rather than dictating artificial assertions. A mutant that is
behaviorally equivalent for every reachable input usually exposes a redundant branch or guard.
Meaningful thresholds have names and exact boundary coverage. Defensive code remains reachable from
its owning module's tests; widening a helper to `private[module]` retains a useful guard without
leaving it untested.

Typical equivalent mutants reveal one of these design problems:

- A branch is dead when both sides compute the same answer. For example, an empty check before a
  fold that already handles emptiness adds no behavior.
- A guard is redundant when a later clamp, truncation or `take` already enforces it.
  `elapsed.max(0.0)` replaces a separate non-positive branch, while `take(0)` already handles zero
  slots.
- A threshold that cannot be addressed exactly is difficult to test. A name such as
  `Scheduler.minEntropy` makes the boundary and both sides directly testable.
- A strict/non-strict comparison that survives usually lacks an exact-equality case, such as a grade
  exactly at 0.6 or belief exactly at its retention target.

## Isolated-agent security model

Agent sessions follow least authority: each gets a synthetic repository containing one explicitly
selected committed revision, provider-specific credentials, a private temporary filesystem and
only the required tool closure. Results return to the host through an explicit diff review and
apply step.

The outer Bubblewrap namespace is the security boundary. Inner agent permissions are deliberately
permissive within that boundary so they do not add a weaker or fail-open second sandbox. Network
access is allowlisted and relayed through a host-side HTTPS CONNECT proxy, but an allowed endpoint
is still an exfiltration channel and receives repository content during normal model use.

This is process isolation, not a virtual machine. It shares the host kernel and maps back to the
invoking user, so hostile native code belongs under a dedicated OS account or in a disposable VM.
Same-UID kernel facilities such as the user's kernel keyring are not a credential boundary.
Credentials intentionally entrusted to an agent are readable by code in that agent's session; the
boundary protects unrelated host credentials.

## Deliberate spec departures and open-question decisions

Two model decisions depart from a literal reading of the spec and are also commented at their
definitions:

- `Fluent.isOngoing` requires both an absent `validTo` and an absent `endReason`. A supersession
  whose boundary date is unknown has definitely ended; treating it as ongoing produced two
  simultaneous current employers.
- `IrreflexiveProperty` is part of the axiom language. Without it, the spec's
  `worksAt ∘ worksAt⁻ ⊑ colleagueOf` makes everyone their own colleague.

For the open questions in §12, the implementation takes these positions:

- The EL profile is checked and warned about but never enforced, because §3.1 sets DL as the ceiling.
- Justification count and size are capped (§12.4), with explicit incomplete markers. Consistency
  pre-flight fails closed on an incomplete closure; read APIs return a typed incomplete result.
- Agent reads carry far less weight than owner reads in utility signals, and every learning session
  reserves a slice for low-utility items so a mis-scored fact remains discoverable (§12.10).
