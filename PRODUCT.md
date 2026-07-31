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
stakes and tolerance differ enough between these five that a design good for one is often wrong for
another.

| Role | When | Attention available | Characteristic failure |
|---|---|---|---|
| **Capturer** | Mid-conversation, or just after | Seconds. Will not read documentation | The fact is lost, or entered wrongly and never noticed |
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
| 2 | that the prompt is the fact, not a question | — | the fact itself is displayed | **High** — F7 |
| 3 | how to score oneself on a 0–1 scale | `noesis review <itemId> 1.0` | belief and stability updated, review logged | **High** — F7 |
| 4 | that items can be inspected | `noesis items` | every item with belief, stability, review and lapse counts | Low |

*Verdict:* this is the most serious gap in the product. `modules/lms` contains a complete `Question`
model — prompt, typed answer, distractors, staleness detection against the source fact — and the CLI
never reaches it. The owner is shown the answer and asked to grade themselves on whether they knew
it. A spaced-repetition loop that cannot tell the owner they were wrong cannot produce the review log
§12.3 depends on. See F7.

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

*Role:* Learner · *Journey:* J3.2, J3.3 · *Spec:* §4.1, §4.3 · *Status:* **not built** — see F7

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
| **F7** | The review loop shows the fact and asks for a self-grade | J3.2, J3.3 | `Question`, `AnswerSpec`, distractors and staleness all exist in `lms` with no CLI surface; `queue` renders `item.prompt` | **Open** — US-07. Highest priority |
| **F8** | Duplicate candidates are detected and unreachable | J9.5 | Detection lives in `vocab`; no command exposes it | **Open** — US-17 |
| **F9** | No machine-readable output | J4, J7 | `Render` targets a terminal exclusively | **Open** — US-20 |
| **F10** | Two grammars for one relationship (`assert` vs `relationship-add`) | J10.3 | Reified records and direct assertions both model §7.1 relationships; no stated rule for choosing | **Open** — needs a design decision before a story |
| **F11** | `check` is manual | J1.5, J6.4 | Consistency is enforced at commit; policy and profile findings are only produced on demand | **Accepted** — commit-time consistency already fails closed (`DESIGN.md` invariant 2). Surfacing advisory findings automatically would add output to every command; the Curator asks when curating |
| **F12** | Capture cannot happen away from the terminal | all | No mobile or web surface (§2) | **Accepted** — local-first, single-device MVP. §10's sync is unbuilt by decision, not oversight |
| **F13** | No latency evidence for §10's budgets | J2, J3 | Nothing measures capture round-trip or review submit | **Accepted for now** — the budgets bind the LLM-backed capture path that does not exist yet. Revisit when it does |

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
noesis quiz
noesis undo
noesis agenda
noesis contact duplicates
noesis contact merge
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
