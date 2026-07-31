# Noesis experience

This document governs how the owner-facing surface behaves. [PRODUCT.md](PRODUCT.md) decides *what*
is worth building and for whom; this file decides *how it presents itself* once that is settled.
[DESIGN.md](DESIGN.md) governs internal structure, and its architectural ownership rules bind here
too — most directly, the verbalizer owns naming, so no surface may render an IRI's local part as a
display name.

The surface is a CLI today and `SPEC.md` §2 anticipates HTTP, MCP and graphical clients. The
conventions below are written so that they survive that transition: they constrain the *projection*
and its vocabulary, not the terminal.

---

## 1. Experience principles

1. **The formal representation is the interface.** §1.2 promises a natural surface over a visible,
   editable formal core — not a natural surface that hides it. Axiom ids, Manchester renderings and
   justifications are first-class output the owner can act on, not diagnostics. A view that shows a
   fact and withholds the handle needed to correct it has failed (this is friction F3).

2. **Confirmation is a budget, not a reflex.** §12.1 names confirmation fatigue as the primary
   experience risk, and §1.3 requires human-in-the-loop commits; these pull in opposite directions and
   the resolution is a budget, not the removal of either. **One confirmation per owner intention** —
   not per axiom, not per operation. A capture that expands into six operations confirms once, showing
   all six. Imports and other batches confirm as a queue with accept-all, per-record accept and
   reject. Auto-acceptance is a per-class policy the owner sets deliberately, never a default and
   never inferred from repetition.

3. **A refusal explains itself.** Fail-closed is only trustworthy when it is legible. Every refusal
   states three things: what was refused, which rule refused it, and what the owner can do. The
   reasoner already computes the justification for an inconsistency and the policy that redacted an
   axiom; discarding that at the rendering layer converts a designed guarantee into an apparent
   malfunction. `noesis disclose` does this correctly and is the reference implementation.

4. **Depth is opt-in, and never lost.** Default output answers the question asked. Belief tints,
   justifications, Manchester syntax and provenance are one flag deeper. Nothing that exists in the
   model is unreachable from the surface — where it currently is, that is a ledger entry
   (F7, F8), not a design choice.

5. **One projection, two readers.** Every read command owes the same answer to a person and to a
   program. `--json` is not a convenience feature: it is how §3.8's future API avoids becoming a
   second, divergent view of the same state, and how the Auditor role automates a check they would
   otherwise skip.

6. **The owner's language is not the system's convenience.** Names, diacritics and scripts round-trip
   exactly, in any locale, at every boundary — argument decoding, storage, rendering and export. The
   `LC_ALL` / `sun.jnu.encoding` handling in the launcher exists because `Lía` was mangled before it
   was ever stored. That was diagnosed as an encoding bug; it was an experience bug, and this
   document claims it. Pronouns and current names follow §7.2 without exception: former names are
   `sensitive` and never rendered.

7. **Latency is a number, attached to a step.** §10's budgets — capture round-trip < 3 s p50,
   incremental consistency < 500 ms, review submit < 200 ms — bind the journey steps in
   [PRODUCT.md](PRODUCT.md) §4, not the system in the abstract. An unmeasured budget is an aspiration;
   see F13 for the current, accepted state.

8. **Silence means nothing happened.** A command that changes durable state says what it changed. A
   command that changes nothing says so — `already true — nothing committed` is the correct shape,
   because a silent success is indistinguishable from a no-op the owner did not intend.

---

## 2. Command grammar

The existing surface follows a rule worth stating, because it is not arbitrary and new commands
should keep it:

- **Top-level commands are verbs on the knowledge base**: `assert`, `retract`, `close`, `supersede`,
  `show`, `query`, `entails`, `explain`, `check`, `export`. They act on the graph as a whole.
- **Module commands are `<record>-<verb>`**: `method-add`, `method-retire`, `employment-add`,
  `follow-up-set`. The record type leads because the module's surface is a set of record types, and
  leading with the noun keeps `--help` alphabetically self-grouping.
- **Container commands are nouns**: `contact`, `archive`. They carry no behavior of their own.
- **Handles are positional; everything else is a flag.** A command's required subject reads as
  prose (`noesis contact method-retire <methodId>`); optional structure is named.

Deprecating a command name keeps the old one as an alias, because §5 of `DESIGN.md` makes the owner's
own scripts a compatibility concern.

## 3. Output contract

- **Human rendering is the default.** It goes to stdout and may change freely; it is not a
  compatibility boundary.
- **`--json` emits the same projection** with stable field names. It *is* a compatibility boundary:
  field renames follow the persisted-format rules in [TESTING.md](TESTING.md), with old and new
  fixtures.
- **Diagnostics, warnings and prompts go to stderr**, so stdout stays parseable under `--json`.
- **Exit codes**: `0` success; `1` a refusal the owner can act on (rejected commit, failed
  verification, unknown entity); `2` a usage error. A refusal is not a crash and must not surface as
  a stack trace.
- **Identifiers are shown wherever the thing they identify is shown.** Facts carry axiom ids, items
  carry item ids, records carry record handles. This is what makes principle 1 operational.
- **Empty is stated, not blank.** `(none)`, `(no asserted facts)`, `(nothing due)`.

## 4. Error message rubric

Every failure path produces three parts, in this order. New failure paths are reviewed against this
rubric as part of the evidence [TESTING.md](TESTING.md) requires.

| Part | Question it answers | Example |
|---|---|---|
| **What** | What did not happen | `commit rejected` |
| **Why** | Which rule refused, with the evidence it used | `crm:spouseOf is irreflexive; sarah cannot be her own spouse (premises: …)` |
| **Next** | What the owner can do | `retract <axiomId>, or assert a different value` |

Specific obligations:

- A **rejected commit** names the axioms in the justification, not just the conclusion.
- A **redaction** names the level and, for a derived fact, that no justification was fully
  disclosable — the distinction between "this fact is sensitive" and "everything that implies it is"
  is the owner's decision to make.
- An **unknown handle** is reported with its nearest existing matches, never silently created
  (F4, US-05).
- An **incomplete result** from a resource cap is reported as incomplete. A sound partial answer must
  never render like a complete negative; this is `DESIGN.md` invariant 3 at the surface.
- A **grader that cannot grade** declines. It never guesses, because a fabricated grade corrupts the
  review log §12.3 refits from.

## 5. Confirmation

The budget from principle 2, made concrete:

- A confirmation shows the verbalization, the proposed annotations (sensitivity, scope, utility,
  confidence) and the Manchester rendering — §3.5.5's three views, which today's post-commit report
  gives only after the fact (F2).
- The default answer is **no**. Accepting is an action; declining is the absence of one.
- `--yes` skips the prompt for scripted and tested use, and is the only way to skip it. An
  environment-derived or heuristic skip is not permitted: it would make §1.3 depend on context the
  owner cannot see.
- Batch confirmation shows the whole queue first, then accepts per record or wholesale. Cancelling a
  batch commits nothing — atomicity is already guaranteed by `DESIGN.md` invariant 2, and the surface
  must not appear to offer partial application.

## 6. Locale, encoding and time

- Arguments, storage, rendering and export round-trip any Unicode name exactly. The launcher's
  `LC_ALL` and `sun.jnu.encoding` settings are load-bearing; changing them requires a round-trip test
  with a non-ASCII name.
- Timestamps are stored as UTC instants and displayed in the reader's zone; `--zone` overrides.
  Calendar dates take no zone, ever (§12.12, D11).
- A recurring day such as a birthday renders as a recurring day. Inventing a year to make it a date
  is a data error the surface must not commit on the owner's behalf.
- Verbalization is per configured language and always uses current names (§7.2).

## 7. Applying this to a change

For any change to the owner-facing surface:

1. Name the journey step in [PRODUCT.md](PRODUCT.md) §4 that it affects, or add one.
2. Check the new or changed output against §3, and every new failure path against §4.
3. If it adds a command, check it against §2 and add it to a journey — `ProductTraceSuite` fails
   otherwise, and also checks its help text against §2 and §3.
4. If it removes friction, update the ledger entry in [PRODUCT.md](PRODUCT.md) §6. If it adds
   friction, add one and say which principle makes the trade acceptable.
5. Produce the launcher transcript required by [TESTING.md](TESTING.md), and check it against the
   story's acceptance criteria rather than against your intent.
