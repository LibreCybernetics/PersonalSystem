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

Operational notes for LLM agents working in this repository. Before changing or validating code,
read [README.md](README.md) for what the project is, follow the architecture, principles and
invariants in [DESIGN.md](DESIGN.md), and read [TESTING.md](TESTING.md) for how to verify it. This
file is about how to change the project without breaking it.

**[SPEC.md](SPEC.md) is the authority on intent.** It is a design document, not documentation of the
code — it describes more than is built. When code and spec disagree, that is a finding to report, not
a bug to silently fix. Deliberate departures are recorded in [DESIGN.md](DESIGN.md) and commented at
their definitions; do not "correct" them.

## Commands

```bash
nix develop --command sbt -batch <task>     # everything runs inside the flake devshell
sbt compile                                 # all seven modules + CLI
sbt cli/launcher                            # writes an executable launcher, prints its path
```

- Testing commands, suite responsibilities, change-specific evidence, CI gates, and reporting rules
  are centralized in [TESTING.md](TESTING.md).
- Toolchain: Scala 3.8.4, sbt 2.0.4, JDK 25, all pinned in `flake.nix`. sbt 2 is a `version`+`src`
  override of nixpkgs' sbt 1.x. Do not add a dependency without adding it to `build.sbt`.

## Conventions

- **Scala 3 indentation syntax** throughout: `:` block openers, `end`-less definitions, no braces
  except where an expression needs them. Context bounds use the multi-bound form
  `[F[_]: {Async, UUIDGen}]` and named bounds where an instance must be referenced directly
  (`[F[_]: {Files as files, Async}]`).
- **Comments explain *why*, and cite the spec section.** The prevailing style is a Scaladoc block on
  each type stating what it is and which spec decision forced its shape, e.g. "`max` within a
  justification because you need *all* its premises; `min` across because you need only *one*". Match
  that density — do not add narration of what the code plainly does, and do not strip the rationale.

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
  relationship to a vocabulary module, declare its domain and range explicitly.
- **Range declarations interact with disjointness.** `crm`'s social properties range over `Agent`,
  not `Person`, because Person and Organization are disjoint in core and a narrower range would make
  "I know this company" an inconsistency rather than a fact.
- **decline reads a leading `--` as an option**, so the canonical `xsd:gMonthDay` form `--05-12` is
  unparseable as an argument. `Literal.parse` accepts bare `MM-DD` for this reason; keep that.
- **A yearless date is not a date.** `PartialDate` holds only located dates (`2026`, `2026-05`,
  `2026-05-12`); a recurring day such as a birthday without a year is `java.time.MonthDay` with
  `xsd:gMonthDay`, built by `Literal.anniversary`. Reach for `Literal.asAnniversary` when matching
  occasions — it answers for both, which is the point of the split.
- **JVM argument decoding uses the platform locale.** Under a C locale, `Lía` is mangled *before it
  is stored*. The launcher sets `LC_ALL` and `sun.jnu.encoding`; keep both.
- **A `final case class` cannot be anonymously subclassed.** `DisclosurePolicy` gained a `local` flag
  instead of an override.
- **Covariant type parameters** cannot appear in a method parameter's contravariant position — see
  `Patch.applyTo[B >: A]`.
- **Tuple destructuring in a lambda parameter list** (`((a, b), i) => ...`) is not legal Scala 3;
  destructure in the body.
- **Fluent-backed facts have no `AxiomRecord`.** They are projections, so the annotation cascade
  cannot see them and falls back to a neutral utility. Since `worksAt`, `hasName` and `pronouns` are
  all time-varying *and* the highest-utility properties, this silently mis-ranked name-change items.
  `LearningEngine.utilityRecords` synthesizes records from fluent annotations; anything else
  resolving policy over items must do the same.

## Adding things

**A vocabulary module:** implement `Module` in `modules/vocab` and add it to `Modules.all`.

**A namespace:** bind it in `Namespaces.default`, then add its naming convention to
`modules/conformance/src/test/resources/mdr/naming.json` — `NamingConformanceSuite` fails on a bound
namespace with no documented convention, which is the point. An imported vocabulary's convention is
*descriptive*: record the names as published, including the ones that break your house style
(`geo:lat_long` has an underscore), because upstream is correct by definition and a rule that
forbade it would fail the import rather than the typo. Never coin a term in someone else's
namespace; put the property that uses their class in yours.

**An inference rule:** follow the monotonicity and provenance requirements in
[DESIGN.md](DESIGN.md), implement `Rule` in `reasoner` or a vocabulary module, combine premise
justifications with `Rule.combine` / `combineAll`, and add it to `RdfsRules.all` for core rules or
the module's `rules` for domain rules.

**An axiom case:** change `logic`; `Axiom` has exhaustive matches in `signature`, `individuals`,
`manchester` and
`Triples.of`, plus `Profile.elWarning`. The compiler will find most, but `manchester` and the profile
check are easy to leave wrong rather than missing.

**A journal operation:** change `journal`, then handle the case in `KbState.step` *and*
`Events.forOperation` (the CLI rebuilds learning state by replaying events, so an operation the event
derivation ignores becomes invisible after a restart).

## Reporting

Follow [TESTING.md](TESTING.md) when reporting verification. When you find a spec/code disagreement,
report it rather than resolving it unilaterally.
