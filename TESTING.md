# Testing Noesis

This maintainer guide is the source of truth for the repository's current testing practice: how the
checks run, what each suite owns, and what evidence accompanies a change. [SPEC.md](SPEC.md) remains
the authority on intended system behavior; [DESIGN.md](DESIGN.md#testing-principles) defines the
testing principles these practices implement.

## Toolchain and suite layout

Checks run in the development shell pinned by `flake.nix` (Scala 3.8.4, sbt 2.0.4, and JDK 25).
The build supplies MUnit and MUnit Cats Effect to every module. Pure suites extend `FunSuite`;
effectful, concurrent, or filesystem-backed suites extend `CatsEffectSuite`.

Tests live beside their owning module under `modules/<module>/src/test/scala`:

| Module | Suites and responsibility |
|---|---|
| `logic` | `LogicSuite`: canonical JSON and stable identifiers, codecs, the axiom algebra, triple projection, literals, annotations, and temporal values |
| `journal` | `JournalSuite`: operation codecs, append ordering, atomic commit frames, cross-handle concurrency, conditional append, coordinated archive snapshots, JSON Lines persistence, permissions, recovery, reopening, and corrupt-input failure. `SerializationSuite`: N-Triples reading and writing, and Turtle output |
| `reasoner` | `ReasonerSuite`, `QuerySuite`: inference, fixpoint behavior, journal-backed justifications, explicit resource-limit incompleteness, consistency, EL warnings, and graph-pattern queries |
| `core` | `ProjectionSuite`, `KnowledgeBaseSuite`, `DisclosureSuite`, `VerbalizerSuite`: replay and temporal projections, commit validation and atomicity, events, policy and disclosure, and naming/verbalization |
| `lms` | `BeliefSuite`, `SchedulerSuite`, `ItemSuite`, `QuestionsSuite`, `LearningEngineSuite`: belief updates and decay, derived belief, retention/elucidation scheduling and exploration, item identity and answer grading, template question generation, and the engine's reaction to core events plus review-log recovery |
| `vocab` | `ModuleSuite`: the merged modules against the unmodified core, including ontology consistency, inference, policies, templates, capture, learning, and ledger scenarios. `PrmSuite`: structured contact capture, validation, privacy, temporal employment, agenda projections, duplicate candidates, and vCard/FOAF integration. `PrmContractSuite`: field-complete capture and interchange mappings, parser boundaries, record identities, normalization, and projection-helper contracts. `FractionalIndexSuite`: sibling order keys, including that appending and prepending stay constant-size. `OutlineSuite`: the note projection, `as-of` over text, arrangement and nesting, and outlines the axiom language cannot rule out. `NotesCaptureSuite`: writing, paragraph chunking, `[[link]]` resolution against current names, and backlinks. `NoteMarkdownSuite`, `NoteEditorSuite`: the mirror, the editable buffer, and which block an edited line is. `NoteRoundTripSuite`: render, edit, plan and commit against a real knowledge base, including that an untouched buffer writes nothing |
| `cli` | `ArchiveSuite`: coordinated archive creation, checksum/replay/projection verification, restore into a fresh workspace, overwrite refusal, and tamper detection. `CommandSurfaceSuite`: derivation of the command tree from `Main`'s typed AST. `ProductTraceSuite`: traceability between that surface and [PRODUCT.md](PRODUCT.md). `ProductDocumentSuite`: the traceability rules themselves, against fixtures |
| `conformance` | `JcsConformanceSuite`, `JsonSyntaxConformanceSuite`, `IjsonConformanceSuite`, `NamingConformanceSuite`, `XsdConformanceSuite`, `IriConformanceSuite`, `LanguageTagConformanceSuite`, `NTriplesConformanceSuite`, `TurtleConformanceSuite`: corpus-driven conformance to the normative references of SPEC §10.1 |
| `nix` | `agent-sandbox-sources`: shell analysis, Python syntax checking, and behavioral tests for the isolated-agent HTTPS proxy |

The `conformance` module answers a different question from the rest and is gated differently, as
described in [Conformance testing](#conformance-testing).

CLI domain behavior is primarily exercised through core and vocabulary integration tests.
`ArchiveSuite` owns the filesystem archive contract; generated-launcher scenarios, described below,
cover command parsing, multi-invocation workspace persistence, and rendering changes.

Shared test fixtures belong in a `Fixtures` or module-specific fixture object.

## Running the tests

Fast feedback comes from running the owning module explicitly:

```bash
nix develop --command sbt -batch "logic/testOnly dev.librecybernetics.noesis.logic.*"
nix develop --command sbt -batch "journal/testOnly dev.librecybernetics.noesis.journal.*"
nix develop --command sbt -batch "reasoner/testOnly dev.librecybernetics.noesis.reasoner.*"
nix develop --command sbt -batch "core/testOnly dev.librecybernetics.noesis.core.*"
nix develop --command sbt -batch "lms/testOnly dev.librecybernetics.noesis.lms.*"
nix develop --command sbt -batch "vocab/testOnly dev.librecybernetics.noesis.vocab.*"
nix develop --command sbt -batch "cli/testOnly dev.librecybernetics.noesis.cli.*"
nix develop --command sbt -batch "conformance/testOnly dev.librecybernetics.noesis.conformance.*"
```

Inside `nix develop`, the `nix develop --command` prefix is unnecessary. The ordinary CI gate is
reproduced by starting clean, compiling all eight modules, and explicitly executing every
test-bearing module:

```bash
nix develop --command sbt -batch \
  "clean;
  compile;
  logic/testOnly dev.librecybernetics.noesis.logic.*;
  journal/testOnly dev.librecybernetics.noesis.journal.*;
  reasoner/testOnly dev.librecybernetics.noesis.reasoner.*;
  core/testOnly dev.librecybernetics.noesis.core.*;
  lms/testOnly dev.librecybernetics.noesis.lms.*;
  vocab/testOnly dev.librecybernetics.noesis.vocab.*;
  cli/testOnly dev.librecybernetics.noesis.cli.*;
  conformance/testOnly dev.librecybernetics.noesis.conformance.*"
```

A plain `sbt test` result is insufficient evidence that every suite ran. sbt 2 executes tests
incrementally and can report `Total 0` for an unchanged module. The explicit `testOnly` tasks above
produce visible results from every selected suite.

### CLI scenarios

Multi-command CLI scenarios begin by generating the launcher:

```bash
nix develop --command sbt -batch cli/launcher
target/out/jvm/scala-3.8.4/noesis-cli/noesis --root <temporary-workspace> init
```

Each subsequent command invokes that script once. `sbt cli/run` is unsuitable for a multi-step
scenario because sbt merges arguments from multiple `run` commands in one session. A disposable
workspace keeps the user's default `~/.noesis` data outside the scenario.

Archive changes use three disposable paths and exercise create, verify, restore, and verification
of the restored workspace. At least one tampered payload is expected to fail `archive verify`.

## Test design

The repository-wide [testing principles](DESIGN.md#testing-principles) provide the basis for the
suite-specific evidence and commands in this file.

MUnit's `assertEquals` requires the obtained and expected values to have matching static types. When
opaque or refined values infer different collection types, ascribe the common type explicitly
instead of weakening the assertion.

## Evidence required by change type

- **Vocabulary module:** Evidence includes registration in `Modules.all` and `ModuleSuite` coverage
  against the unmodified core. The merged ontology, rules, policies, item policies, and templates
  are covered as applicable; contributed naming, validation, interchange and agenda seams are
  included when present. Declarations tested only in isolation are insufficient. Domain and range
  declarations are included where CLI value typing depends on the ontology. When CLI behavior is
  in scope, `noesis check` also confirms that the merged TBox remains consistent. Every term the
  module declares is checked against the naming convention register by `NamingConformanceSuite`; a
  term that would need a new rule needs `modules/vocab/NAMING.md` and its corpus updated first,
  since ISO/IEC 11179-5 conformance is a claim about the namespace, not about the term.
- **Inference rule:** Evidence includes the derived fact, its exact premise justification, and a
  negative or boundary case that protects monotonicity or termination where applicable.
- **Axiom case:** Evidence covers `signature`, `individuals`, Manchester rendering, triple
  projection, `Profile.elWarning`, serialization, and relevant reasoner behavior.
- **Journal operation:** Evidence includes its JSON round trip in `JournalSuite`, replay effect in
  projection tests, and event reconstruction in core tests. Restart/replay exposes the same behavior
  as the live commit path.
- **Persistence or archive behavior:** Evidence covers owner-only permissions where POSIX is
  available, symlink/replacement failure, cross-handle locking, crash-tail behavior, checksum and
  sequence corruption, coordinated journal/review capture, archive replay/projection verification,
  and restore into a fresh destination.
- **Policy or disclosure behavior:** Evidence covers cascade precedence, boundary values, and
  fail-closed behavior. Relevant derived-disclosure cases include competing derivation paths and
  justification sensitivity.
- **Fluent or temporal behavior:** Evidence covers journal replay, current and point-in-time
  projections, boundary dates, support provenance, and emitted state-change events.
- **Learning behavior:** Evidence maintains the distinction between `belief` and
  `truthConfidence`, and covers review-log evidence, decay/update boundaries, scheduling
  consequences, and derived-premise handling as applicable.
- **CLI behavior:** Evidence uses the launcher with a disposable workspace and covers affected
  parsing, rendered output, and persistence/reopen behavior.
- **Owner-facing behavior:** Evidence is the launcher transcript of the affected journey step in
  [PRODUCT.md](PRODUCT.md) §4, checked against the acceptance criteria of the story it serves rather
  than against the author's intent. Every new or changed failure path is checked against the error
  rubric in [UX.md](UX.md) §4, and any `--json` output is treated as a persisted format by the rule
  above. A change that removes friction updates its ledger row; a change that adds friction adds one
  and names the principle that makes the trade acceptable. A new command additionally needs a
  journey step, which `ProductTraceSuite` enforces — see [Product traceability](#product-traceability).
- **RDF serialization:** Reading and writing belong to `noesis-journal`, with unit claims in
  `SerializationSuite` and grammar conformance in `modules/conformance`. Writer evidence uses an
  independently written transcription of the grammar rather than the writer's own interpretation
  of legality.
- **Anything governed by a normative reference:** The vector belongs in the matching corpus under
  `modules/conformance/src/test/resources`, rather than a module suite. A new axiom case also
  declares its `Profile.elWarning` result and, when it introduces a datatype or identifier form, its
  lexical space and canonical mapping.
- **A new normative citation:** A corpus covers the citation before it enters a module `SPEC.md`;
  without that coverage, the normative claim remains unsupported.

## Conformance testing

Corpora live under `src/test/resources`, one directory per specification, each case carrying the
clause it is derived from. Adding coverage means adding a vector, not writing a test. Vectors are
derived from the clauses their `provenance` blocks cite — they are not the specifications' own
published test data; vendoring the upstream corpora is recorded as follow-up F1 in
`modules/conformance/DEVIATIONS.md`.

The [conformance-testing principles](DESIGN.md#conformance-testing) define the classification of
failures and deviations. The conformance module is deliberately outside the Stryker matrix;
`DEVIATIONS.md` is its gate.

## Static analysis

Compilation is part of the test gate. Warnings are errors, including unused code, discarded values,
non-`Unit` statements, and safe-initialization diagnostics. Scapegoat and the curated WartRemover
profile in `build.sbt` run during production and test compilation. A local bypass or suppression
therefore requires documentation of why the exception is safe.

The ordinary GitHub `CI` workflow runs:

```bash
nix flake check
```

The `agent-sandbox-sources` check runs ShellCheck over both shell scripts, compiles
`nix/agent-proxy.py` as Python, and executes `nix/agent-proxy-test.py`. Changes to `flake.nix`,
`nix/agent-session.sh`, `nix/agent-run.sh`, or the agent proxy must also run this check locally.

## Product traceability

`ProductTraceSuite` in the `cli` module compares the shipped command surface against
[PRODUCT.md](PRODUCT.md), and runs with the rest of that module under
`cli/testOnly dev.librecybernetics.noesis.cli.*`. It fails when a shipped command appears in no
journey or story, when a product document invokes a command that does not exist and is not declared
under "Proposed commands", when a proposal has since shipped, when a story cites a journey step
that does not exist, when a journey is served by no story, when a story is missing a field or its
acceptance criteria, when an `F`/`US` cross-reference dangles, when a subcommand is never composed
into `Main.main`, when two sibling commands share a name, and when help text breaks the conventions
in [UX.md](UX.md).

The surface it checks is **derived, not transcribed**: `CommandSurface` is a macro that reads
`Main`'s typed AST, recognizing `Opts.subcommand` by method symbol and nesting by symbol reference.
decline's `Opts` constructors are `private[decline]`, so the built value cannot be inspected at
runtime, and a hand-maintained list would drift silently — which is the exact failure this check
exists to catch. Because the derivation is structural, a command's own help text cannot be mistaken
for structure.

Whether a journey is *worth* serving is not checkable and remains a review question; the suite only
guarantees that the document and the surface describe the same product. `ProductDocumentSuite`
covers the rules themselves against fixtures, so a rule that stops firing is caught too.

## Mutation testing

Stryker4s evaluates whether the suites detect behavioral mutations. Version 1.0.3 is deliberately
pinned because 1.1.0 crashes while mutating this repository's Scala 3.8 sources.

Mutation testing for an affected test-bearing module uses:

```bash
nix develop --command sbt -batch \
  "core/stryker
  --reporters console
  --reporters html
  --reporters json
  --thresholds.high 100
  --thresholds.low 100
  --thresholds.break 99"
```

The module prefix is `logic`, `journal`, `reasoner`, `core`, `lms`, or `vocab`, according to the
affected module. Reports are written under `modules/<module>/target/stryker4s-report`. CI runs those
six modules independently and retains the HTML and JSON reports as artifacts.

**All six modules score 100%, and a change that drops any of them below that fails CI.** The
`conformance` module is deliberately not among them — see [Conformance testing](#conformance-testing).
`--thresholds.break 99` is the strictest value Stryker4s accepts — it requires `break` to be
strictly below `low` — so the workflow additionally reads the JSON report and fails on any mutant
left `Survived` or `NoCoverage`. That check, not the threshold, is the real gate.

`Ignored` mutants do not count against the score. They are Stryker4s's `static` category: values
computed once during object initialization, such as the vocabulary modules' axiom lists, which the
harness cannot re-evaluate per mutant. `CompileError` mutants do not count either.

### Killing a mutant that no test can distinguish

The [mutation-testing design principles](DESIGN.md#mutation-testing) treat an equivalent mutant as
evidence of behaviorally redundant code and meaningful boundaries as directly testable concepts.

### A mutant that never returns is not a mutant a test caught

Stryker4s records a mutant that throws `StackOverflowError` as **`Survived`**, not as detected. A
recursive function therefore hides every mutation that breaks its termination condition rather than
its arithmetic: the test asserting the right answer never runs, no assertion fails, and the report
says the suite did not notice. Adding assertions cannot fix it — the mutant has to be made to return
a wrong value instead of not returning.

**`@tailrec` does not solve this, and usually cannot be applied.** A recursion that builds a result
around its call — `prefix + midpoint(rest)` — is not in tail position, and the annotation is a
compile error rather than a fix. Where an accumulator *can* be threaded through to make it tail
recursive, the annotation converts the overflow into an infinite loop, which Stryker4s records as
`Timeout`. That counts as detected and passes the CI gate, which fails only on `Survived` and
`NoCoverage` (`.github/workflows/mutation.yml`). It is still the worse outcome: the mutant costs a
full timeout instead of failing in milliseconds, and "the run hung" is weaker evidence than "an
assertion caught a wrong answer". Reach for it only when a function genuinely must recurse.

The better fix is to make termination structural, so that no mutation of a guard can affect it:

- `FractionalIndex` consumes the shared prefix of two keys in one step instead of one character at a
  time, leaving a closed form. Nothing in it recurses.
- `Outline.childrenOf` descends on the blocks *not yet placed* rather than on the whole note, so
  each level strictly shrinks its input. Depth is bounded by the block count whatever `parentBlock`
  says — which matters twice over, since a cycle is a shape the axiom language cannot rule out and
  a projection must survive rather than assume away.

Both were found this way: `vocab` reported fifteen `Survived` mutants that no assertion could reach,
and three `Timeout`s in the outline walk. After the rewrites the module reports 740 detected mutants
and **zero** scored by overflow or timeout.

Before adding recursion to a mutation-tested module, ask what a mutated guard does. If it can loop,
the mutation score has quietly stopped measuring that function.

## Continuous integration and reporting

The ordinary `CI` workflow runs the clean compile, all eight explicit suite tasks and
`nix flake check` on every branch push. The `Mutation testing` workflow also runs on every branch
push and can be started manually; its module matrix does not fail fast.

Verification reports contain:

- Test counts from the command output of that run rather than documentation or source scanning.
- The exact module tasks that ran.
- Every failure and every relevant skipped check, together with its output or reason.
- For mutation testing, the affected module's score and report location.
- Any specification/implementation disagreement as a finding rather than an implicit change to
  intended behavior.
