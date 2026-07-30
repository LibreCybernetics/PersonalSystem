# Noesis design

This document records the principles and constraints of the current implementation. It complements
[SPEC.md](SPEC.md), which is the authority on intended design and deliberately describes more than
the MVP implements. A disagreement between implementation and spec is a finding rather than an
implicit reason to change either one. [README.md](README.md) describes the current product and its
operation; [TESTING.md](TESTING.md) describes its verification practices.

## Architecture

```
logic  ← journal
  ↑
reasoner

logic + journal + reasoner  ← core  ← lms  ← vocab  ← cli
```

Dependencies point one way:

- `logic` is the persisted semantic language and depends on no Noesis module.
- `journal` and `reasoner` depend only on `logic`; neither knows about application policy.
- `core` composes those foundations. It knows nothing about learning, vocabulary modules or the CLI
  and never imports from them.
- `lms` reads the Knowledge Core and reacts to its events; it never writes to the KB.
- `lms` remains independent of domain vocabularies.
- `vocab` declares vocabulary as data. A module is a value implementing `Module`, not a plugin with
  lifecycle hooks.
- `cli` assembles and exposes the system without becoming a dependency of any other module.

Modules extend the system through three seams: `Rule` for inference, `PolicyBook` /
`ItemPolicyBook` for annotation and item defaults, and `Templates` for verbalization. A
module-specific branch inside `core` indicates that the seam is wrong.

Key implementation points:

| Concern | File |
|---|---|
| Axiom language | `modules/logic/src/main/scala/noesis/logic/Axiom.scala` |
| Journal operations | `modules/journal/src/main/scala/noesis/journal/Operation.scala`, `Journal.scala` |
| Journal → state fold | `modules/core/src/main/scala/noesis/core/projection/KbState.scala` |
| State projections | `modules/core/src/main/scala/noesis/core/projection/Projections.scala` |
| Reasoner graph | `modules/reasoner/src/main/scala/noesis/reasoner/Graph.scala` |
| Inference rules | `modules/reasoner/src/main/scala/noesis/reasoner/Rule.scala` (+ module rules in `vocab/`) |
| Fixpoint, closure | `modules/reasoner/src/main/scala/noesis/reasoner/Reasoner.scala` |
| Consistency, EL profile | `modules/reasoner/src/main/scala/noesis/reasoner/Consistency.scala` |
| Annotation cascade | `modules/core/src/main/scala/noesis/core/policy/Policy.scala` |
| Disclosure rule | `modules/core/src/main/scala/noesis/core/policy/Disclosure.scala` |
| Intent → operations | `modules/core/src/main/scala/noesis/core/capture/Capture.scala` |
| Service surface | `modules/core/src/main/scala/noesis/core/kb/KnowledgeBase.scala` |
| Belief, derived belief | `modules/lms/src/main/scala/noesis/lms/Belief.scala` |
| Scheduling | `modules/lms/src/main/scala/noesis/lms/Scheduler.scala` |
| Module contract | `modules/vocab/src/main/scala/noesis/vocab/Module.scala` |
| Naming convention register | `modules/vocab/NAMING.md` (rules in `modules/conformance/src/test/resources/mdr/naming.json`) |

## System principles and invariants

1. **Local-first software.** Noesis treats the owner's local data as primary, rather than as a cache
   of server-owned state. Capture, reasoning, learning and export work without a network service;
   the knowledge journal and review log remain available in ordinary local files. This follows the
   ownership, offline operation, longevity, privacy and user-control ideals introduced by Kleppmann,
   Wiggins, van Hardenberg and McGranaghan in
   [*Local-First Software: You Own Your Data, in Spite of the Cloud*](https://www.inkandswitch.com/local-first/)
   ([open-access paper](https://martin.kleppmann.com/papers/local-first.pdf),
   [ACM DOI](https://doi.org/10.1145/3359591.3359737)). The current MVP realizes the single-device
   part of that model; multi-device synchronization and collaboration remain unimplemented.
2. **Only journals are written.** The knowledge journal and review log are the durable sources of
   truth. State, reasoner closure, learning items and ledger balances are projections, cached in
   memory and invalidated on commit. Cross-process use reconstructs them instead of persisting a
   derived value as truth. `cli/Workspace.open` contains the reconstruction pattern.
3. **Validation precedes the knowledge journal.** `KnowledgeBase.commit` plans, checks consistency
   on a scratch projection, appends, invalidates, then emits. Consequently, the journal contains
   only states accepted by the reasoner.
4. **A commit is atomic.** A bundle either lands whole or not at all; there is no partial-success
   path.
5. **Justifications are shared, load-bearing infrastructure.** Disclosure filtering (§3.3.1),
   derived belief (§4.4) and contradiction messages (§3.4) read the same justification data.
   Inference preserves exact journal-backed premises; facts that remain correct while their
   provenance is dropped or coarsened still break the privacy and learning models.
   `Justification.empty` applies only to conclusions without real premises.
6. **Sensitivity fails closed.** Unlabeled assertions default to `personal`, an unresolvable
   premise resolves to `sensitive`, and `sensitive` is undisclosable regardless of grants. No path
   defaults to `public`.
7. **Belief is not truth confidence.** `belief` measures how well the owner remembers a fact;
   `truthConfidence` measures how likely the fact is to be true. They are never combined.
8. **Model judgments are never fabricated.** The current product has no LLM calls or API key. A
   rubric-graded answer returns `None` instead of a guessed grade because a fabricated grade would
   corrupt the review log used by §12.3.
9. **The verbalizer owns naming.** Display names come from `Verbalizer.label`, never from an IRI's
   local part. Former names are `sensitive` and must not reach output (§7.2).
10. **Inference rules are monotone.** A `Rule` may add facts but never remove them; otherwise the
   fixpoint need not terminate.

Journal-serialized sum types are `enum`s that derive `ConfiguredCodec` and use the
`given Configuration` in `modules/logic/src/main/scala/noesis/logic/JsonConfig.scala`
(discriminator `type`, defaults honored). Identifiers such as `Iri`, `AxiomId`, `FluentId` and
`ItemId` are opaque types with explicit Circe instances in their companions. These shapes keep
persistence boundaries explicit and prevent unrelated identifiers from being mixed.

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
- Justification count and size are capped (§12.4).
- Agent reads carry far less weight than owner reads in utility signals, and every learning session
  reserves a slice for low-utility items so a mis-scored fact remains discoverable (§12.10).
