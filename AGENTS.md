# AGENTS.md

## Keep documentation and implementation synchronized

Before finishing any change, check documentation and implementation in both directions. A change to
documentation that describes current behavior, commands, configuration, architecture or guarantees
must be reflected in the implementation; a change to implementation must update every affected
README, operational note, example and other current-state documentation. If the two cannot be made
consistent within the task's scope, report the discrepancy explicitly rather than leaving silent
drift.

`SPEC.md` remains the exception described below: it is the authority on intended design and may
deliberately describe work that is not implemented yet. Do not implement speculative scope merely
to make the current code match the design document.

Operational notes for LLM agents working in this repository. Read [README.md](README.md) for what the
project is; this file is about how to change it without breaking it.

**[SPEC.md](SPEC.md) is the authority on intent.** It is a design document, not documentation of the
code — it describes more than is built. When code and spec disagree, that is a finding to report, not
a bug to silently fix. Two deliberate departures are already recorded in README and commented at
their definitions (`Fluent.isOngoing`, `IrreflexiveProperty`); do not "correct" them.

## Commands

```bash
nix develop --command sbt -batch <task>     # everything runs inside the flake devshell
sbt compile                                 # all seven modules + CLI
sbt logic/testOnly 'noesis.logic.*'         #   4 tests
sbt journal/testOnly 'noesis.journal.*'     #   8
sbt reasoner/testOnly 'noesis.reasoner.*'   #  39
sbt core/testOnly 'noesis.core.*'           #  65
sbt lms/testOnly 'noesis.lms.*'             #  36
sbt vocab/testOnly 'noesis.vocab.*'         #  41
sbt cli/launcher                            # writes an executable launcher, prints its path
```

- **`sbt test` is incremental in sbt 2** and prints `Total 0` for unchanged modules. It is not
  evidence the suite passed. Use the `testOnly` forms above to actually run everything, and quote the
  real counts when reporting.
- **`sbt cli/run` merges arguments** across multiple `run` commands in one session, so a multi-step
  CLI scenario cannot be scripted through it. Use `sbt cli/launcher` and invoke the script.
- Toolchain: Scala 3.8.4, sbt 2.0.4, JDK 25, all pinned in `flake.nix`. sbt 2 is a `version`+`src`
  override of nixpkgs' sbt 1.x. Do not add a dependency without adding it to `build.sbt`.

## Architecture

```
logic  ← journal
  ↑
reasoner

logic + journal + reasoner  ← core  ← lms  ← vocab  ← cli
```

Dependencies point one way and must stay that way:

- `logic` is the persisted semantic language and depends on no Noesis module.
- `journal` and `reasoner` depend only on `logic`; neither knows about application policy.
- `core` composes those foundations. It knows nothing about learning, vocabulary modules or the CLI.
  It must never import from them.
- `lms` reads the Knowledge Core and reacts to its events; it never writes to the KB.
- `vocab` declares vocabulary as data. A module is a value implementing `Module`, not a plugin with
  lifecycle hooks.
- Modules extend the system through three seams only: `Rule` (inference), `PolicyBook` /
  `ItemPolicyBook` (annotation and item defaults), `Templates` (verbalization). If you find yourself
  adding a module-specific branch inside `core`, the seam is wrong.

Key files:

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

## Invariants — do not break these

1. **Only the journal is written.** Everything else is a projection. Never persist a derived value
   (a balance, a closure, an item's belief) as though it were truth. If you need it across processes,
   recompute it — `cli/Workspace.open` shows the pattern.
2. **Validation precedes the journal.** `KnowledgeBase.commit` plans → checks consistency on a
   *scratch* projection → appends → invalidates → emits. Never append first and clean up after; the
   journal must never contain a state the reasoner rejects.
3. **A commit is atomic.** A bundle either lands whole or not at all. Do not add a partial-success
   path.
4. **Justifications are load-bearing.** Disclosure filtering (§3.3.1), derived belief (§4.4) and
   contradiction messages (§3.4) all read the same justification data. A change that keeps facts
   correct but drops or coarsens justifications silently breaks the privacy model. Tests assert on
   justifications for this reason — do not weaken them to facts-only.
5. **Sensitivity fails closed.** Unlabeled assertions default to `personal`; an unresolvable premise
   resolves to `sensitive`; `sensitive` is undisclosable regardless of grants. Never add a path that
   defaults to `public`.
6. **Belief ≠ truthConfidence.** Memory versus world. Never combine them.
7. **No LLM calls.** There is no model in the loop and no API key. A rubric-graded answer returns
   `None` rather than a guessed grade, because a fabricated grade corrupts the review log §12.3 needs.
   Do not "fill in" the grader.
8. **The verbalizer owns naming.** Display names come from `Verbalizer.label`, never from an IRI's
   local part. Former names are `sensitive` and must not reach output (§7.2).
9. **Rules must be monotone.** A `Rule` may only add facts, or the fixpoint will not terminate.

## Conventions

- **Scala 3 indentation syntax** throughout: `:` block openers, `end`-less definitions, no braces
  except where an expression needs them. Context bounds use the multi-bound form
  `[F[_]: {Async, UUIDGen}]` and named bounds where an instance must be referenced directly
  (`[F[_]: {Files as files, Async}]`).
- **Comments explain *why*, and cite the spec section.** The prevailing style is a Scaladoc block on
  each type stating what it is and which spec decision forced its shape, e.g. "`max` within a
  justification because you need *all* its premises; `min` across because you need only *one*". Match
  that density — do not add narration of what the code plainly does, and do not strip the rationale.
- **Sum types are `enum`.** Journal-serialized types derive `ConfiguredCodec` and rely on the
  `given Configuration` in `modules/logic/src/main/scala/noesis/logic/JsonConfig.scala`
  (discriminator `type`, defaults honored).
- **Opaque types** for identifiers (`Iri`, `AxiomId`, `FluentId`, `ItemId`) with explicit circe
  instances in the companion.
- **Tests are behavioral and named as claims** — `"a conclusion derivable from public facts alone is
  public, whatever other derivation paths exist"`, not `"testDisclosure3"`. Prefer pinning a property
  over a magic constant where the spec calls the model provisional (§12.3).

## Traps hit in this codebase

These cost real time. Check here before debugging from scratch.

- **`Option[Option[A]]` does not round-trip.** `Some(None)` encodes to JSON `null`, indistinguishable
  from absent, so "clear this override" became a silent no-op on replay. Use the explicit three-state
  `Patch` enum in `modules/logic/src/main/scala/noesis/logic/Annotations.scala`.
- **fs2 `Files` + `Async` context bounds are ambiguous.** `Files[F]` cannot be summoned when both are
  in scope (fs2 3.12 deprecates the `Async`-derived instance). Either use a named context bound
  (`Files as files`) or pass `Files[F]` as an explicit constructor parameter, as `JsonLinesJournal`
  does.
- **An enum case field may not share a name with a method on the enum.** `Operation.Assert(axiomId:
  ...)` plus `def axiomId` is an override error; hence `targetAxiom` / `targetFluent`.
- **sbt 2 rejects `File`/`Path` as cached task output.** Return `String` (or a virtual file ref), or
  mark the task `@transient` / `Def.uncached`. See the `launcher` task.
- **sbt 2 caches tasks by inputs, including ones whose product is a side effect.** The `launcher`
  task reported `[success]` while writing nothing, because its inputs were unchanged and a previous
  `clean` had deleted the output. Any task whose real product is a file on disk needs
  `Def.uncached`. A `[success]` with no work log and a missing artifact is this bug.
- **An object property with no declared range is indistinguishable from a data property.** The CLI
  types `assert s p v` from the ontology, so a property lacking `PropertyRange` fell through to the
  literal branch and stored `spouseOf "marco"` — a string — instead of a reference to Marco. Being
  the inverse of a typed property is *not* enough (`childOf` had the same bug). When adding a
  relationship to a vocabulary module, declare its domain and range explicitly; `ModuleSuite` has a
  test enumerating the social properties that guards this.
- **Range declarations interact with disjointness.** `crm`'s social properties range over `Agent`,
  not `Person`, because Person and Organization are disjoint in core and a narrower range would make
  "I know this company" an inconsistency rather than a fact.
- **decline reads a leading `--` as an option**, so the canonical partial date `--05-12` is
  unparseable as an argument. `Literal.parse` accepts bare `MM-DD` for this reason; keep that.
- **JVM argument decoding uses the platform locale.** Under a C locale, `Lía` is mangled *before it
  is stored*. The launcher sets `LC_ALL` and `sun.jnu.encoding`; keep both.
- **A `final case class` cannot be anonymously subclassed.** `DisclosurePolicy` gained a `local` flag
  instead of an override.
- **Covariant type parameters** cannot appear in a method parameter's contravariant position — see
  `Patch.applyTo[B >: A]`.
- **munit `assertEquals` requires matching types**; a `Set[(Node.Ref, Node)]` will not compare against
  `Set[(Node, Node)]`. Ascribe explicitly.
- **Tuple destructuring in a lambda parameter list** (`((a, b), i) => ...`) is not legal Scala 3;
  destructure in the body.
- **Fluent-backed facts have no `AxiomRecord`.** They are projections, so the annotation cascade
  cannot see them and falls back to a neutral utility. Since `worksAt`, `hasName` and `pronouns` are
  all time-varying *and* the highest-utility properties, this silently mis-ranked name-change items.
  `LearningEngine.utilityRecords` synthesizes records from fluent annotations; anything else
  resolving policy over items must do the same.

## Adding things

**A vocabulary module:** implement `Module` in `modules/vocab`, add it to `Modules.all`, and add
integration tests to `ModuleSuite` that exercise it against the *unmodified* core — testing the
declarations in isolation proves nothing. Check `noesis check` still reports the merged TBox
consistent.

**An inference rule:** implement `Rule` in `reasoner` or a vocabulary module, keep it monotone,
combine premise justifications with
`Rule.combine` / `combineAll` (never fabricate `Justification.empty` for a real premise), and add it
to `RdfsRules.all` for core rules or the module's `rules` for domain rules. Assert on the derived
fact *and* its justification.

**An axiom case:** change `logic`; `Axiom` has exhaustive matches in `signature`, `individuals`,
`manchester` and
`Triples.of`, plus `Profile.elWarning`. The compiler will find most, but `manchester` and the profile
check are easy to leave wrong rather than missing.

**A journal operation:** change `journal`, then handle the case in `KbState.step` *and*
`Events.forOperation` (the CLI rebuilds learning state by replaying events, so an operation the event
derivation ignores becomes invisible after a restart), and add a round-trip case to `JournalSuite`.

## Reporting

State test counts from an actual run, not from this file. If a test fails or a step was skipped, say
so with the output. When you find a spec/code disagreement, report it rather than resolving it
unilaterally.
