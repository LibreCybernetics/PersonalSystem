# Noesis

A single-user knowledge and learning system built on a formal knowledge representation.

Knowledge is stored as description-logic axioms rather than free text. An append-only journal is the
only source of truth; every other view — inferred facts, "what is true now", ledger balances, what
you should review today — is a projection recomputed from it. A learning engine tracks how well you
have internalized each fact and quizzes you to close the gap between what the system knows and what
you know.

[SPEC.md](SPEC.md) is the design document and the authority on intent. This repository is an **MVP**
of it: the Knowledge Core and Learning Engine are complete and well covered by tests, the three
domain modules work end to end, and the LLM, MCP and HTTP surfaces are not built. See
[what is and isn't implemented](#what-is-implemented).

## Quick start

```bash
nix develop            # JDK 25, sbt 2.0.4, coursier, scala-cli, metals
sbt cli/launcher       # writes an executable launcher and prints its path
```

The launcher lands at `target/out/jvm/scala-3.8.4/noesis-cli/noesis`. It defaults to a workspace at
`~/.noesis`; pass `--root DIR` to use another. A workspace is two append-only files —
`journal.jsonl` and `reviews.jsonl` — and nothing else.

```bash
noesis init                                   # install the module ontologies

# Capture. Bare words become entities; prefixed names are vocabulary terms.
noesis assert lia rdf:type crm:Person
noesis assert lia rdfs:label Lía
noesis assert lia crm:birthday 05-12          # a yearless partial date
noesis assert sarah rdfs:label Sarah
noesis assert marco rdfs:label Marco
noesis assert molina rdfs:label 'Molina Labs'
noesis assert sarah crm:spouseOf marco
noesis assert sarah crm:parentOf lia
noesis assert marco crm:parentOf lia
noesis assert sarah crm:worksAt molina        # time-varying, so this opens a fluent

# Ask what follows from what you said.
noesis entails sarah crm:knows marco          # yes — spouseOf ⊑ partnerOf ⊑ knows
noesis explain sarah crm:knows marco          # ...and the premises it used
noesis query "?p crm:parentOf noesis:e/lia"   # Sarah, Marco

# States change. This is one supersession, not a delete plus an insert.
noesis supersede sarah crm:worksAt noesis:e/acme --on 2026-07-01
noesis as-of 2026-03-15                       # the graph as it stood in March

# Learning.
noesis show sarah                             # facts and states, tinted with belief
noesis queue                                  # what to review, and why each item was picked
noesis review <itemId> 1.0

# Boundaries and hygiene.
noesis disclose tutor --level public          # what an external agent would be allowed to see
noesis loans                                  # derived from the event ledger, never stored
noesis check                                  # consistency, annotation policies, OWL profile
noesis export                                 # Turtle
```

`noesis --help` lists all 18 subcommands.

## What is implemented

| Spec area | Status |
|---|---|
| §3.2 Journal & projections | JSON Lines append-only journal; state, current-graph, point-in-time and time-travel projections, all rebuilt from it |
| §3.1 Representation | RDFS core plus the OWL role constructs the modules need — symmetry, transitivity, inverses, chains, disjointness, irreflexivity; content-derived stable axiom ids; partial dates |
| §3.3 Annotations & cascade | One cascade for sensitivity, utility, confidence and scope: owner override → term policy → module default → behavioral and temporal signals, with decay |
| §3.3.1 Sensitivity | Four levels, per-scope `internal` grants, and the derived-fact rule `min over justifications (max over axioms)` |
| §3.4 Reasoning | Forward-chaining closure with **justification tracking**, minimal explanations, consistency checking that rejects a commit *with its justification*, EL profile warnings, conjunctive graph-pattern queries |
| §3.5 Capture | Intent → operations → validated atomic commit; nothing reaches the journal before the reasoner accepts it |
| §3.6 Fluents | Open, close and supersede; the plain-assertion sugar; current-graph materialization; `state.changed` carrying both old and new value |
| §4 Learning engine | Items drafted from events by policy; belief with α-update and exponential decay by stability; retention and elucidation queues; belief in derived facts; change items at elevated priority; durable review log |
| §5 Modules | The module contract as a plain value: ontology, rules, policies, item policies and templates, merged into one configuration |
| §5.2 Verbalizer | Template-first, always using current names (§7.2) |
| §6 `ll:` | Interlingual hub-and-spoke; translation derived as `Lexeme → Concept → Lexeme`; false friends, cognates, belief-tensor keys |
| §7 `crm:` | Cardinality-free relationships, the `colleagueOf` chain, the `metamourOf` rule, renames as supersessions |
| §8 `vf:` | ValueFlows alignment; custody, loans and balances as folds over event history |
| §10 | Local-first, append-only, Turtle export |

### Not implemented

- **No LLM anywhere.** §3.5's natural-language translation, §4.3's generated cases and the rubric
  grader all need a model. Capture is structured, questions are template-generated, and a
  rubric-graded answer *declines to grade* rather than guessing — a fabricated grade would corrupt
  the review log that §12.3 depends on for refitting the belief model.
- **No MCP gateway** (§9) and **no HTTP API** (§3.8). The disclosure engine both depend on is built
  and tested; what is missing is transport, OAuth and rate limiting.
- **No references module** (§3.7), and therefore no remediation (§4.5).
- **No production reasoner.** §11 names ELK and HermiT; this is a naive fixpoint over a rule set and
  does not meet §10's "500 ms at 10⁶ axioms". Everything downstream consumes `Closure`, so
  substituting a real reasoner is one implementation swap rather than a redesign.
- **No agenda service** (§5.2), no sync, no end-to-end encryption.

## Layout

```
modules/core    Knowledge Core — journal, projections, reasoner, query, policy, verbalizer
modules/lms     Learning Engine — items, belief, scheduling, question generation
modules/vocab   Vocabulary modules — core upper ontology, crm:, ll:, vf:
modules/cli     Command-line interface
```

Dependencies point one way only: `core` knows nothing about learning or modules, `lms` knows nothing
about the domain vocabularies. Everything is on the typelevel stack — cats-effect, fs2, circe,
decline, munit.

## Development

```bash
sbt core/testOnly 'noesis.core.*'   # 112
sbt lms/testOnly 'noesis.lms.*'     #  36
sbt vocab/testOnly 'noesis.vocab.*' #  41
```

Note that plain `sbt test` is incremental in sbt 2 and reports `Total 0` for modules whose inputs
have not changed. Use the explicit `testOnly` forms above when you want to see all 189 tests run.

The build treats warnings as errors and enables unused, discarded-value, non-`Unit` statement and
safe-initialization diagnostics. Scapegoat and a curated WartRemover safety profile run as compiler
plugins for both production and test compilation.

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
This limits accidental network access and straightforward exfiltration, but an allowed provider
host remains an exfiltration channel and receives repository content as part of normal agent use.
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

This is strong process isolation, not a virtual machine. It shares the host kernel and maps back to
the invoking user, so a kernel/user-namespace escape would regain that user's authority. For
actively hostile native code, run the flake under a dedicated OS account with no private files, or
inside a disposable VM. Same-UID kernel facilities such as the user's kernel keyring are likewise
not a credential boundary. The host must be Linux with unprivileged user namespaces enabled; on
NixOS, leave `security.unprivilegedUsernsClone` enabled.

Codex is intentionally run with `danger-full-access` and Claude with `acceptEdits` *inside* the
outer sandbox. Those settings let the agents use the isolated worktree without layering a weaker or
fail-open inner sandbox over Bubblewrap; they do not grant access outside the outer namespace.

## Design notes

**The journal is the only thing written.** The state fold, the reasoner closure, learning items and
ledger balances are all projections, cached in memory and invalidated on commit. A CLI invocation
reads the two append-only files and recomputes everything else, so no on-disk state can go stale.

**Justifications are shared infrastructure.** Disclosure filtering, belief in derived facts and
contradiction messages are the same data viewed three ways. Computing it once in the reasoner is what
keeps those three from drifting apart.

**Sensitivity fails closed.** Unlabeled assertions default to `personal`, not `public`; a premise
that cannot be resolved is treated as `sensitive`; `sensitive` is undisclosable regardless of grants.

**Belief is not confidence.** `belief` is how well the owner knows a fact; `truthConfidence` is how
likely it is to be true. They are never combined.

Two model decisions depart from a literal reading of the spec, both commented at their definitions:

- `Fluent.isOngoing` also requires an absent `endReason`, not only an absent `validTo`. A
  supersession whose boundary date is unknown has definitely ended, and treating it as ongoing put
  two simultaneous current employers into the current graph.
- `IrreflexiveProperty` was added to the axiom language. Without it the spec's own
  `worksAt ∘ worksAt⁻ ⊑ colleagueOf` makes everyone their own colleague.

On the open questions in §12, the code takes these positions: the EL profile is checked and warned
about but never enforced, since §3.1 sets DL as the ceiling; justification count and size are capped
(§12.4); agent reads are weighted far below owner reads in utility signals, and every session
reserves a slice for low-utility items so a mis-scored fact stays discoverable (§12.10).
