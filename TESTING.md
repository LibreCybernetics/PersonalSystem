# Testing Noesis

This file is the source of truth for the repository's current testing practice: how to run the
checks, what each suite owns, and what evidence a change must add. [SPEC.md](SPEC.md) remains the
authority on intended system behavior.

## Toolchain and suite layout

Run checks in the development shell pinned by `flake.nix` (Scala 3.8.4, sbt 2.0.4, and JDK 25).
The build supplies MUnit and MUnit Cats Effect to every module. Pure suites extend `FunSuite`;
effectful, concurrent, or filesystem-backed suites extend `CatsEffectSuite`.

Tests live beside their owning module under `modules/<module>/src/test/scala`:

| Module | Suites and responsibility |
|---|---|
| `logic` | `LogicSuite`: canonical JSON and stable identifiers, codecs, the axiom algebra, triple projection, literals, annotations, and temporal values |
| `journal` | `JournalSuite`: operation codecs, append ordering, atomic bundles, concurrent appends, JSON Lines persistence, reopening, and corrupt-input failure |
| `reasoner` | `ReasonerSuite`, `QuerySuite`: inference, fixpoint behavior, journal-backed justifications, consistency, EL warnings, and graph-pattern queries |
| `core` | `ProjectionSuite`, `KnowledgeBaseSuite`, `DisclosureSuite`, `VerbalizerSuite`: replay and temporal projections, commit validation and atomicity, events, policy and disclosure, and naming/verbalization |
| `lms` | `BeliefSuite`, `SchedulerSuite`: belief updates and decay, derived belief, review logging, retention/elucidation scheduling, and exploration |
| `vocab` | `ModuleSuite`: the merged modules against the unmodified core, including ontology consistency, inference, policies, templates, capture, learning, and ledger scenarios |
| `nix` | `agent-sandbox-sources`: shell analysis, Python syntax checking, and behavioral tests for the isolated-agent HTTPS proxy |

The CLI currently has no dedicated test suite. It is compiled by the full check, while its domain
behavior is exercised through core and vocabulary integration tests. Exercise command parsing,
workspace persistence, and rendering changes through the generated launcher as described below.

Shared test fixtures belong in a `Fixtures` or module-specific fixture object. Prefer realistic
vocabulary shapes from the shipped modules over abstract placeholders when the shape itself is part
of the behavior under test.

## Running the tests

For fast feedback, run the owning module explicitly:

```bash
nix develop --command sbt -batch "logic/testOnly noesis.logic.*"
nix develop --command sbt -batch "journal/testOnly noesis.journal.*"
nix develop --command sbt -batch "reasoner/testOnly noesis.reasoner.*"
nix develop --command sbt -batch "core/testOnly noesis.core.*"
nix develop --command sbt -batch "lms/testOnly noesis.lms.*"
nix develop --command sbt -batch "vocab/testOnly noesis.vocab.*"
```

When already inside `nix develop`, omit `nix develop --command`. To reproduce the ordinary CI gate,
start clean, compile all seven modules, and explicitly execute every test-bearing module:

```bash
nix develop --command sbt -batch \
  "clean;
  compile;
  logic/testOnly noesis.logic.*;
  journal/testOnly noesis.journal.*;
  reasoner/testOnly noesis.reasoner.*;
  core/testOnly noesis.core.*;
  lms/testOnly noesis.lms.*;
  vocab/testOnly noesis.vocab.*"
```

Do not use a plain `sbt test` result as evidence that every suite ran. sbt 2 executes tests
incrementally and can report `Total 0` for an unchanged module. The explicit `testOnly` tasks above
force visible results from every selected suite.

### CLI scenarios

Generate the launcher before a multi-command CLI scenario:

```bash
nix develop --command sbt -batch cli/launcher
target/out/jvm/scala-3.8.4/noesis-cli/noesis --root <temporary-workspace> init
```

Invoke that script once for each subsequent command. Do not script a multi-step scenario with
`sbt cli/run`: sbt merges arguments from multiple `run` commands in one session. Use a disposable
workspace so a test cannot alter the user's default `~/.noesis` data.

## Test design

- Name tests as behavioral claims, for example `"a conclusion derivable from public facts alone is
  public, whatever other derivation paths exist"`, not `"testDisclosure3"`.
- Test observable contracts and invariants. Prefer pinning a property over a magic constant when
  the model is provisional in the specification.
- Cite the relevant `SPEC.md` section in a suite or section comment when the reason for a cluster of
  cases is not obvious.
- Keep tests deterministic and local. The product has no LLM calls or API key, and tests must not
  introduce either. Use Cats Effect resources for temporary files and other acquired state.
- Assert failure behavior and preserved state, not only the happy-path result. Rejected commits,
  malformed persisted input, and undisclosable data must demonstrate their fail-closed guarantees.
- Preserve provenance in assertions. For inferred facts, assert both the conclusion and its
  journal-backed justification; a facts-only test can miss a privacy, derived-belief, or
  contradiction-reporting regression.
- Use exact round trips or golden values for persisted compatibility boundaries. A wire-format
  change needs fixtures for both the old and new representation plus an explicit migration strategy.

MUnit's `assertEquals` requires the obtained and expected values to have matching static types. When
opaque or refined values infer different collection types, ascribe the common type explicitly
instead of weakening the assertion.

## Evidence required by change type

Tests should live at the lowest module that owns the contract, with an integration case whenever the
contract crosses module seams.

- **Vocabulary module:** add it to `Modules.all`, then cover it in `ModuleSuite` against the
  unmodified core. Exercise the merged ontology, rules, policies, item policies, and templates as
  applicable; declarations tested only in isolation are insufficient. Cover property domain and
  range declarations where CLI value typing depends on the ontology. Confirm the merged TBox remains
  consistent, including through `noesis check` when CLI behavior is in scope.
- **Inference rule:** assert the derived fact and its exact premise justification. Cover a negative
  or boundary case that protects monotonicity or termination where applicable.
- **Axiom case:** cover `signature`, `individuals`, Manchester rendering, triple projection,
  `Profile.elWarning`, serialization, and relevant reasoner behavior.
- **Journal operation:** add its JSON round trip to `JournalSuite`, its replay effect to projection
  tests, and its event reconstruction to core tests. Restart/replay must expose the same behavior as
  the live commit path.
- **Policy or disclosure behavior:** cover cascade precedence, boundary values, and fail-closed
  behavior. Derived disclosure cases must include competing derivation paths and justification
  sensitivity where relevant.
- **Fluent or temporal behavior:** cover journal replay, current and point-in-time projections,
  boundary dates, support provenance, and emitted state-change events.
- **Learning behavior:** keep `belief` separate from `truthConfidence`; cover review-log evidence,
  decay/update boundaries, scheduling consequences, and derived-premise handling as applicable.
- **CLI behavior:** exercise the launcher with a disposable workspace and cover parsing, rendered
  output, and persistence/reopen behavior affected by the change.

## Static analysis

Compilation is part of the test gate. Warnings are errors, including unused code, discarded values,
non-`Unit` statements, and safe-initialization diagnostics. Scapegoat and the curated WartRemover
profile in `build.sbt` run during production and test compilation. Do not bypass or locally suppress
these checks without documenting why the exception is safe.

Changes to `flake.nix`, `nix/agent-session.sh`, `nix/agent-run.sh`, or the agent proxy must also run:

```bash
nix flake check
```

The `agent-sandbox-sources` check runs ShellCheck over both shell scripts, compiles
`nix/agent-proxy.py` as Python, and executes `nix/agent-proxy-test.py`. This check is defined by the
flake but is not currently part of either GitHub Actions workflow.

## Mutation testing

Stryker4s evaluates whether the suites detect behavioral mutations. Version 1.0.3 is deliberately
pinned because 1.1.0 crashes while mutating this repository's Scala 3.8 sources.

Run mutation testing for the affected test-bearing module:

```bash
nix develop --command sbt -batch \
  "core/stryker
  --reporters console
  --reporters html
  --reporters json
  --thresholds.high 60
  --thresholds.low 41
  --thresholds.break 40"
```

Replace `core` with `logic`, `journal`, `reasoner`, `lms`, or `vocab` as needed. Reports are written
under `modules/<module>/target/stryker4s-report`. CI runs all six modules independently, retains the
HTML and JSON reports as artifacts, and fails any module whose mutation score is below 40%. Increase
the floor as surviving and uncovered mutants are addressed.

## Continuous integration and reporting

The ordinary `CI` workflow runs the clean compile and all six explicit suite tasks on every branch
push. The `Mutation testing` workflow also runs on every branch push and can be started manually;
its module matrix does not fail fast.

When reporting verification:

- Quote test counts from the command output of that run, not from documentation or source scanning.
- Identify the exact module tasks that ran.
- State every failure and every relevant check that was skipped, with its output or reason.
- For mutation testing, report the affected module's score and the report location.
- Report a specification/implementation disagreement as a finding; do not change intended behavior
  merely to make a test pass.
