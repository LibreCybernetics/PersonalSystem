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
| `journal` | `JournalSuite`: operation codecs, append ordering, atomic bundles, concurrent appends, JSON Lines persistence, reopening, and corrupt-input failure. `SerializationSuite`: N-Triples reading and writing, and Turtle output |
| `reasoner` | `ReasonerSuite`, `QuerySuite`: inference, fixpoint behavior, journal-backed justifications, consistency, EL warnings, and graph-pattern queries |
| `core` | `ProjectionSuite`, `KnowledgeBaseSuite`, `DisclosureSuite`, `VerbalizerSuite`: replay and temporal projections, commit validation and atomicity, events, policy and disclosure, and naming/verbalization |
| `lms` | `BeliefSuite`, `SchedulerSuite`, `ItemSuite`, `QuestionsSuite`, `LearningEngineSuite`: belief updates and decay, derived belief, retention/elucidation scheduling and exploration, item identity and answer grading, template question generation, and the engine's reaction to core events plus review-log recovery |
| `vocab` | `ModuleSuite`: the merged modules against the unmodified core, including ontology consistency, inference, policies, templates, capture, learning, and ledger scenarios |
| `conformance` | `JcsConformanceSuite`, `XsdConformanceSuite`, `IriConformanceSuite`, `LanguageTagConformanceSuite`, `NTriplesConformanceSuite`, `TurtleConformanceSuite`: corpus-driven conformance to the normative references of SPEC §10.1 |
| `nix` | `agent-sandbox-sources`: shell analysis, Python syntax checking, and behavioral tests for the isolated-agent HTTPS proxy |

The `conformance` module answers a different question from the rest and is gated differently, as
described in [Conformance testing](#conformance-testing).

The CLI currently has no dedicated test suite. It is compiled by the full check, while its domain
behavior is exercised through core and vocabulary integration tests. Generated-launcher scenarios,
described below, cover command parsing, workspace persistence, and rendering changes.

Shared test fixtures belong in a `Fixtures` or module-specific fixture object.

## Running the tests

Fast feedback comes from running the owning module explicitly:

```bash
nix develop --command sbt -batch "logic/testOnly noesis.logic.*"
nix develop --command sbt -batch "journal/testOnly noesis.journal.*"
nix develop --command sbt -batch "reasoner/testOnly noesis.reasoner.*"
nix develop --command sbt -batch "core/testOnly noesis.core.*"
nix develop --command sbt -batch "lms/testOnly noesis.lms.*"
nix develop --command sbt -batch "vocab/testOnly noesis.vocab.*"
nix develop --command sbt -batch "conformance/testOnly noesis.conformance.*"
```

Inside `nix develop`, the `nix develop --command` prefix is unnecessary. The ordinary CI gate is
reproduced by starting clean, compiling all eight modules, and explicitly executing every
test-bearing module:

```bash
nix develop --command sbt -batch \
  "clean;
  compile;
  logic/testOnly noesis.logic.*;
  journal/testOnly noesis.journal.*;
  reasoner/testOnly noesis.reasoner.*;
  core/testOnly noesis.core.*;
  lms/testOnly noesis.lms.*;
  vocab/testOnly noesis.vocab.*;
  conformance/testOnly noesis.conformance.*"
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

## Test design

The repository-wide [testing principles](DESIGN.md#testing-principles) provide the basis for the
suite-specific evidence and commands in this file.

MUnit's `assertEquals` requires the obtained and expected values to have matching static types. When
opaque or refined values infer different collection types, ascribe the common type explicitly
instead of weakening the assertion.

## Evidence required by change type

- **Vocabulary module:** Evidence includes registration in `Modules.all` and `ModuleSuite` coverage
  against the unmodified core. The merged ontology, rules, policies, item policies, and templates
  are covered as applicable; declarations tested only in isolation are insufficient. Domain and
  range declarations are included where CLI value typing depends on the ontology. When CLI behavior
  is in scope, `noesis check` also confirms that the merged TBox remains consistent.
- **Inference rule:** Evidence includes the derived fact, its exact premise justification, and a
  negative or boundary case that protects monotonicity or termination where applicable.
- **Axiom case:** Evidence covers `signature`, `individuals`, Manchester rendering, triple
  projection, `Profile.elWarning`, serialization, and relevant reasoner behavior.
- **Journal operation:** Evidence includes its JSON round trip in `JournalSuite`, replay effect in
  projection tests, and event reconstruction in core tests. Restart/replay exposes the same behavior
  as the live commit path.
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

Changes to `flake.nix`, `nix/agent-session.sh`, `nix/agent-run.sh`, or the agent proxy also require:

```bash
nix flake check
```

The `agent-sandbox-sources` check runs ShellCheck over both shell scripts, compiles
`nix/agent-proxy.py` as Python, and executes `nix/agent-proxy-test.py`. This check is defined by the
flake but is not currently part of either GitHub Actions workflow.

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

## Continuous integration and reporting

The ordinary `CI` workflow runs the clean compile and all seven explicit suite tasks on every branch
push. The `Mutation testing` workflow also runs on every branch push and can be started manually;
its module matrix does not fail fast.

Verification reports contain:

- Test counts from the command output of that run rather than documentation or source scanning.
- The exact module tasks that ran.
- Every failure and every relevant skipped check, together with its output or reason.
- For mutation testing, the affected module's score and report location.
- Any specification/implementation disagreement as a finding rather than an implicit change to
  intended behavior.
