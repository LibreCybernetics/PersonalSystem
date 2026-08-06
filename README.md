# Noesis

A single-user knowledge and learning system built on a formal knowledge representation.

Knowledge is stored as description-logic axioms rather than free text. An append-only journal is the
only source of truth; every other view — inferred facts, "what is true now", ledger balances, what
you should review today — is a projection recomputed from it. A learning engine tracks how well you
have internalized each fact and quizzes you to close the gap between what the system knows and what
you know.

[SPEC.md](SPEC.md) is the authority on intended design; [DESIGN.md](DESIGN.md) records the principles
and constraints of the current implementation. This repository is an **MVP** of the spec: the
Knowledge Core and Learning Engine are complete and well covered by tests, the three domain modules
work end to end, and GTK/libadwaita and ScalaFX daily-loop applications plus a specialist CLI are
available. The LLM, MCP and HTTP surfaces are not built. See
[what is and isn't implemented](#what-is-implemented).

## Quick start

```bash
nix develop            # JDK 25, sbt 2.0.4, coursier, scala-cli, metals
sbt cli/launcher       # writes an executable launcher and prints its path
nix run .#gui          # builds and opens the GTK 4/libadwaita application
nix run .#gui-scalafx  # builds and opens the equally supported ScalaFX alternative
```

The launcher lands at `target/out/jvm/scala-3.8.4/noesis-cli/noesis`. It defaults to a workspace at
`~/.noesis`; pass `--root DIR` to use another. Both GUIs use the same default; append `-- --workspace
DIR` to either Nix command for a different workspace. The two append-only sources of truth are `journal.jsonl`
and `reviews.jsonl`; disposable projections such as the Markdown note mirror may live beside them.
On POSIX filesystems Noesis creates or tightens the workspace to owner-only permissions (`0700` for
the directory, `0600` for the files) and rejects symlinked persistence paths.

```bash
noesis init                                   # install the module ontologies

# Structured contacts. Bare handles become noesis:e/ entities.
noesis contact add 'Lía García' --id lia
noesis contact add Sarah --id sarah
noesis contact add Marco --id marco
noesis contact add 'Molina Labs' --id molina --organization
noesis contact method-add lia 'lia@example.com' --kind email --label personal
noesis contact method-add lia '+52 55 1234' --kind phone --label mobile
noesis contact employment-add sarah --at molina --title Researcher
noesis contact interaction-add sarah --with marco --on 2026-07-30 --channel in-person

# Generic semantic capture remains available; prefixed names are vocabulary terms.
noesis assert lia crm:birthday 05-12          # a recurring day: xsd:gMonthDay, no year invented
noesis assert sarah crm:spouseOf marco
noesis assert sarah crm:parentOf lia
noesis assert marco crm:parentOf lia

# Ask what follows from what you said.
noesis entails sarah crm:knows marco          # yes — spouseOf ⊑ partnerOf ⊑ knows
noesis explain sarah crm:knows marco          # ...and the premises it used
noesis query "?p crm:parentOf noesis:e/lia"   # Sarah, Marco

# States change. A contact method retires without deleting its history.
noesis contact method-retire <methodId>
noesis as-of 2026-03-15                       # the graph as it stood in March

# Learning.
noesis show sarah                             # facts and states, tinted with belief
noesis queue                                  # what to review, and why each item was picked
noesis review <itemId> 1.0

# Boundaries and hygiene.
noesis disclose tutor --level public          # what an external agent would be allowed to see
noesis loans                                  # derived from the event ledger, never stored
noesis contact show lia                       # current contact card
noesis contact due                            # follow-ups and reminders
noesis contact export lia --format vcard       # contact methods are omitted by default
noesis contact export lia --format vcard --include-contact-data
noesis check                                  # consistency, annotation policies, OWL profile
noesis export                                 # Turtle

# Portable, checksummed exit and recovery.
noesis archive create /safe/place/noesis-archive
noesis archive verify /safe/place/noesis-archive
noesis archive restore /safe/place/noesis-archive /new/noesis-workspace
```

`noesis --help` lists the top-level commands; `noesis contact --help` lists the PRM operations.
An archive is an inspectable directory containing the two source logs, `manifest.json` with
SHA-256 checksums and format versions, and a `current.ttl` projection. Creation locks both logs
together; verification replays the journal and recomputes the projection. Restore refuses to
overwrite an existing path.

## What is implemented

| Spec area | Status |
|---|---|
| §2.1 GNOME desktop | GTK 4/libadwaita daily loop with explicit first-run initialization, Today/agenda and note capture, reviewed structured facts, local search/entity detail, and learning. A Cats Effect/fs2 Model–View–Update loop serializes typed events and durable effects over the shared application services; run it with `nix run .#gui` |
| §2.2 ScalaFX desktop | Equal Linux/Nix alternative over the same surfaces, stable ids, immutable presentation, effects, and owner lifecycle; only scene graph, JavaFX scheduling, and window lifecycle differ. Run it with `nix run .#gui-scalafx` |
| §3.2 Journal & projections | Dedicated [`journal`](modules/journal/) module with checksummed, versioned, crash-recoverable commit frames, cross-process locking and fsync; state, current-graph, point-in-time and time-travel projections, all rebuilt from it |
| §3.1 Representation | Dedicated [`logic`](modules/logic/) module with the RDFS core plus the OWL role constructs the vocabularies need — symmetry, transitivity, inverses, chains, disjointness, irreflexivity; content-derived stable axiom ids; located partial dates, with recurring days kept separate |
| §3.3 Annotations & cascade | One cascade for sensitivity, utility, confidence and scope: owner override → term policy → module default → behavioral and temporal signals, with decay |
| §3.3.1 Sensitivity | Four levels, per-scope `internal` grants, and the derived-fact rule `min over justifications (max over axioms)` |
| §3.4 Reasoning | Dedicated [`reasoner`](modules/reasoner/) module with forward-chaining closure, **justification tracking**, explicit incomplete results when a configured cap is reached, minimal explanations, consistency checking that rejects a commit *with its justification*, EL profile warnings, and conjunctive graph-pattern queries |
| §3.5 Capture | Intent → operations → validated atomic commit; nothing reaches the journal before the reasoner accepts it |
| §3.6 Fluents | Open, close and supersede; the plain-assertion sugar; current-graph materialization; `state.changed` carrying both old and new value |
| §4 Learning engine | Items drafted from events by policy; belief with α-update and exponential decay by stability; retention and elucidation queues; belief in derived facts; change items at elevated priority; durable review log. `noesis quiz` asks the stored question with its answer withheld, grades against the item's typed answer, regenerates a question whose source fact has changed rather than asking it, and declines to grade what needs a judge |
| §5 Modules | The module contract as a plain value: ontology, rules, policies, item policies, templates, naming schemes, validators, document adapters and agenda producers, merged into one configuration |
| §5.2 Verbalizer | Template-first, always using current names (§7.2) |
| §6 `ll:` | Interlingual hub-and-spoke; translation derived as `Lexeme → Concept → Lexeme`; false friends, cognates, belief-tensor keys |
| §7 `crm:` PRM | Structured names; concurrent email, phone, online and postal contact methods; employment; relationships; interactions; notes; preferences; reminders; follow-up; circles; companion animals; gifts; duplicate-candidate detection; vCard 4.0 and mapped FOAF/RDF interchange; cardinality-free social reasoning |
| §8 `vf:` | ValueFlows alignment; custody, loans and balances as folds over event history |
| §8.5 `note:` | Notes and journaling: blocks as fluents, so per-block history and `as-of` come from §3.6 and the journal gains no operation; sibling order as a fractional index; `[[links]]` resolved against current names into `note:mentions` axioms, never minting an entity; backlinks as a closure projection rather than an index; an `$EDITOR` round-trip that preserves block identity across rewording, moving and re-indenting; a read-only Markdown mirror for `grep` |
| §10 | Local-first, append-only, transparent checksummed archives with verified restore, Turtle, vCard and mapped FOAF/RDF export |

### Built but not reachable

One finished, tested subsystem still has no owner-facing surface. [PRODUCT.md](PRODUCT.md) ranks
this kind of gap above new capability, because exposing what exists is cheaper than building
anything and it sits on the journeys used most.

- **Duplicate candidates are detected and unreachable.** The PRM module computes them; no command
  shows them, so §12.11's owner-confirmed merge has no surface (friction F8).

The structured-capture path that exists because the LLM does not is now navigable. `noesis vocab
search` finds a term by name *or by how it reads*, so "married" reaches `crm:spouseOf`; `noesis
vocab show` gives its domain, range, cascade defaults and an example invocation; and `noesis assert`
shows the verbalization, identifier, Manchester rendering and resolved annotations, writing nothing
until the owner accepts (`--yes` is the only way past it). What the vocabulary still cannot state is
the datatype a data property takes: `PropertyRange` puts its object in the *class* role, so
declaring `xsd:gMonthDay` there would pun it against its datatype role, which ISO/IEC 11179-5 §8.1.2
forbids and the conformance suite enforces (friction F20).

### Not implemented

- **No LLM anywhere.** §3.5's natural-language translation, §4.3's generated cases and the rubric
  grader all need a model. Capture is structured, questions are template-generated, and a
  rubric-graded answer *declines to grade* rather than guessing — a fabricated grade would corrupt
  the review log that §12.3 depends on for refitting the belief model.
- **No MCP gateway** (§9) and **no HTTP API** (§3.8). The disclosure engine both depend on is built
  and tested; what is missing is transport, OAuth and rate limiting.
- **No references module** (§3.7), and therefore no remediation (§4.5), no reading sessions and no
  quotes (§8.5.4). `note:cites` is specified and deliberately unbound until `ref:Quote` exists.
- **Nothing extracts facts from notes** (§8.5.5). Notes are written, linked, searched and edited;
  turning what was written into axioms needs the model that is not here, so `noesis note extract`
  remains a proposal. `note:` therefore contributes no learning items yet, by policy rather than by
  omission — the mechanics of writing are `ItemPolicy.Ignore`, and the facts extracted *from* notes
  would be scheduled by the ordinary cascade.
- **Journal pruning is specified and unbuilt** (§3.2.1). Superseded state accumulates; `noesis
  prune`, `noesis journal size` and `noesis journal discard` are proposals.
- **No production reasoner.** §11 names ELK and HermiT; this is a naive fixpoint over a rule set and
  does not meet §10's "500 ms at 10⁶ axioms". The implementation and its compatibility contract are
  isolated in `noesis-reasoner`; an external engine must preserve journal-backed justifications as
  well as entailment results.
- **No calendar-backed agenda or briefing generation** (§5.2). Module-produced due work is visible
  through `noesis agenda`, `noesis contact due`, and the GUI Today page. Calendar sync, generated
  briefings and end-to-end encryption are unimplemented.

## Layout

```
modules/logic   Persisted semantic language — axioms, literals, annotations, fluents, triples
modules/journal Append-only operation protocol and JSON Lines/in-memory implementations
modules/reasoner Inference, justification tracking, consistency, profile checks and queries
modules/core    Knowledge Core — projections, capture, policy, events, service orchestration
modules/lms     Learning Engine — items, belief, scheduling, question generation
modules/vocab   Vocabulary modules — core upper ontology, crm:, ll:, vf:
modules/app     Shared workspace lifecycle and presentation-neutral owner use cases
modules/cli     Command-line interface
modules/gui-core Toolkit-neutral desktop model, presentation, effects and lifecycle
modules/gui     GNOME GTK/libadwaita renderer and lifecycle adapter (default)
modules/gui-scalafx ScalaFX renderer and JavaFX lifecycle adapter
```

The runtime uses cats-effect, fs2, circe, decline, Java-GI, and ScalaFX. See [DESIGN.md](DESIGN.md) for module
boundaries and dependency rules. Each foundational module has its own README and implementation
specification. [THREAT_MODEL.md](THREAT_MODEL.md) records application assets, trust boundaries,
controls and residual risks. [PRODUCT.md](PRODUCT.md) records who the system serves, the journeys it
must support, the friction ledger and the ordering rule for what to build next; [UX.md](UX.md)
records the conventions the owner-facing surface follows.

## Development

The development toolchain comes from `flake.nix`; dependencies and compiler configuration live in
`build.sbt`. See [TESTING.md](TESTING.md) for the test suites, exact full-run command, scoverage
reports, per-module and aggregate coverage floors, the 100%-changed-production-line gate,
static-analysis and mutation-testing gates, CI behavior, and the evidence required for each kind of
change.

## Isolated coding agents

The flake can run either `nixpkgs#codex` or `nixpkgs#claude-code` in a rootless Bubblewrap sandbox.
Each agent gets a private synthetic Git repository and worktree containing only one committed
revision of Noesis. It does not see this checkout, its Git history, uncommitted files, the host home
directory, `~/.noesis`, SSH/GPG sockets, environment credentials, the Docker socket, or the Nix
daemon.

Create a session from `HEAD`, then start either agent:

```bash
nix run .#agent-session -- create issue-42
nix run .#codex-agent -- issue-42
# or:
nix run .#claude-agent -- issue-42
```

To inspect the exact boundary or run a command without an agent, use the same sandbox through
`nix run .#agent-shell -- issue-42`, optionally followed by a command and its arguments.

The optional second argument to `create` is any local commit or ref:

```bash
nix run .#agent-session -- create review-pr origin/main
```

Only the committed tree at that ref is copied. This is deliberate: commit or otherwise preserve
local source changes that the agent needs before creating its session. The copy is given a new root
commit, so the agent cannot inspect the source repository's history or remotes.

Agent login state is also separate for each provider within each session; host credentials are never
copied or inherited. Authenticate inside the sandbox on first use:

```bash
nix run .#codex-agent -- issue-42 login --device-auth
nix run .#claude-agent -- issue-42 auth login
```

Use a dedicated, revocable account or token with the narrowest practical billing and access limits.
Code run by an agent can read the credentials that the agent itself needs inside that session; this
design protects unrelated host credentials, not a credential deliberately entrusted to the
sandbox. Destroying the session removes its worktree, caches, and agent login state.

Inspect and transfer the result explicitly:

```bash
nix run .#agent-session -- status issue-42
nix run .#agent-session -- diff issue-42
nix run .#agent-session -- export issue-42 > /tmp/issue-42.patch
git apply --check /tmp/issue-42.patch
git apply /tmp/issue-42.patch
NOESIS_AGENT_FORCE=1 nix run .#agent-session -- destroy issue-42
```

`export` is a binary Git diff from the session's synthetic base. New files must be staged or
committed in the session to appear in it; always check `status` before destroying a session. By
default sessions live under `$XDG_STATE_HOME/noesis-agents`, falling back to
`~/.local/state/noesis-agents`. Set `NOESIS_AGENT_STATE_DIR` to put them elsewhere.

### Sandbox boundary

The wrapper starts an empty mount namespace and exposes only the session repository/worktree, its
private state, `/proc`, a minimal `/dev`, and the exact Nix store closure of the pinned tool
environment. The environment contains JDK 25, sbt 2.0.4, the Scala tools, Git, Codex, Claude Code,
and basic POSIX utilities. `/tmp` is an in-memory private filesystem. Linux capabilities, nested user
namespaces, IPC, PID, UTS, cgroup, and network namespaces are separated.

The network namespace has no normal network interface. HTTPS is relayed through a host-side CONNECT
proxy which accepts port 443 only, resolves names outside the sandbox, rejects IP literals and
non-public addresses, and permits only the model providers and Scala/Maven repositories by default.
Add required package hosts deliberately:

```bash
NOESIS_AGENT_EXTRA_DOMAINS=repo.example.org,downloads.example.org \
  nix run .#codex-agent -- issue-42
```

Set `NOESIS_AGENT_NO_DEFAULT_DOMAINS=1` together with
`NOESIS_AGENT_EXTRA_DOMAINS=...` to replace the defaults. The proxy logs allowed and denied hostnames
but cannot see TLS payloads.

When a user systemd manager is available, the wrapper also creates a transient scope with
`MemoryHigh=6G`, `MemoryMax=8G`, `TasksMax=1024`, and `CPUQuota=400%`. Override these with
`NOESIS_AGENT_MEMORY_HIGH`, `NOESIS_AGENT_MEMORY_MAX`, `NOESIS_AGENT_TASKS_MAX`, and
`NOESIS_AGENT_CPU_QUOTA`, or set `NOESIS_AGENT_SYSTEMD_SCOPE=0` to skip the scope.

The host must be Linux with unprivileged user namespaces enabled; on NixOS, leave
`security.unprivilegedUsernsClone` enabled. See [DESIGN.md](DESIGN.md#isolated-agent-security-model)
for the sandbox's security principles, trust boundary and threat model.
