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
| `lms` | `BeliefSuite`, `SchedulerSuite`, `ItemSuite`, `QuestionsSuite`, `LearningEngineSuite`: belief updates and decay, derived belief, retention/elucidation scheduling and exploration, item identity and answer grading, template question generation, and the engine's reaction to core events plus review-log recovery |
| `vocab` | `ModuleSuite`: the merged modules against the unmodified core, including ontology consistency, inference, policies, templates, capture, learning, and ledger scenarios |
| `conformance` | `JcsConformanceSuite`, `XsdConformanceSuite`, `IriConformanceSuite`, `LanguageTagConformanceSuite`, `NTriplesConformanceSuite`: corpus-driven conformance to the normative references of SPEC §10.1 |
| `nix` | `agent-sandbox-sources`: shell analysis, Python syntax checking, and behavioral tests for the isolated-agent HTTPS proxy |

The `conformance` module answers a different question from the rest and is gated differently. See
[Conformance testing](#conformance-testing) below before adding to it.

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
nix develop --command sbt -batch "conformance/testOnly noesis.conformance.*"
```

When already inside `nix develop`, omit `nix develop --command`. To reproduce the ordinary CI gate,
start clean, compile all eight modules, and explicitly execute every test-bearing module:

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
- **Anything a normative reference governs:** add the vector to the matching corpus under
  `modules/conformance/src/test/resources`, not to a module suite. A new axiom case must also
  declare its `Profile.elWarning` result and, where it introduces a datatype or identifier form, its
  lexical space and canonical mapping.
- **A new normative citation:** it may only be added to a module `SPEC.md` once a corpus covers it.
  An untested normative citation is a false claim.

## Conformance testing

`modules/conformance` asks whether what we intended matches the specification; every other module
asks whether the implementation does what we intended. The two fail differently, so they are gated
differently.

Corpora live under `src/test/resources`, one directory per specification, each case carrying the
clause it is derived from. Adding coverage means adding a vector, not writing a test. Vectors are
derived from the clauses their `provenance` blocks cite — they are not the specifications' own
published test data; vendoring the upstream corpora is recorded as follow-up F1 in
`modules/conformance/DEVIATIONS.md`.

**A failing conformance case is never skipped.** It is either a bug to fix or a deviation to record
in `DEVIATIONS.md`, with the clause it departs from and what Noesis does instead. A case that fails
and is not recorded there is a bug.

**This module is deliberately outside the Stryker matrix,** for two reasons that point the same way.
Putting external corpora inside a gated module would inflate its coverage: broad conformance cases
kill mutants incidentally, so a 100% score would stop meaning "the unit suite pins this behavior" —
you could delete a precise boundary assertion and CI would stay green, and that erosion is silent
and unrecoverable. And the infrastructure here, manifest loading and the N-Triples reader, is test
scaffolding with a large mutation surface and no product contract to justify holding it at 100%.
`DEVIATIONS.md` is the gate instead.

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
  --thresholds.high 100
  --thresholds.low 100
  --thresholds.break 99"
```

Replace `core` with `logic`, `journal`, `reasoner`, `lms`, or `vocab` as needed. Reports are written
under `modules/<module>/target/stryker4s-report`. CI runs those six modules independently and retains
the HTML and JSON reports as artifacts.

**All six modules score 100%, and a change that drops any of them below that fails CI.** The
`conformance` module is deliberately not among them — see [Conformance testing](#conformance-testing).
`--thresholds.break 99` is the strictest value Stryker4s accepts — it requires `break` to be
strictly below `low` — so the workflow additionally reads the JSON report and fails on any mutant
left `Survived` or `NoCoverage`. That check, not the threshold, is the real gate.

`Ignored` mutants do not count against the score. They are Stryker4s's `static` category: values
computed once during object initialization, such as the vocabulary modules' axiom lists, which the
harness cannot re-evaluate per mutant. `CompileError` mutants do not count either.

### Killing a mutant that no test can distinguish

Some mutants are *equivalent*: they change the source without changing behavior for any reachable
input, so no honest assertion can detect them. Do not contort a test to chase one, and do not weaken
an assertion around it. Fix the code instead — an equivalent mutant is almost always a redundant
branch or an unreachable guard:

- A branch whose two sides compute the same answer is dead. `if xs.isEmpty then None else …` in front
  of a fold that already yields `None` for the empty case is one branch, not two.
- A guard that a later clamp, truncation or `take` already enforces is redundant. Prefer
  `elapsed.max(0.0)` over an `if elapsed <= 0` special case, and let `take(0)` stand in for an
  `if slots == 0` early return.
- A threshold you cannot land on exactly is untestable. Give it a name (`Scheduler.minEntropy`) so a
  test can construct a value that sits exactly on the boundary, and pin both sides — the boundary is
  usually the behavior worth pinning anyway.
- Genuinely unreachable defensive code should be reachable from the module's own tests. Widening a
  helper to `private[module]` and testing it directly is preferable to deleting a guard that a future
  caller will need, or to leaving it uncovered.

When a strict/non-strict comparison survives, the missing test is nearly always the exact-equality
case: a grade of exactly 0.6, a belief exactly at its retention target, a utility exactly at the
suspend threshold. Those cases decide real behavior and belong in the suite regardless.

## Continuous integration and reporting

The ordinary `CI` workflow runs the clean compile and all seven explicit suite tasks on every branch
push. The `Mutation testing` workflow also runs on every branch push and can be started manually;
its module matrix does not fail fast.

When reporting verification:

- Quote test counts from the command output of that run, not from documentation or source scanning.
- Identify the exact module tasks that ran.
- State every failure and every relevant check that was skipped, with its output or reason.
- For mutation testing, report the affected module's score and the report location.
- Report a specification/implementation disagreement as a finding; do not change intended behavior
  merely to make a test pass.
