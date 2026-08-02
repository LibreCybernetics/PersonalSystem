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
- **High-cardinality relations are summarized, with a route.** A view that would list fourteen
  blocks states the count and names the command that lists them. This is principle 4, and it is
  *presentation only* — the count is the true count, and summarizing is never a substitute for the
  sensitivity cascade deciding what may be shown at all (SPEC §8.5.8).

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

### 5.1 Model proposals

A proposal is a confirmation whose content the owner did not write, which raises the stakes of every
rule above rather than changing them.

- **The queue is summarized before it is walked.** How many proposals, over how many blocks, at what
  sensitivity, and how many are already entailed. An owner who cannot see the size of the batch
  cannot decide whether to review it now, and will accept it wholesale instead.
- **Already-entailed proposals are separated, not hidden.** §3.5.4's redundancy check makes them
  cheap to skip; presenting them mixed in spends attention on nothing.
- **Every proposal shows its source.** The block, or the reference and locator, so the owner can
  check the claim against what was actually written.
- **A rejection is remembered.** Re-running extraction over unchanged text must not re-propose what
  was already declined, or the queue punishes the owner for using it twice.
- **Confidence is shown and never acted on.** It orders the queue; it never auto-accepts. §1.3 admits
  no threshold above which the owner stops being asked.
- **The model and digest that produced a proposal are shown on request and recorded on commit.**

## 6. Long-running work

Local inference is seconds to minutes, not milliseconds, and a reading session is longer still. §10's
capture budget does not apply to it and must not be quoted as though it did.

- **Say what is happening, and roughly how far along.** A silent process is indistinguishable from a
  hung one, which is principle 8 applied to work that takes time.
- **Interruption is safe by construction.** Nothing is committed until confirmation, so `Ctrl-C`
  during extraction or reading loses proposals and nothing else. Say so rather than making the owner
  find out.
- **Budgets, per step:** review submit < 200 ms and structured capture < 3 s p50 stay as §10 sets
  them. Extraction over one block should be under 10 s p50 on a small local model; a reading session
  is bounded by the text and reports progress instead of promising a time. Any budget without a
  measurement is an aspiration — see F13.

## 7. The editing round-trip

Blocks are journaled state, so a text editor edits a *rendering* of them and the result is diffed
back (F16). That contract has consequences the surface owes the owner:

- **Block identity survives editing.** Rewording, re-indenting or moving a line keeps its id; only a
  genuinely new line mints a new block. Every extracted fact, quote and link points at those ids, so
  a diff that mints ids freely silently detaches the knowledge from the writing.
- **The materialized buffer shows ids for blocks that carry knowledge**, so the owner can see which
  lines are load-bearing before rewriting them.
- **A conflicting edit is reported, never merged.** If the note changed underneath the editor, the
  save is refused with both versions available — fail-closed, as everywhere else.
- **The read-only Markdown mirror is a projection.** It is safe to delete, is rebuilt
  deterministically, and is never read back as truth.

## 8. Locale, encoding and time

- Arguments, storage, rendering and export round-trip any Unicode name exactly. The launcher's
  `LC_ALL` and `sun.jnu.encoding` settings are load-bearing; changing them requires a round-trip test
  with a non-ASCII name.
- Timestamps are stored as UTC instants and displayed in the reader's zone; `--zone` overrides.
  Calendar dates take no zone, ever (§12.12, D11).
- A recurring day such as a birthday renders as a recurring day. Inventing a year to make it a date
  is a data error the surface must not commit on the owner's behalf.
- Verbalization is per configured language and always uses current names (§7.2).

## 9. Applying this to a change

For any change to the owner-facing surface:

1. Name the journey step in [PRODUCT.md](PRODUCT.md) §4 that it affects, or add one.
2. Check the new or changed output against §3, and every new failure path against §4.
3. If it adds a command, check it against §2 and add it to a journey — `ProductTraceSuite` fails
   otherwise, and also checks its help text against §2 and §3.
4. If it removes friction, update the ledger entry in [PRODUCT.md](PRODUCT.md) §6. If it adds
   friction, add one and say which principle makes the trade acceptable.
5. Produce the launcher transcript required by [TESTING.md](TESTING.md), and check it against the
   story's acceptance criteria rather than against your intent.

## 10. GNOME desktop surface

The desktop client follows every principle above and adds no second vocabulary for the same state.
Its first release is the J16 daily loop in [PRODUCT.md](PRODUCT.md); CLI-only specialist operations
remain explicit routes rather than controls that look present but do nothing.

### 10.1 Window and navigation

- One adaptive libadwaita window contains Today, Capture, Learn and Search. Entity detail is reached
  from Search and returns through ordinary navigation. The split view collapses into page navigation
  at narrow widths; it does not open a stack of secondary windows.
- First run is a page, not a background side effect. It states the exact workspace path and privacy
  guarantee; only **Start Noesis** creates anything.
- Every surface has a stable id (`gui:today`, `gui:capture-fact`, and so on) used by product
  traceability and deterministic interaction transcripts. These ids are not displayed to the owner.

### 10.2 Reactive behavior

- GTK signals become typed events. Rendering is a function of one immutable model; widget-local
  state is never another authority on whether a commit happened or which search result is current.
- Work in flight is named and visible. Controls that would start the same durable action are disabled
  until it finishes. Closing after **Commit fact**, **Save note** or **Start Noesis** waits for the
  atomic result rather than cancelling between journal append and projection refresh.
- Explicit search requests are serialized through the event loop. An empty query has an explicit
  empty state and never reuses the last result invisibly.
- A success toast may summarize an already-visible result. It never carries the only copy of an id,
  warning or next step. Failures remain until dismissed or resolved and obey §4 in full.

### 10.3 Capture and learning

- Fact capture selects an existing entity or an explicitly marked new one, a vocabulary term and a
  value parsed according to that term. A bare typo cannot mint an entity as a side effect of focus
  or autocomplete.
- The Review step shows verbalization and resolved annotations first. Axiom id and Manchester form
  are one disclosure level deeper but present before commit. **Cancel** is the default; one
  **Commit fact** action confirms the whole intention.
- Note mode is for the owner's text when it is not yet a formal claim. **Save note** is the explicit
  durable action; it does not run extraction.
- Learn always shows why the item was selected, withholds the answer until submission and uses text
  as well as color for belief/outcome. A grader that cannot judge produces the §4 refusal, not an
  enabled Submit button that silently does nothing.

### 10.4 Accessibility and local privacy

- Controls have programmatic names, keyboard focus order follows visual order, standard navigation
  shortcuts work, and status changes are announced. Belief, errors and selection never depend on
  hue alone. System text scaling, contrast, reduced-motion and right-to-left behavior are honored.
- The GUI uses the local-owner disclosure policy. It sends nothing over a network, copies nothing to
  the clipboard automatically and registers no desktop search provider or notification content in
  the first release.
- Window geometry may be desktop configuration. Search text, capture drafts, selected entities,
  names and other knowledge content are never stored in desktop settings.
