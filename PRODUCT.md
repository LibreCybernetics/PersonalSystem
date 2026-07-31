# Noesis product

This document is the authority on **for whom, and to what end**. [SPEC.md](SPEC.md) is the authority
on intended design; this file records who that design serves, what they are trying to accomplish, and
how a change earns its place. [UX.md](UX.md) governs how the surface behaves once a change is
justified; [DESIGN.md](DESIGN.md) governs how it is built; [TESTING.md](TESTING.md) governs how it is
verified.

A capability in `SPEC.md` that serves no journey here is a finding, not a feature. A shipped command
that appears in no journey here is a finding too — `ProductTraceSuite` enforces the second direction
mechanically, because the first cannot be automated and the second can. It derives the command tree
from the CLI's own typed AST, so this document is checked against what ships rather than against a
description of it.

Nothing here overrides `DESIGN.md`. Where a friction can only be removed by weakening an
implementation invariant, the invariant wins and the friction is recorded as accepted below, with the
principle that justifies the cost. Convenience does not buy its way past fail-closed.

---

## 1. Actors

### 1.1 The owner, and the roles the owner occupies

`SPEC.md` §1.1 fixes exactly one human principal. Personas in the ordinary product sense would
therefore be a fiction. What genuinely varies is the *situation* the same person is in: attention,
stakes and tolerance differ enough between these six that a design good for one is often wrong for
another.

| Role | When | Attention available | Characteristic failure |
|---|---|---|---|
| **Capturer** | Mid-conversation, or just after | Seconds. Will not read documentation | The fact is lost, or entered wrongly and never noticed |
| **Reader** | An hour with a chapter or a paper | Long, deliberate, easily broken | Highlights accumulate and are never revisited; the reading leaves no trace worth having |
| **Learner** | Daily, briefly | Minutes, willing but impatient | The queue stops being worth trusting, so the habit dies |
| **Curator** | Occasionally, deliberately | High | A wrong fact is discovered but is expensive to correct, so it stays |
| **Auditor** | Rarely, high stakes | High, adversarial | Cannot answer "what does this agent actually see?" and so grants nothing, or grants blindly |
| **Exiter** | Once, possibly under pressure | Whatever is left | The archive does not restore, and the knowledge is gone |

The Capturer and the Exiter are the two roles whose failures are unrecoverable: a fact never captured
cannot be reviewed, and a corrupt exit ends the system. They get the strictest acceptance criteria.

### 1.2 Non-human actors

These have requirements of their own, and they are not the owner's requirements.

- **The MCP agent** (§9, unimplemented) needs a stable, filtered projection and a comprehensible
  refusal. An agent that receives an opaque denial retries, and retry against a privacy boundary is
  the failure mode that matters.
- **The coding agent** ([AGENTS.md](AGENTS.md)) needs the product intent to be legible in the
  repository, because it cannot ask the owner and cannot observe use. Every process rule below has an
  agent-executable form for this reason.
- **The downstream consumer** of an export — a phone's address book reading vCard, another system
  reading Turtle or FOAF — needs output that is correct without Noesis present to explain it. §5 of
  `DESIGN.md` makes this a compatibility boundary, which means the export surface is a product
  surface, not a convenience.

---

## 2. Outcomes

What the owner is actually trying to get, in priority order. Every story below serves one of these.

1. **Nothing worth knowing is lost.** Capture is fast enough and cheap enough to happen at the moment
   the fact is heard, not later.
2. **What the system knows, the owner also knows.** The gap between the knowledge base and the
   owner's memory closes on the facts that matter, and the owner can tell it is closing.
3. **The record is trustworthy.** Facts are correct, corrections are cheap, and the history of how a
   fact came to be believed is inspectable rather than asserted.
4. **Time is handled honestly.** States that changed are recorded as change, not as contradiction or
   deletion; the past remains answerable.
5. **The boundary holds, visibly.** The owner can see, before granting anything, exactly what an
   external party would receive.
6. **Leaving is always possible.** The data outlives the program, and the exit path is exercised
   rather than assumed.

## 3. Non-goals

Stated so that a plausible-sounding request can be rejected quickly and consistently.

- **Multi-user anything.** No accounts, sharing, roles or collaboration. §1.1 is structural: it is
  why access control targets external parties only.
- **A social network.** `crm:` models the owner's knowledge *about* people; it never contacts them,
  and no fact is gathered without the owner entering it.
- **Automatic capture.** No mail, calendar or location scraping into the journal. §12.12 makes the
  reason explicit for place; it generalizes. Import is explicit, file-based and confirmable.
- **A general-purpose triple store.** The journal is the truth and the projections are derived; a
  request that amounts to "let me write directly to the graph" is a request to delete the product.
- **Convenience that defeats the sensitivity model.** No "just show me everything" flag, no
  disclosure default other than `public`.
- **Engagement.** Streaks, gamification and notification pressure are out of scope. §4's scheduler
  optimizes retention of things worth knowing, not daily active use.

---

## 4. Journeys

Each journey is the end-to-end path for one situation. The **must already know** column is the
important one: it is where undiscoverable design becomes visible instead of arguable. Friction is
graded Low / Medium / High and carries a ledger id (§6) when it is real.

Journeys are written against the launcher (`sbt cli/launcher`), which is what the owner actually runs.

### J1 — The first hour

*Intent:* go from an empty machine to a knowledge base worth keeping.
*Roles:* Capturer, Curator. *Outcomes:* 1, 3.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that a workspace exists and must be created | `noesis init` | workspace at `~/.noesis`; module ontologies installed | Low |
| 2 | nothing | `noesis contact add 'Lía García' --id lia` | a person entity; the handle is optional and derived from the name otherwise | Low |
| 3 | the term `crm:birthday`, and that a yearless date is legal | `noesis assert lia crm:birthday 05-12` | committed, then reported | **High** — F1, F2 |
| 4 | nothing | `noesis show lia` | facts and states, belief-tinted | Low |
| 5 | that consistency is checkable at all | `noesis check` | consistency, annotation policy, profile warnings | Medium — F11 |

*Verdict:* the shape is right and step 3 is where new owners stop. The system asks them to know a
vocabulary it will not show them (F1), and then reports the commit rather than confirming it (F2),
so the one moment designed to teach the formal representation teaches nothing.

### J2 — Capturing something just heard

*Intent:* record a fact in the seconds available after hearing it.
*Roles:* Capturer. *Outcomes:* 1, 3.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that `crm:spouseOf` is the term | `noesis assert sarah crm:spouseOf marco` | commit | **High** — F1 |
| 2 | that no confirmation is coming | — | the axiom is printed after it is durable | **High** — F2 |
| 3 | that bare handles mint entities | — | `sara` and `sarah` are two different people, silently | **High** — F4 |
| 4 | that entailment is worth checking | `noesis entails sarah crm:knows marco` | yes | Low |
| 5 | that explanation exists | `noesis explain sarah crm:knows marco` | the premises used | Low — the system's best moment |
| 6 | that an encounter is a record, not an assertion | `noesis contact interaction-add sarah --with marco --on 2026-07-30 --channel in-person` | interaction record | Medium — two capture grammars for one situation |
| 7 | that free text has a home | `noesis contact note-add sarah 'prefers mornings'` | note, `personal` by default | Low |

*Verdict:* steps 4 and 5 are the product working exactly as intended — the owner asserts one fact and
is shown a second one they did not state, with its provenance. Steps 1–3 are the cost of reaching
them, and that cost is paid at the worst possible moment.

### J3 — The daily review

*Intent:* close the gap between what the system knows and what the owner remembers.
*Roles:* Learner. *Outcomes:* 2.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | nothing | `noesis queue` | ranked items with weight, belief, utility, and *why each was chosen* | Low — the reason line is a real strength |
| 2 | nothing | `noesis quiz` | the question, with the answer withheld; choices when the ontology supplies distractors | Low |
| 3 | nothing | — | graded against the item's typed answer, belief and stability updated, review logged | Low |
| 4 | that items can be inspected | `noesis items` | every item with belief, stability, review and lapse counts | Low |

*Verdict:* was the most serious gap in the product, and is closed. `modules/lms` already contained a
complete `Question` model — prompt, typed answer, ontology-grounded distractors, staleness against
the source fact — and the CLI reached none of it, so the owner was shown the answer and asked to
grade themselves on whether they knew it. `noesis quiz` asks, grades against the typed answer, and
logs the outcome, which is what makes the review log evidence about recall rather than about
self-assessment. `noesis review <itemId> <grade>` remains, and is now what it should always have
been: the way to record a judgement no grader can make, not the ordinary path.

### J4 — Before I see someone

*Intent:* walk into a conversation with what matters loaded.
*Roles:* Learner, Capturer. *Outcomes:* 2, 4.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that the agenda lives under `contact` | `noesis contact due` | *all modules'* dated obligations | Medium — F6 |
| 2 | nothing | `noesis contact show lia` | current card: methods, employment, recent interactions | Low |
| 3 | nothing | `noesis show lia` | the belief-tinted fact view | Low — two "show" verbs, two answers |
| 4 | that loans are derived, not stored | `noesis loans` | what is out and what is borrowed | Low |
| 5 | basic graph-pattern syntax | `noesis query "?p crm:parentOf noesis:e/lia"` | matching bindings | Medium — F1 again, in query form |

*Verdict:* step 1 is misfiled. `contact due` runs every module's agenda producer, which is exactly
§5.2's shared agenda — but it is reachable only through the PRM namespace, so the cross-module
promise is invisible and `README.md` describes it as a PRM feature.

### J5 — Something changed

*Intent:* record a change as change, without losing what was true before.
*Roles:* Curator. *Outcomes:* 3, 4.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that a job change is one state, not two facts | `noesis supersede sarah crm:worksAt molina --on 2026-06-01` | old state closed, new opened, atomically | Low — the model earning its keep |
| 2 | that a retired method is not a deleted one | `noesis contact method-retire <methodId>` | leaves the card, stays in history | Low |
| 3 | that ending differs from replacing | `noesis close sarah crm:worksAt --on 2026-06-01` | state closed with no successor | Low |
| 4 | nothing | `noesis contact employment-add sarah --at molina --title Researcher` | new employment record | Low |
| 5 | that the past is still answerable | `noesis as-of 2026-03-15` | the graph as it stood | Low |

*Verdict:* the strongest journey in the product. Every step does something a flat contact manager
cannot, and the friction is near zero once the owner knows the three verbs exist. Steps 1 and 5
together are the clearest demonstration of why the journal is the truth.

### J6 — I got it wrong

*Intent:* correct a mistake cheaply enough that it actually gets corrected.
*Roles:* Curator. *Outcomes:* 3.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that correction needs an axiom id | `noesis journal --limit 20` | raw journal entries | **High** — F3 |
| 2 | how to find the right id among them | — | manual reading, then copy | **High** — F3 |
| 3 | nothing | `noesis retract <axiomId>` | retraction committed | Low, once reached |
| 4 | that contradictions surface on commit | `noesis check` | inconsistency with its justification | Low |
| 5 | that "why does it think that?" is answerable | `noesis explain <s> <p> <v>` | minimal justifications | Low |

*Verdict:* the mechanism is correct and the ergonomics are not. A typo made in J2 step 1 costs a
journal dump, visual id matching and a copy. The Curator role is the one most likely to abandon a
correction, and this is the reason.

### J7 — What can this agent see?

*Intent:* decide whether to grant an external party access, on evidence.
*Roles:* Auditor. *Outcomes:* 5.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that disclosure is simulable before it is granted | `noesis disclose tutor --level public` | per-axiom disclose/redact with the reason for each redaction | Low — genuinely strong |
| 2 | that scopes are grantable individually | `noesis disclose tutor --level internal --scope org:acme` | that scope's knowledge and nothing else | Low |
| 3 | that policy violations are checkable | `noesis check` | annotation policy violations | Low |

*Verdict:* the best-served journey, and the only one where a "dry run before you commit to anything"
affordance already exists. It is the model the rest of the product should copy — see F2.

### J8 — Leaving, and coming back

*Intent:* prove the data outlives the program.
*Roles:* Exiter. *Outcomes:* 6.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | nothing | `noesis archive create /safe/place/noesis-archive` | both logs locked together, manifest, checksums, Turtle projection | Low |
| 2 | that verification replays rather than trusting | `noesis archive verify /safe/place/noesis-archive` | checksums, replay and recomputed projection | Low |
| 3 | that restore refuses to overwrite | `noesis archive restore /safe/place/noesis-archive /new/workspace` | a fresh workspace | Low |
| 4 | that the graph is exportable independently | `noesis export` | Turtle | Low |
| 5 | that contact data is withheld by default | `noesis contact export lia --format vcard` | vCard without methods unless asked | Low — correct default, discoverable only via `--help` |

*Verdict:* complete and honest. This journey is the one the repository already treats as a product
surface, and it shows.

### J9 — Bringing in an address book I already have

*Intent:* start from existing data without importing existing mistakes.
*Roles:* Curator, Capturer. *Outcomes:* 1, 3.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that import can be rehearsed | `noesis contact import contacts.vcf --format vcard --dry-run` | parse and validation results, nothing committed | Low — the affordance F2 lacks |
| 2 | nothing | `noesis contact import contacts.vcf --format vcard` | committed contacts | Medium — F5, no per-record confirmation |
| 3 | that structured methods beat free text | `noesis contact method-add lia 'lia@example.com' --kind email --label personal` | typed method | Low |
| 4 | that addresses are structured and `sensitive` | `noesis contact address-add lia '…' --locality 'Mexico City' --country MX` | structured postal address | Low |
| 5 | that duplicates are candidates, never merges | — | duplicate candidates surfaced, never applied | Medium — detection exists, no command reaches it |

*Verdict:* `--dry-run` on step 1 is the correct pattern and it exists in exactly one place. Step 5 is
implemented in `vocab` (duplicate-candidate detection) with no owner-facing route to it; §12.11 makes
owner-confirmed merges a requirement, so the missing surface is the whole feature.

### J10 — Keeping a relationship alive

*Intent:* the long tail of PRM — the reason a personal system beats a notes file.
*Roles:* Capturer, Learner. *Outcomes:* 1, 2, 4.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that cadence is a plan, not a reminder | `noesis contact follow-up-set sarah --every 30` | overdue derived from last qualifying interaction | Low |
| 2 | that one-off prompts differ from cadence | `noesis contact reminder-add sarah --due 2026-09-01 --occasion 'ask about the move'` | reminder record | Low |
| 3 | that relationships are reified | `noesis contact relationship-add sarah marco --kind spouse` | a relationship record with its own annotations | Medium — overlaps `assert … crm:spouseOf …` with no guidance on which to use |
| 4 | nothing | `noesis contact preference-add lia likes 'oat milk'` | preference, learnable if starred | Low |
| 5 | nothing | `noesis contact gift-add lia 'field notebook' --status idea` | gift ledger entry | Low |
| 6 | nothing | `noesis contact companion-add lia 'Pulga'` | companion animal | Low |
| 7 | that circles are sets, not groups with permissions | `noesis contact circle-add 'climbing' lia --with sarah` | a contact circle | Low |

*Verdict:* feature-complete against §7.1 and the least discoverable part of the product. Seven
capabilities reachable only by reading `noesis contact --help` in full. Step 3's ambiguity is the
one substantive design question here: two correct ways to say "Sarah is married to Marco", with
different annotation and learning consequences and no stated rule for choosing.

### J11 — Writing things down

*Intent:* have somewhere to put a thought that is not yet a fact.
*Roles:* Capturer. *Outcomes:* 1, 3. *Status:* **implemented** in 0.2 — `SPEC.md` §8.5.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that there is a page for today | `noesis note today` | the dated page, created on first use | Low |
| 2 | that a thought worth keeping apart from the day gets its own note | `noesis note new 'Local-first software'` | a permanent note, or a literature note with `--literature` | Low |
| 3 | that `[[…]]` means an entity | `noesis note append 'met [[Lía García]] about local-first'` | a block per paragraph; the links resolve against current names | Low |
| 4 | that an unresolved link asks rather than guesses | — | clarification prompt, never a silent new entity | Low — §3.5.3 applied |
| 5 | that editing is a round-trip, not a live file | `noesis note edit today` | `$EDITOR` opens; the saved buffer diffs back into block operations | Medium — F16 |
| 6 | that every block keeps its history | `noesis note history <blockId>` | every revision, because block text is a fluent | Low |
| 7 | nothing | `noesis as-of 2026-03-01` | the note as it stood, from machinery that already exists | Low |

*Verdict (design):* the whole journey rests on blocks being fluents, so history, time travel and
`state.changed` come from §3.6 rather than from anything new. Step 4 is the only real cost, and it
is the price of D1 — the journal is the truth, so a file cannot be.

### J12 — Turning writing into knowledge

*Intent:* get the facts out of what was written, without retyping them as assertions.
*Roles:* Capturer, Curator. *Outcomes:* 1, 2, 3. *Status:* **not built** — planned for 0.3.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that extraction is asked for, never automatic | `noesis note extract today` | proposals, each with its axiom, Manchester rendering, confidence and source block | Low |
| 2 | that a paragraph can propose a dozen axioms | — | a batch queue: accept all, accept one, edit, reject | **High** — F5, the governing risk |
| 3 | that nothing was committed before this | — | confirmed axioms land; the rest is discarded | Low — closes F2 |
| 4 | nothing | `noesis show lia` | the fact, alongside facts entered by hand — no second class | Low |
| 5 | nothing | `noesis queue` | an item drafted from it, by the ordinary cascade | Low |

*Verdict (design):* this is §3.5's capture pipeline finally built, and it is what closes F2 —
confirmation cannot stay a post-hoc report once a model is proposing the content. The risk is
entirely in step 2: extraction makes §12.1's confirmation fatigue the central UX problem rather
than a future one.

### J13 — Finding my way back

*Intent:* find what I wrote, and everything I have ever written about someone.
*Roles:* Curator, Learner. *Outcomes:* 3. *Status:* **partial** in 0.2 — backlinks and search ship; tags do not.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | nothing | `noesis backlinks lia` | every block mentioning her — a closure query, not an index | Low |
| 2 | nothing | `noesis search 'local-first'` | blocks, notes and quotes matching, by label and text | Low |
| 3 | nothing | `noesis note list` | every note, with its title and how many blocks it holds | Low |
| 4 | nothing | `noesis note show <id>` | the note as an outline, with block ids | Low |
| 5 | graph-pattern syntax | `noesis query "?b note:mentions noesis:e/lia"` | the same answer as step 1, generalized | Medium — F1 |

*Verdict (design):* backlinks fall out of the reasoner because `[[links]]` are axioms. That is the
argument for links being knowledge rather than markup — Obsidian maintains an index for this and
Noesis does not need one.

### J14 — Reading something long

*Intent:* read a chapter and keep what matters, without the system keeping the chapter.
*Roles:* Reader, Learner. *Outcomes:* 2, 3. *Status:* **not built** — planned for 0.3.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that a source is registered before it is read | `noesis reference add --title 'Local-First Software' --url …` | a reference record | Low |
| 2 | that the text is not kept | `noesis read chapter.txt --reference <id>` | digest recorded; the session runs in memory | Low |
| 3 | that suggestions are suggestions | — | proposed quotes, facts and comprehension questions, each with a locator | **High** — F5 again, and longer |
| 4 | nothing | — | confirmed quotes and facts land; the text is discarded | Low |
| 5 | nothing | `noesis reference show <id>` | exactly what was kept from it | Low |
| 6 | that comprehension is graded, not scored | `noesis quiz --reference <id>` | open questions graded against a rubric, cloze only over confirmed quotes | Medium — F15 |
| 7 | nothing | `noesis backlinks <id>` | every note and fact that came from this source | Low |

*Verdict (design):* the no-store rule is what makes this journey defensible rather than a private
library of other people's writing, and it costs exactly two things: F14 (re-reading means
re-supplying) and F15 (comprehension questions cannot go stale). Both are recorded as accepted
rather than discovered later.

### J15 — Making the journal smaller without making it a lie

*Intent:* drop history that answers no question, while keeping the history that does.
*Roles:* Curator, Exiter. *Outcomes:* 3, 4. *Status:* **not built** — specified in `SPEC.md` §3.2.1, planned for 0.3.

| # | Must already know | Command | Result | Friction |
|---|---|---|---|---|
| 1 | that history has a size, and where it went | `noesis journal size` | the journal's size broken down by what is generating it | Low |
| 2 | that keeping history is per property, not global | `noesis prune --show-policy` | which properties keep history and which do not, with the default for each | Low |
| 3 | that a prune is rehearsed first | `noesis prune --dry-run` | what would go, what is held back, and by which pointer | Low — the F5 pattern applied before it is needed |
| 4 | that pruning writes a new generation | `noesis prune` | generation *n+1*, opening with a prune record; generation *n* untouched | Medium — F18 |
| 5 | that the old generation is discarded deliberately | `noesis journal discard <generation>` | the bytes go, as a separate act | Low — the only step that actually loses anything |
| 6 | that `as-of` is now approximate for pruned properties | `noesis as-of 2026-03-01` | the right words in today's arrangement, and a note saying so | Medium — F19 |

*Verdict (design):* every step exists to keep two things apart that a size-driven feature naturally
merges — making the log smaller, and making a fact never have existed. Steps 4 and 5 are separate
commands for that reason alone, and step 6 tells the truth about what step 4 cost rather than
rendering a past that never held.

---

## 5. Story catalogue

Stories carry stable ids so that commits, tests and issues can cite them. Acceptance criteria are
written as commands and observable output, which makes them the launcher scenario
[TESTING.md](TESTING.md) already requires rather than a second artifact.

Status is one of **implemented**, **partial**, **not built**.

### US-01 — Create a workspace that is private by construction

As the owner, when I start, I want the workspace to be private without my configuring anything, so
that privacy is not a thing I can forget.

*Role:* Exiter · *Journey:* J1.1 · *Spec:* §10 · *Status:* implemented

```
Given  no workspace exists
When   noesis init
Then   the directory is 0700 and its files 0600, whatever the umask
And    a symlinked persistence path is refused
```

### US-02 — Assert a fact in one line

As the Capturer, when I hear something, I want to record it in a single command, so that capture
costs less than remembering to capture later.

*Role:* Capturer · *Journey:* J1.3, J2.1 · *Spec:* §3.5 · *Status:* implemented

```
Given  an initialized workspace
When   noesis assert lia crm:birthday 05-12
Then   the axiom is committed atomically
And    it is stored as xsd:gMonthDay, with no year invented
```

### US-03 — Confirm the formal representation before it is durable

As the Capturer, when I assert something, I want to approve the formal representation first, so that
the knowledge base contains what I meant and I learn the vocabulary by seeing it.

*Role:* Capturer · *Journey:* J1.3, J2.2 · *Spec:* §1.3, §3.5.5 · *Status:* **not built** — see F2

```
Given  an initialized workspace
When   noesis assert lia crm:birthday 05-12
Then   the verbalization, the proposed annotations and the Manchester rendering are shown
And    nothing is appended to the journal until the owner accepts
And    --yes skips the prompt for scripted use, and is the only way to skip it
```

### US-04 — Discover the vocabulary from inside the tool

As the Capturer, when I do not know the term for what I want to say, I want to find it without
leaving the terminal, so that I do not have to read the specification to record a birthday.

*Role:* Capturer · *Journey:* J1.3, J2.1, J4.5 · *Spec:* §5.1 · *Status:* **not built** — see F1

```
Given  the module ontologies are installed
When   noesis vocab search spouse
Then   crm:spouseOf is listed with its domain, range and one example invocation
When   noesis vocab show crm:birthday
Then   its datatype, sensitivity default and utility default are shown
```

### US-05 — Be warned before minting a new entity

As the Capturer, when I mistype a handle, I want to be told a new entity is about to exist, so that
`sara` and `sarah` do not become two people I never reconcile.

*Role:* Capturer · *Journey:* J2.3 · *Spec:* §3.5.3 · *Status:* **not built** — see F4

```
Given  an entity noesis:e/sarah exists and noesis:e/sara does not
When   noesis assert sara crm:spouseOf marco
Then   the unknown handle is reported as NEW with its nearest existing matches
And    it is created only on confirmation, or with --new
```

### US-06 — See a fact I did not state

As the owner, when I assert a fact, I want to be able to ask what now follows, so that the formal
representation is visibly worth its cost.

*Role:* Curator · *Journey:* J2.4, J2.5 · *Spec:* §3.4 · *Status:* implemented

```
Given  sarah crm:spouseOf marco is asserted
When   noesis entails sarah crm:knows marco
Then   the answer is yes
When   noesis explain sarah crm:knows marco
Then   the minimal justification and its journal-backed premises are shown
And    a result reaching a resource cap is reported as incomplete, never as a negative
```

### US-07 — Be asked a question, not shown the answer

As the Learner, when I review, I want to be asked something and told whether I was right, so that the
review log records recall rather than self-assessment.

*Role:* Learner · *Journey:* J3.2, J3.3 · *Spec:* §4.1, §4.3 · *Status:* **implemented** in 0.2 — closes F7

```
Given  a queued item generated from lia crm:birthday 05-12
When   noesis quiz
Then   the question prompt is shown and the answer is not
When   the owner answers
Then   the answer is graded against the item's AnswerSpec and the outcome shown
And    a question stale against its source fact is regenerated, not asked
And    a rubric-graded answer with no grader available declines to grade rather than guessing
```

### US-08 — Understand why an item was chosen

As the Learner, when the queue shows me something, I want to know why it is there, so that I can
trust the scheduler instead of second-guessing it.

*Role:* Learner · *Journey:* J3.1 · *Spec:* §4.3 · *Status:* implemented

```
Given  items with differing belief and utility
When   noesis queue
Then   each entry shows its mode, weight, belief, utility and a stated reason
And    a slice of the queue is reserved for low-utility items (§12.10)
```

### US-09 — Correct a mistake without a scavenger hunt

As the Curator, when I notice a wrong fact, I want to undo it from where I saw it, so that
correcting is cheaper than tolerating.

*Role:* Curator · *Journey:* J6.1–J6.3 · *Spec:* §3.2 · *Status:* **partial** — `retract` exists; see F3

```
Given  the most recent commit asserted a wrong fact
When   noesis undo
Then   that commit's operations are shown and retracted on confirmation
And    the retraction is a new journal entry, never an edit to an existing one
When   noesis show lia
Then   each displayed fact carries the axiom id needed to retract it
```

### US-10 — Record a change as a change

As the Curator, when a state ends or is replaced, I want one command that closes the old and opens
the new, so that history stays coherent.

*Role:* Curator · *Journey:* J5.1, J5.3 · *Spec:* §3.6 · *Status:* implemented

```
Given  sarah has an open worksAt state
When   noesis supersede sarah crm:worksAt molina --on 2026-06-01
Then   the previous state is closed at that boundary and one new state is open
And    a state.changed event carries both the old and the new value
When   noesis close sarah crm:worksAt --on 2026-06-01
Then   the state is closed with no successor and is not reported as ongoing
```

### US-11 — Retire a contact method without losing history

As the Curator, when someone's number changes, I want to retire the old one, so that it leaves their
card while the interactions that used it stay intact.

*Role:* Curator · *Journey:* J5.2 · *Spec:* §7.1, §3.6 · *Status:* implemented

```
Given  Lía has a mobile method M and an email method
When   noesis contact method-retire M
Then   noesis contact show lia lists only the email as current
And    noesis as-of <a date before the retirement> still shows M
And    the journal holds a close-fluent operation, not a retraction
```

### US-12 — Answer a question about the past

As the Curator, when I need to know what was true then, I want to ask directly, so that the journal's
value is reachable rather than theoretical.

*Role:* Curator · *Journey:* J5.5 · *Spec:* §3.2, §3.6 · *Status:* implemented

```
Given  a state that closed on 2026-06-01
When   noesis as-of 2026-03-15
Then   the graph as it stood on that date is shown, including the then-open state
```

### US-13 — See what an agent would receive, before granting it

As the Auditor, when I consider granting access, I want to simulate the disclosure, so that I decide
on evidence rather than on the policy's description of itself.

*Role:* Auditor · *Journey:* J7.1, J7.2 · *Spec:* §3.3.1, §9 · *Status:* implemented

```
Given  facts at every sensitivity level
When   noesis disclose tutor --level public
Then   each axiom is shown as disclosed or redacted, with the reason for each redaction
And    no sensitive axiom is disclosed under any grant
And    a derived fact is disclosed only when one justification is fully disclosable
```

### US-14 — Find out what is wrong before it matters

As the Curator, when something is inconsistent or mis-annotated, I want to be told, so that errors do
not accumulate silently.

*Role:* Curator · *Journey:* J1.5, J6.4 · *Spec:* §3.4 · *Status:* **partial** — `check` exists but is manual; see F11

```
Given  a knowledge base with an annotation policy violation
When   noesis check
Then   the violation is reported with the axiom and the policy that rejected it
And    an inconsistency is reported with its justification
And    EL profile departures are reported as warnings, never as failures
```

### US-15 — One agenda, not one per module

As the Learner, when I ask what is due, I want everything dated from every module, so that I do not
have to remember which module owns which obligation.

*Role:* Learner · *Journey:* J4.1 · *Spec:* §5.2 · *Status:* **partial** — implemented, misfiled; see F6

```
Given  a due follow-up and a due obligation from another module
When   noesis agenda
Then   both are listed in one ordered queue, with overdue entries marked
And    noesis contact due remains as an alias
```

### US-16 — Rehearse an import before committing it

As the Curator, when I import an address book, I want to see what it would do first, so that I do not
import someone else's data model into mine.

*Role:* Curator · *Journey:* J9.1, J9.2 · *Spec:* §7.3 · *Status:* **partial** — `--dry-run` exists, per-record confirmation does not; see F5

```
Given  a vCard file containing four contacts, one of which already exists
When   noesis contact import contacts.vcf --format vcard --dry-run
Then   the parsed records and validation results are shown and nothing is committed
And    the existing contact is reported as a duplicate candidate, never merged
```

### US-17 — Confirm a merge, never infer one

As the Curator, when two records look like the same person, I want to decide, so that identity is
never inferred from an email address.

*Role:* Curator · *Journey:* J9.5 · *Spec:* §7.2, §12.11 · *Status:* **not built** — detection exists, no surface; see F8

```
Given  two contacts sharing a mailbox
When   noesis contact duplicates
Then   both are listed as candidates with the evidence for the match
When   noesis contact merge <a> <b>
Then   the merge is applied only on confirmation, as journal operations that preserve both provenances
```

### US-18 — Take the data and go

As the Exiter, when I want out, I want a verified archive and a restore that provably works, so that
leaving is a decision rather than a risk.

*Role:* Exiter · *Journey:* J8.1–J8.3 · *Spec:* §10 · *Status:* implemented

```
Given  a workspace with a journal and a review log
When   noesis archive create /tmp/a
Then   both logs are captured under one lock, with SHA-256 checksums and a current.ttl projection
When   a payload is tampered with and noesis archive verify /tmp/a runs
Then   verification fails and names the payload
When   noesis archive restore /tmp/a /tmp/new
Then   a fresh workspace is produced, and restoring onto an existing path is refused
```

### US-19 — Export without leaking

As the Exiter, when I export a contact, I want contact data withheld unless I ask for it, so that
sharing a card is not the same as publishing a mailbox.

*Role:* Exiter, Auditor · *Journey:* J8.4, J8.5 · *Spec:* §7.3, §10 · *Status:* implemented

```
Given  Lía has an email method and a postal address
When   noesis contact export lia --format vcard
Then   the vCard omits contact methods and addresses
When   noesis contact export lia --format vcard --include-contact-data
Then   they are included, subject to the disclosure policy
When   noesis export
Then   the current graph is written as Turtle with correct prefix bindings
```

### US-20 — Read the output with a program

As the Auditor, and as any future non-terminal surface, I want machine-readable output, so that one
projection serves both a person and a program.

*Role:* Auditor · *Journey:* J7, J4 · *Spec:* §3.8 · *Status:* **not built** — see F9

```
Given  any read command
When   --json is passed
Then   the same projection is emitted as JSON with stable field names
And    the human rendering remains the default
And    diagnostics go to stderr so stdout stays parseable
```

### US-21 — Keep a name correct

As the Learner, when someone's name or pronouns change, I want the system to prioritize my learning
it and to never show me the old one, so that the record does not make me get it wrong.

*Role:* Learner · *Journey:* J5, J3 · *Spec:* §7.2 · *Status:* implemented

```
Given  a name supersession
When   noesis show <person> or any other rendered output is produced
Then   only the current name appears; former names are sensitive and never rendered
And    a change item is drafted at elevated priority
```

### US-22 — Learn a language against the same machinery

As the Learner, when I study vocabulary, I want it to be ordinary knowledge, so that there is one
scheduler, one belief model and one journal.

*Role:* Learner · *Journey:* J3 · *Spec:* §6 · *Status:* **partial** — `ll:` ships; no capture ergonomics

```
Given  the ll: module is installed
When   a lexeme-concept-lexeme path exists between two languages
Then   translation is derived rather than stored
And    mastery is tracked per direction
```

### US-23 — Keep in touch on a cadence I chose

As the Capturer, when a relationship matters more than my memory of it, I want a cadence and to be
told when I have drifted past it, so that keeping in touch does not depend on noticing.

*Role:* Capturer, Learner · *Journey:* J10.1, J10.2 · *Spec:* §7.4 · *Status:* implemented

```
Given  a 30-day cadence for sarah and a last qualifying interaction 40 days ago
When   noesis contact due
Then   sarah is listed as an overdue follow-up
And    a birthday due in the lead time is projected from the fact itself,
       never duplicated into a stored reminder record
```

### US-24 — Put a thought somewhere before it is a fact

As the Capturer, when I have a thought that is not yet an assertion, I want somewhere to write it,
so that capture is never blocked on knowing how to formalize it.

*Role:* Capturer · *Journey:* J11.1, J11.3 · *Spec:* §8.5.1 · *Status:* **implemented**

```
Given  no page exists for today
When   noesis note today
Then   a note:Daily page is created and shown
When   noesis note append 'met [[Lía García]] about local-first'
Then   a block is appended with a stable id
And    it is a fluent, so the text can be superseded without losing the id
```

### US-25 — Mention a thing, not a string

As the Capturer, when I write `[[Lía García]]`, I want it to mean the entity, so that what I write
joins the graph instead of sitting beside it.

*Role:* Capturer · *Journey:* J11.3, J11.4 · *Spec:* §8.5.2 · *Status:* **implemented**

```
Given  an entity whose current name is Lía García
When   a block containing [[Lía García]] is committed
Then   a note:mentions axiom is asserted from the block to that entity
When   the link matches no entity
Then   it is raised as a clarification prompt, never a silent new entity
And    a former name never resolves, because §7.2 forbids rendering it
```

### US-26 — Edit in my own editor

As the Capturer, when a block needs more than one line of thought, I want to edit it in `$EDITOR`,
so that writing is not constrained by the shape of a command.

*Role:* Capturer, Curator · *Journey:* J11.5 · *Spec:* §8.5.3 · *Status:* **implemented**

```
Given  a note with three blocks
When   noesis note edit today
Then   the note is materialized as Markdown and $EDITOR opens
When   a line is reworded, one is re-indented and one is added
Then   the reworded and re-indented blocks keep their ids
And    only the genuinely new line mints a new block
And    facts and quotes pointing at those blocks still point at them
```

### US-27 — See what a block used to say

As the Curator, when a note has changed, I want its history, so that "what did I think then" is
answerable.

*Role:* Curator · *Journey:* J11.6, J11.7 · *Spec:* §8.5.1, §3.6 · *Status:* **implemented**

```
Given  a block edited three times
When   noesis note history <blockId>
Then   every revision is listed with the date it was superseded
When   noesis as-of <a date before the last edit>
Then   the note renders as it stood, with no new machinery
```

### US-28 — Propose facts, never write them

As the Curator, when I ask for extraction, I want proposals I can accept, edit or reject, so that a
model never puts anything in the journal on my behalf.

*Role:* Curator · *Journey:* J12.1, J12.2, J12.3 · *Spec:* §8.5.5, §3.5 · *Status:* **not built**

```
Given  a block reading 'met [[Lía García]], her birthday is 12 May'
When   noesis note extract today
Then   a proposal shows the axiom, its Manchester rendering, confidence and source block
And    nothing has been appended to the journal
When   the proposal is accepted
Then   the axiom is committed with note:extractedFrom pointing at that block
And    the model name and digest that produced it are recorded
When   the proposal is rejected
Then   nothing is committed, and the rejection is not re-proposed unchanged
```

### US-29 — Confirm a batch without confirming a hundred times

As the Curator, when one paragraph proposes a dozen axioms, I want to dispose of them as a batch,
so that extraction does not cost more attention than typing them would have.

*Role:* Curator · *Journey:* J12.2 · *Spec:* §12.1 · *Status:* **not built**

```
Given  twelve proposals from one note
When   the batch queue is shown
Then   accept-all, per-item accept, edit and reject are all available
And    the count and the sensitivity of what would be committed are shown before accepting
When   the batch is cancelled
Then   nothing is committed, because the commit is atomic
```

### US-30 — A model never sees what an agent could not

As the Auditor, when text is sent to the local model, I want the same disclosure boundary that
governs every other external consumer, so that "it runs locally" is not the whole argument.

*Role:* Auditor · *Journey:* J12.1 · *Spec:* §8.5.5, §3.3.1 · *Status:* **not built**

```
Given  facts at every sensitivity level about a mentioned entity
When   a prompt is assembled
Then   its knowledge-base context is a DisclosureView, and sensitive facts are absent
When   --include-sensitive is passed
Then   the widened policy is used for that invocation and recorded
And    no remote provider is configurable in this release
```

### US-31 — Everything I wrote about someone

As the Curator, when I want what I know about a person, I want what I *wrote* about them too, so
that notes and facts are one body of knowledge rather than two.

*Role:* Curator, Learner · *Journey:* J13.1, J13.2 · *Spec:* §8.5.2 · *Status:* **implemented**

```
Given  three blocks mentioning Lía across two notes
When   noesis backlinks lia
Then   all three are listed with their notes
And    the answer comes from the closure, so no index can fall out of date
When   noesis search 'local-first'
Then   matching blocks, note titles and quotes are returned
```

### US-32 — Register a source before reading it

As the Reader, when I am about to read something, I want the source recorded first, so that
everything I keep from it has somewhere to attach.

*Role:* Reader · *Journey:* J14.1 · *Spec:* §3.7, §8.5.4 · *Status:* **not built**

```
Given  no reference exists for the paper
When   noesis reference add --title 'Local-First Software' --url https://…
Then   a ref:Reference is created with its metadata
And    it is an ordinary entity, so it can be mentioned by [[links]] like any other
```

### US-33 — Read a chapter without the system keeping it

As the Reader, when I hand Noesis a chapter, I want it used and discarded, so that my knowledge
base does not become a copy of someone else's book.

*Role:* Reader · *Journey:* J14.2, J14.3, J14.4 · *Spec:* §8.5.4 · *Status:* **not built**

```
Given  a chapter as a file
When   noesis read chapter.txt --reference <id>
Then   ref:sourceDigest records a SHA-256 over the normalized text
And    proposed quotes, facts and questions are shown with locators
When   the session ends
Then   the journal, the Markdown mirror and the workspace contain no unconfirmed
       substring of the source
When   the same file is supplied again
Then   the digest matches and is reported; a different text is reported, not accepted
```

### US-34 — Keep the quote, keep it verbatim

As the Reader, when a passage matters, I want it kept exactly as written and marked as not mine, so
that what the source said never blurs into what I think.

*Role:* Reader · *Journey:* J14.3, J14.4 · *Spec:* §8.5.4 · *Status:* **not built**

```
Given  a proposed quote with its locator
When   it is confirmed
Then   a ref:Quote is asserted verbatim, with no fluent and no way to supersede it
When   a literature note comments on it
Then   the block cites the quote rather than containing it
And    the block is editable while the quote is not
```

### US-35 — Be tested on what I read

As the Reader, when I have finished something, I want to be asked whether I understood it, so that
reading leaves more than a list of highlights.

*Role:* Reader, Learner · *Journey:* J14.6 · *Spec:* §8.5.6, §4.1 · *Status:* **not built**

```
Given  comprehension questions generated during the reading session
When   noesis quiz --reference <id>
Then   open questions are asked and graded against a rubric by the local model
And    the grade records the model and digest, and the owner can override it
And    a cloze question exists only where it was built from a confirmed quote
When   no model is configured
Then   the grader declines rather than guessing
```

### US-36 — See exactly what a source left behind

As the Reader, when I look back at a source, I want what I kept from it, so that the value of
having read it is visible.

*Role:* Reader, Curator · *Journey:* J14.5, J14.7 · *Spec:* §8.5.4 · *Status:* **not built**

```
Given  a reference with confirmed quotes and extracted facts
When   noesis reference show <id>
Then   its metadata, digest, quotes and the facts linked to it are listed
When   noesis backlinks <id>
Then   every note block and axiom deriving from it is listed
```

### US-37 — Know what is generating the journal before deciding to prune

As the Curator, when the journal has grown, I want to see what is generating it, so that pruning is
a decision about a known cost rather than a reflex about a number.

*Role:* Curator · *Journey:* J15.1, J15.2 · *Spec:* §3.2.1 · *Status:* **not built**

```
Given  a workspace whose notes have been edited many times
When   noesis journal size
Then   the total is broken down by property, with superseded states counted separately
When   noesis prune --show-policy
Then   every time-varying property is listed as keeping history or not, with the default shown
And    note:text keeps history and note:order does not, per §3.2.1
```

### US-38 — Prune without ever editing a journal

As the Curator, when I prune, I want a new generation rather than a rewritten log, so that the
history I keep stays as verifiable as it was before.

*Role:* Curator · *Journey:* J15.3, J15.4 · *Spec:* §3.2.1, `modules/journal/SPEC.md` §1 · *Status:* **not built**

```
Given  a workspace with superseded order states and a learning item citing one of them
When   noesis prune --dry-run
Then   the prunable states are listed, and the cited one is listed as held back with its pointer
And    nothing is written
When   noesis prune
Then   a new generation is written whose first entry is a prune record
And    the previous generation is unchanged and still replays
And    every surviving axiom and fluent keeps the identifier it had
```

### US-39 — Be told when the past is approximate

As the Curator, when I ask for a note as it stood and the answer is no longer exact, I want to be
told, so that a pruned journal never quietly renders a past that never held.

*Role:* Curator · *Journey:* J15.6 · *Spec:* §3.2.1 · *Status:* **not built**

```
Given  a generation in which note:order history was pruned
When   noesis as-of 2026-03-01
Then   the blocks show the wording they held on that date
And    the output states that their arrangement is current, not historical
```

### US-40 — Discarding bytes is its own decision

As the Exiter, when I want superseded history gone rather than merely unused, I want that to be a
separate act, so that shrinking the journal can never be mistaken for erasing a fact.

*Role:* Exiter · *Journey:* J15.5 · *Spec:* §3.2.1, §3.4 · *Status:* **not built**

```
Given  a workspace with a pruned generation and the generation it came from
When   noesis journal discard <the source generation>
Then   its bytes are removed and the prune record's digest of it remains
When   the same is attempted for the current generation
Then   it is refused, because that is retraction's job and not this command's
```

---

## 6. Friction ledger

Every friction identified above, with its root cause and status. **Open** means it should be fixed.
**Accepted** means the cost is real and is outweighed by a principle, which is named.

| id | Friction | Journeys | Root cause | Status |
|---|---|---|---|---|
| **F1** | The vocabulary is undiscoverable from inside the tool | J1.3, J2.1, J4.5 | §1.2 assumed an LLM would translate natural language, so no term-browsing surface was ever specified for the structured path | **Open** — US-04 |
| **F2** | Commits are reported, not confirmed | J1.3, J2.2 | `Main.reportCommit` prints the axiom after `commit` returns; there is no interactive prompt anywhere in the CLI | **Open** — US-03. Contradicts §1.3 and §3.5.5; recorded as a specification/implementation disagreement, not silently reconciled |
| **F3** | Correcting a fact requires a journal dump and a copied id | J6.1, J6.2 | `retract` takes an axiom id; no view that displays a fact also displays its id | **Open** — US-09 |
| **F4** | Unknown handles mint entities silently | J2.3 | `Workspace.iri` maps any bare token to `noesis:e/<token>` with no existence check; §3.5.3's NEW flag is unimplemented | **Open** — US-05 |
| **F5** | Import commits without per-record confirmation | J9.2 | §12.1's batch-confirmation queue is unbuilt; `--dry-run` is the partial substitute | **Open** — US-16 |
| **F6** | The cross-module agenda is filed under `contact` | J4.1 | `ContactCommand.Due` calls `Modules.agendaProducers(Modules.all)` — it is already §5.2's shared agenda under a PRM name | **Open** — US-15 |
| **F7** | The review loop shows the fact and asks for a self-grade | J3.2, J3.3 | `Question`, `AnswerSpec`, distractors and staleness all exist in `lms` with no CLI surface; `queue` renders `item.prompt` | **Closed** in 0.2 — US-07. `noesis quiz` asks the stored question, grades against its `AnswerSpec`, and regenerates one whose source fact has changed rather than asking it |
| **F8** | Duplicate candidates are detected and unreachable | J9.5 | Detection lives in `vocab`; no command exposes it | **Open** — US-17 |
| **F9** | No machine-readable output | J4, J7 | `Render` targets a terminal exclusively | **Open** — US-20 |
| **F10** | Two grammars for one relationship (`assert` vs `relationship-add`) | J10.3 | Reified records and direct assertions both model §7.1 relationships; no stated rule for choosing | **Open** — needs a design decision before a story |
| **F11** | `check` is manual | J1.5, J6.4 | Consistency is enforced at commit; policy and profile findings are only produced on demand | **Accepted** — commit-time consistency already fails closed (`DESIGN.md` invariant 2). Surfacing advisory findings automatically would add output to every command; the Curator asks when curating |
| **F12** | Capture cannot happen away from the terminal | all | No mobile or web surface (§2) | **Accepted** — local-first, single-device MVP. §10's sync is unbuilt by decision, not oversight |
| **F13** | No latency evidence for §10's budgets | J2, J3 | Nothing measures capture round-trip or review submit | **Accepted for now** — the budgets bind the LLM-backed capture path that does not exist yet. Revisit when it does |
| **F14** | Re-extracting from a source means supplying the text again | J14.2 | §8.5.4 keeps no copy of the text | **Accepted** — the direct cost of the retention rule. The alternative is holding the copyrighted text the rule exists to avoid holding, and the digest at least makes "is this the same text?" answerable |
| **F15** | Comprehension questions cannot go stale | J14.6 | §4.1 detects staleness from source axioms; an open comprehension question has only a reference | **Accepted** — regenerating requires the text, which is not kept. Recorded as a deliberate departure rather than left to be discovered when a question outlives its accuracy |
| **F16** | Editing a note is a round-trip, not a live file | J11.5 | D1: blocks are journaled state, so a file cannot be the truth | **Accepted** — what buys per-block history, time travel and stable link targets. A read-only Markdown mirror keeps `grep` working |
| **F17** | Extraction volume is unbounded | J12.2, J14.3 | A model proposes as much as the text supports; §12.1's batch queue is the only control | **Open** — US-29. This is the one that decides whether the module is usable, and it cannot be judged until real text meets a real model |
| **F18** | Pruning writes a whole new generation, so it needs room for two | J15.4 | §3.2.1 forbids editing a journal in place, and a generation is only immutable if it is never touched | **Accepted** — the alternative is compaction that rewrites the log, which makes the log's own history unverifiable. Disk is the cheapest thing this system spends |
| **F19** | After a prune, `as-of` is approximate for the pruned properties | J15.6 | The states that answered the question are the ones removed | **Accepted** — the direct and only cost of pruning, which is why §3.2.1 makes the choice per property and why US-39 requires the output to say so rather than render silently |

---

## 7. Prioritization

Rank by **(owner frequency × friction removed)**, then apply the gates in order:

1. **Principle gate.** A change that weakens a `DESIGN.md` implementation invariant is rejected,
   however much friction it removes. Fail-closed sensitivity, journal-as-truth, monotone rules and
   provenance-carrying derivation are not negotiable against convenience.
2. **Reachability gate.** Prefer exposing a capability that is built, tested and unreachable over
   building a new one. F6, F7 and F8 are all surfaces missing from finished subsystems, which makes
   them the cheapest real improvements available.
3. **Unrecoverability gate.** A friction in J1 or J8 outranks an equal friction elsewhere, because the
   Capturer's and the Exiter's failures cannot be repaired later.
4. **Evidence gate.** A story ships only with the evidence [TESTING.md](TESTING.md) requires for its
   change type, including the transcript of the journey step it claims to fix.

Applying this to the current ledger gives the ordering: **F7** (a finished subsystem the owner cannot
reach, on the daily journey), then **F1** and **F2** (both on J1 and J2, both unrecoverable-role,
and F2 is a spec disagreement), then **F3** and **F6**, then **F9**, then the rest.

## 8. Proposed commands (not implemented)

Commands named by stories above that do not exist yet. This block is machine-read by
`ProductTraceSuite`, which derives the real surface from `Main`'s typed AST: an invocation in this
document is either a command the CLI ships or one of these, and never both. A proposal that ships
without leaving this block fails the suite.

```
noesis vocab search
noesis vocab show
noesis undo
noesis agenda
noesis contact duplicates
noesis contact merge
noesis note extract
noesis reference add
noesis reference show
noesis reference list
noesis reference quote-add
noesis read
noesis prune
noesis journal size
noesis journal discard
```

## 9. Product decisions

Dated, numbered, and appended — never rewritten. A decision that reverses an earlier one cites it.

- **PD-01 (2026-07-30) — Product intent gets a document.** The repository had authorities for intent,
  design, verification and threat, and none for who the system serves. Journeys and stories now live
  here, and the traceability check makes a shipped command with no journey a build failure.
- **PD-02 (2026-07-30) — Roles, not personas.** §1.1's single principal makes personas dishonest.
  Design targets the five situations of one person instead.
- **PD-03 (2026-07-30) — F11, F12 and F13 accepted.** Recorded with the principle that justifies each,
  so that re-proposing them is cheap to answer.
- **PD-04 (2026-07-30) — Reachability before novelty.** Where a subsystem is built and tested but has
  no owner-facing surface, exposing it outranks new capability. This is why F7 leads the ordering.
- **PD-05 (2026-07-31) — Notes, journaling and reading are one module, specified before built.**
  `SPEC.md` §8.5 settles the contract ahead of the code because two of its rules are expensive to
  retrofit: source text is never retained, and a model never sees more than an external agent would.
  Eight decisions were taken, and each closed off an alternative worth recording:
  - *Blocks are fluents.* Block text, parent and order are time-varying states, so per-block history
    and `as-of` come from §3.6 and the journal gains no operation.
  - *Journal-native blocks with an editor round-trip*, not files-as-truth. Buys history and stable
    link targets; costs live editing (F16).
  - *Block-level addressing*, so provenance points at a sentence. This is why own-notes need no
    text-quote selectors — and why read sources, whose text is not kept, still do.
  - *Quotes are records; blocks cite them.* Your words are fluents because you revise them;
    someone else's are axioms because revising them would make the claim false.
  - *Source text is transient*, with a digest recorded. Not primarily a storage decision: it is what
    makes handing a purchased chapter to a personal knowledge base defensible.
  - *The local model is gated like any external consumer.* It is a separate process that could log
    what it is shown, so prompt context is a `DisclosureView` and `sensitive` is absent by default.
  - *Comprehension questions are open or cloze-over-confirmed-quotes only*, so no unconfirmed
    passage is persisted inside a question.
  - *Loopback now, endpoint abstracted.* Ollama does not yet serve a Unix socket
    ([ollama/ollama#8072](https://github.com/ollama/ollama/pull/8072)); the socket path is
    implemented so the switch is configuration rather than a rewrite.
- **PD-06 (2026-07-31) — Ordering after PD-05.** Notes without a model ship first, then `noesis
  quiz`, then the gateway, then extraction, then reading sessions. The reachability gate (§7) still
  applies: F7 blocks the reading journey, because "quiz me on what I read" is unbuildable while the
  review loop cannot ask a question.
- **PD-07 (2026-07-31) — Superseded history is prunable, by generation and by property.**
  `SPEC.md` §3.2.1 settles how an append-only log gets smaller without becoming unverifiable: a
  prune reads one generation and writes the next, never edits one. Three consequences were chosen
  rather than fallen into. *Per property, not per size* — the owner says `note:text` keeps every
  draft and `note:order` keeps none, because a threshold cannot tell the difference between history
  that answers a question and history that does not. *Pointers win over policy* — anything citing a
  superseded state keeps it, since identifiers are content-derived and a dangling one is
  unrepairable. *Pruning and forgetting stay separate commands* — retraction makes a fact stop
  counting, discarding a generation makes bytes go away, and merging them would let a size feature
  quietly become an erasure feature. The admitted cost is F19: `as-of` goes approximate for pruned
  properties, and the output has to say so.
- **PD-08 (2026-07-31) — Sibling order carries an integer part.** Measured, not assumed: the plain
  fractional midpoint grows keys by about one character per five blocks, so a 200-block note reaches
  40-character order keys and a 1000-block one reaches 200. Appending is the dominant operation on a
  dated page, so it is the one that must not degrade. A length-prefixed integer part makes appending
  and prepending constant-size and leaves growth only where it is unavoidable — repeated insertion
  into one gap. PD-07's pruning does not substitute for this: pruning cleans up superseded order
  states, while the length of the *current* key is what this decision governs. Rebalancing was
  rejected for a reason pruning does not fix either — it emits a `state.changed` burst for every
  block in the note, which is a false signal to §4.1 whether or not the states are later pruned.
