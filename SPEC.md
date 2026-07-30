# Noesis — Architecture & Specification
### A single-user knowledge & learning system on a formal knowledge representation

**Version:** 0.6 (Draft) — Personal Relationship Management and contact interchange design
**Status:** For review

---

## 1. Purpose & Principles

Noesis unifies *knowing* and *learning* for one person. Knowledge is stored as a description-logic knowledge base rather than free text; a learning engine tracks how well the owner has internalized each fact and quizzes to close the gap between what the system knows and what the owner knows.

1. **Single owner.** Exactly one human principal ("the owner," modeled in the KB as `core:me`). There is no human account management, no roles, no sharing between users. All access-control machinery governs *external parties only*: LLM providers, MCP agents, device sync, and exports.
2. **Formal core, natural surface.** The owner works in natural language; an LLM translates to and from the formal representation, which stays visible and editable.
3. **Human-in-the-loop commits.** Nothing enters the KB — from the owner's own captures or from agents — without the owner confirming the formal representation.
4. **The journal is the truth.** The append-only journal of asserted axioms is the only source of truth; every other graph (entailments, current state, balances) is a reproducible projection.
5. **Learning follows knowledge.** Every fact carries two orthogonal scores: belief `b` (how well it's *known*) and recall utility `u` (how much it's *worth knowing from memory*). The learning engine trains where `u` is high and `b` is uncertain or low.
6. **Extensible by vocabulary.** Specialized modules contribute ontology fragments, capture operators, item generators, and tools — never a parallel store.
7. **Local-first & private.** Default deployment is local; sync is end-to-end encrypted; per-axiom sensitivity gates everything that crosses the system boundary.

---

## 2. Architecture

```
Clients: Web / Mobile / CLI UI          MCP agents (external LLM apps)
        │ REST + WebSocket                      │ MCP (stdio | HTTP+OAuth)
┌───────▼────────────────────────────────────────▼─────────────────────┐
│ Application layer                                                    │
│   Capture Service · Learning Engine · Module Host · MCP Gateway      │
│   Shared services: Verbalizer · Agenda · Search · LLM Gateway        │
├──────────────────────────────────────────────────────────────────────┤
│ Knowledge Core orchestration · projections · policy · event bus      │
├───────────────────┬─────────────────────┬────────────────────────────┤
│ Semantic language │ Append-only journal │ Reasoner · query · explain │
├──────────────────────────────────────────────────────────────────────┤
│ Storage: RDF quad store · operational DB · vector/full-text index    │
└──────────────────────────────────────────────────────────────────────┘
```

| Component | Responsibility |
|---|---|
| Semantic language | OWL-style axiom algebra, identifiers, literals, annotations, fluents, and ternary views (§3.1, §3.6) |
| Journal | Append-only operation protocol and persistence; the sole source of truth (§3.2) |
| Reasoner | Graph closure, journal-backed justifications, consistency, profile checks, explanation, and query (§3.4) |
| Knowledge Core | Composes language, journal, and reasoner with projections, capture, policies, verbalization, and events (§3) |
| Capture Service | NL → formal pipeline with confirmation; verbalization round-trip (§3.5) |
| Learning Engine | Items, belief, scheduling, quiz generation & grading, remediation (§4) |
| Module Host | Loads modules; namespaces their ontology; mediates permissions (§5) |
| Shared services | Verbalizer, Agenda, Search, LLM Gateway — used by core and modules alike (§5.2) |
| MCP Gateway | The only surface for external LLM agents; scoped, filtered, propose-only (§9) |
| Event bus | `axiom.*`, `entailment.changed`, `state.changed`, `belief.updated`, `review.completed`, `agenda.due` |

---

## 3. Knowledge Core

### 3.1 Representation

- **Logic:** OWL 2 DL (SROIQ(D)) as the expressivity ceiling; the EL profile is preferred and the system warns when an axiom leaves it (EL keeps classification polynomial). TBox (concepts), RBox (role axioms incl. chains, e.g. `worksAt ∘ worksAt⁻ ⊑ colleagueOf`), ABox (individual assertions — where most personal facts live).
- Roles are binary; events and n-ary relations are reified individuals. Language-tagged strings and full XSD datatypes (partial dates like `--05-12` permitted).
- **Axiom identity:** every asserted axiom has a stable `axiomId` (RDF-star), so annotations, learning items, references, and justifications address axioms, not bare triples. Entity IRIs are opaque UUIDs — names are data (§7.2), so renames never break anything.

The executable language boundary and its serialization compatibility rules are specified in
[`modules/logic/SPEC.md`](modules/logic/SPEC.md).

### 3.2 Journal & Projections

The **journal** is an append-only log of operations (`assert`, `retract`, `reclassify`, annotation changes) on axioms. Everything else is a **projection**: cached, event-invalidated, and always rebuildable from the journal —

- *inferred graph* — materialized entailments;
- *current graph* — plain triples projected from ongoing fluents (§3.6), over which all standard DL reasoning about "now" runs;
- *module state* — e.g., resource balances and custody folded from economic events (§8).

This one principle yields time-travel queries, trivial backup (snapshot + journal), and audit for free. Module TBoxes live in namespaced graphs; free-text notes attach to entities/axioms (indexed for search and LLM context, but non-logical).

The executable operation protocol, replay ordering, and implemented durability guarantees are
specified in [`modules/journal/SPEC.md`](modules/journal/SPEC.md).

### 3.3 Annotations & the Policy Cascade

Every axiom carries annotation dimensions. All dimensions resolve through **one cascade** (highest precedence first): *explicit owner override → class/property policy → module default → behavioral & temporal signals*. Modules may register additional dimensions.

| Dimension | Values | Meaning |
|---|---|---|
| `truthConfidence` | [0,1] | Epistemic: how likely the fact is *true* (owner-confirmed defaults to 1.0) |
| `sensitivity` | 4 levels below | What may cross the system boundary |
| `knowledgeScope` | → `core:Agent`, required when `internal` | Which org/person the knowledge belongs to |
| `recallUtility` | [0,1] | How valuable it is to know this from memory, day to day (§3.3.2) |
| `status`, provenance | — | active/retracted/disputed; capture session, source span, reference locator |

#### 3.3.1 Sensitivity

Since there are no co-users, sensitivity governs only what crosses the boundary: remote LLM providers, MCP agents, exports, sync.

| Level | Definition | Boundary handling |
|---|---|---|
| `public` | Findable on the public internet | Unrestricted |
| `internal` | Learnable only inside a specific org / from specific people; not personal or PII. Carries `knowledgeScope` link(s) to that org/person | Disclosed only under a matching per-scope grant, e.g. `internal(org:acme)` |
| `personal` | Non-sensitive personal info about oneself or others (birthdays, preferences, who works where) | Explicit per-provider / per-agent grant |
| `sensitive` | Sensitive personal info (health, finances, legal, identity history, protected attributes) | Never leaves the device unencrypted; never sent to remote LLMs; never over MCP |

Capture *proposes* a level (and scope) from provenance heuristics — public-URL reference → `public`; touches a person → at least `personal`; learned in a work interaction → `internal(that org)` — and the owner confirms in the commit UI. **Derived facts:** disclosable under a policy iff at least one justification is *fully* disclosable under it; effective level = `min over justifications (max over axioms)`, internal scopes unioning within the chosen justification. A conclusion derivable from public facts alone is public, whatever other derivation paths exist.

#### 3.3.2 Recall Utility

`u` answers: *how valuable is knowing this from memory rather than looking it up?* The cascade supplies it: owner star/slider → class policy (`crm:birthday → 0.9`, `vf:EconomicEvent → 0.05`) → **module weight** (owner-tunable slider: e.g. relationships 0.9, accounting 0.2) → behavioral boost (views, query hits, briefing inclusions — agent reads weighted far below the owner's) → temporal boost (upcoming occasions, active goals). Utility decays slowly without reinforcement; a periodic "still important?" queue allows pruning. Effects on scheduling: §4.3.

### 3.4 Reasoning Services

Pluggable reasoner (ELK for EL; HermiT/Openllet for full DL): incremental **consistency** on every commit (inconsistent commits rejected with a justification), classification/realization, **entailment + explanation** (minimal justification sets — these power derived belief §4.4, disclosure filtering §3.3.1, and contradiction UX), SPARQL 1.1 and DL queries over asserted ∪ projected graphs, and entailment diffs emitted as `entailment.changed`.

The executable reasoner boundary, including the compatibility requirements for a future production
adapter, is specified in [`modules/reasoner/SPEC.md`](modules/reasoner/SPEC.md).

**Contradictions** — from a new capture or a `contradicts` reference link (§3.7) — surface the justification ("conflicts with: *A worksAt Acme*, added 2025-03-02") with resolutions: reject new, retract old, qualify temporally (close a fluent), or mark disputed (excluded from reasoning).

### 3.5 Capture

The signature workflow. Invariant: **nothing is stored until the owner commits the formal representation.**

1. **Context assembly** — vector retrieval of relevant ontology fragments and similar individuals, plus active modules' schemas, so the LLM maps onto existing vocabulary.
2. **LLM translation** — constrained JSON: candidate operations, each with formal axiom(s), Manchester rendering, translation confidence, and the source-text span. Ambiguities ("which Sarah?") become clarification prompts, never silent guesses.
3. **Entity resolution** — exact/alias match, then vector similarity; new entities flagged NEW and require label + type confirmation.
4. **Validation** — syntax, profile warning, consistency pre-flight on a scratch copy, redundancy check (already entailed → offer to skip).
5. **Confirmation** — each candidate shown three ways: verbalized NL, structured chips (each slot swappable), raw Manchester syntax. Owner edits, splits, deletes, adds; confirms sensitivity/scope and utility proposals. Batch queue for imports and long texts.
6. **Commit** — atomic; journal + provenance written; events emitted; learning items drafted.

Capture emits **operations**, not just assertions: `assert`, `retract`, `close-fluent`, `supersede` (§3.6) — and modules can register further operators (e.g., `vf:` "record loan" emitting an event + commitment bundle). Verbalization is a standalone shared service (§5.2): any axiom renders to NL in any configured language, honoring naming policy (§7.2).

**Privacy gate:** `sensitive` context is never sent to remote LLM providers; `personal`/`internal` only under an explicit per-provider policy; a local-model or manual path always exists.

### 3.6 Time: Fluents & State Change

Many personal facts are *states with a start and possibly an end*. "A started at Acme 2026-01-01 / stopped 2026-07-01" and "started using 'Alice', stopped using 'Adam', 2026-05-01" are **two states** (one employment, one name supersession) — not four facts.

```
core:Fluent            one continuous state of a time-varying property
  statedSubject · statedProperty · statedValue
  validFrom (absent ⇒ unknown) · validTo (absent ⇒ ongoing)
  supersededBy → Fluent · endReason ∈ {ended, superseded, corrected}
Properties marked core:timeVarying are captured as fluents
  (worksAt, livesIn, memberOf, hasName, pronouns, …)
```

- **Current-graph projection:** ongoing fluents materialize as plain triples in `graph:current` (§3.2); DL reasoning about "now" is unchanged; point-in-time queries rebuild the projection for any date.
- **Boundaries edit one fluent:** "stopped …" finds the matching open fluent and proposes closing it ("closing: *A worksAt Acme*, open since 2026-01-01"); no match → offer an already-closed historical fluent. A replacement is one confirmable **supersession**: close old + open new + link.
- **Sugar:** a plain assertion on a time-varying property silently opens an ongoing fluent — the common case never requires thinking about fluents.
- **LMS propagation:** closing/superseding emits `state.changed`; old-state questions go stale; if the historical fact retains utility it becomes a historical item; and a **change item** for the new value is created at *elevated* priority — the entrenched old answer will proactively interfere, so the change itself is drilled ("Where does A work *now*?").
- **Boundary rule:** fluents for slow, state-like properties; high-churn quantitative history (money, custody) is event-sourced instead (§8). Anything near the seam is modeled on the event side to avoid double bookkeeping.

### 3.7 References

Sources are first-class: facts link to *where they came from and where they're explained*, with pinpoint locators — provenance for the PKM, remediation for the LMS (§4.5).

```
ref:Reference ⊒ Book · AcademicPaper · Article · BlogPost · Video ·
               PodcastEpisode · Course · Lecture · Webpage · Document
  title · url · doi · isbn · publishedOn · duration
  language → ll:Language · creator → core:Agent · partOf → Reference
  readStatus ∈ {toConsume, consuming, finished, abandoned} · userRating
ref:Locator      page/section (text) · timestamp span (A/V) ·
                 text-quote + selector (web, W3C Annotation style)
ref:ReferenceLink   axiom(s) | entity  ↔  reference @ locator
  relation ∈ {supports, explains, elaborates, exampleOf,
              contradicts, primarySource} · linkQuality [0,1]
```

Ingestion: URL paste (OpenGraph/oEmbed; Crossref for DOIs, OpenLibrary for ISBNs) or file import. **Capture-from-reference:** highlighting text or marking a timestamp opens the capture pipeline pre-filled; committed axioms are auto-linked to that locator. **Retro-linking:** embedding similarity between reference segments and axiom verbalizations proposes links (always confirmed). Enables queries like "everything supported by this book" or "unwatched videos linked to what I'm learning."

### 3.8 Core API (sketch)

```
POST /kb/capture/sessions · POST /kb/capture/sessions/{id}/commit
GET  /kb/entities/{iri} · POST /kb/axioms · DELETE /kb/axioms/{id}
POST /kb/query (SPARQL|DL) · POST /kb/entails · GET /kb/axioms/{id}/justifications
GET  /kb/verbalize?axiomIds&lang
PATCH /kb/axioms/{id}/annotations          # sensitivity, utility, confidence
POST /kb/references (+/{id}/links) · GET /kb/axioms/{id}/references
WS   /kb/events
```

---

## 4. Learning Engine

### 4.1 Items & Questions

A **learning item** references one or more axioms. Kinds: `ATOMIC_FACT` (one assertion; bidirectional cloze/Q&A), `COMPOSITE` (small axiom cluster), `CONCEPT` (TBox-level idea, assessed by generated questions), `SKILL` (module-defined, with custom generators and graders). On `axiom.added` the engine drafts items per the policy cascade (§3.3): auto-activate, draft-for-review, or ignore, by class/property — `crm:birthday` always, phone numbers never unless starred. Retraction retires items; `state.changed` transforms them (§3.6).

**Questions** are stored objects: format (cloze, MCQ, short answer, case, translate, custom), answer spec (exact | set | LLM rubric), provenance (generator, model, hash of source axioms — a changed axiom marks questions *stale* for regeneration), and light IRT stats. **Distractors are ontology-grounded**: siblings under the same class, same-property values of similar individuals — plausible and diagnostic because the KB is formal. Rubric-graded answers always display the reference axioms; owner overrides feed item stats.

### 4.2 Belief

`b ∈ [0,1]` per item: the estimated probability the owner would, right now, correctly recall/apply the fact.

- Update on outcome `g ∈ [0,1]`: `b ← b + α·(g − b)`, α modulated by latency and question discrimination; stability `S` grows on success, shrinks on failure (FSRS-style; parameters refit from logged reviews).
- Decay between reviews: `b(t) = b_last · e^(−Δt/S)`; reads are always decay-adjusted.
- Priors from origin and structure: owner-authored > imported; modules supply hints (a cognate starts high, a false friend low — §6).
- Optional per-skill split `bBySkill` (used heavily by language learning, §6.4). Boundary values are meaningful: `b=1` suspends scheduling but keeps the score; `b=0` marks a known-unknown awaiting introduction.

*(Belief is distinct from `truthConfidence`: what the owner knows vs. what is true. They are never mixed.)*

### 4.3 Scheduling

Two selection policies, composed into a session budget allocated across modules by utility mass:

1. **Retention (spaced repetition).** Review when predicted `b(t)` falls below `R_target = 0.70 + 0.25·u` — high-utility facts held near 0.95, marginal ones allowed to fade. Items with `u < θ` (default 0.15) are stored-but-suspended: in the KB, not in your head. A small ε-fraction of each session samples low-`u` items so mis-scored utility stays discoverable.
2. **Elucidation.** `b ≈ 0.5` is maximum uncertainty — entropy `H(b)` peaks there, so one question yields maximal information. Queue weight `w = H(b) · u · recencyBoost`; questions are *probing*: for COMPOSITE/CONCEPT items the LLM composes short **cases** (scenario, application-level, rubric-graded) from the item's axioms and neighborhood. Outcomes sharpen `b` toward 0 or 1, after which the item joins retention.

Change items (§3.6) enter with elevated priority; occasion-linked items (§7.4) are front-loaded ahead of their dates regardless of `b`.

### 4.4 Belief for Derived Facts

Optional and lazy: for an entailed (non-asserted) fact `d`, take justifications `J₁…Jₘ` from the explanation service; combine within a justification by a t-norm (`∏ b(a)` default, or `min`), across justifications by the dual (`noisy-OR` default, or `max`), optionally discounted by an inference-difficulty factor shrinking with justification size — knowing the premises doesn't guarantee having connected them. Cached; invalidated by `entailment.changed`/`belief.updated`. Uses: elucidation of conclusions the owner may not realize they know; the browser's believed-vs-derived overlay. Reviews of derived facts back-propagate attenuated credit to premise items.

### 4.5 Remediation via References

When quizzing shows a fact isn't known (failed review, or `b` below a study threshold), the engine points at the exact place to relearn it: top-ranked ReferenceLinks with deep locators ("rewatch 12:30–15:45," "reread pp. 141–143"), one tap opening at the locator. For weak clusters sharing a reference, the scheduler inserts a **study task** instead of another futile quiz, followed by a short-delay re-quiz — exposure converted into measured belief, not assumed. Ranking: relation (`explains > primarySource > supports > elaborates`) × locator precision × linkQuality × media preference by context (audio/video on mobile). A coverage report lists *unsourced weak facts*; the `source_hunt` MCP prompt (§9) can delegate finding candidates, confirm-gated as always.

### 4.6 API (sketch)

```
GET  /lms/queue?mode=retention|elucidation|mixed
POST /lms/reviews {questionId, response, latencyMs}
GET|PATCH /lms/items/{id}          # belief, utility, suspend, prioritize
GET  /lms/beliefs?axiomIds=…       # overlay lookups
GET  /lms/items/{id}/resources · POST /lms/study-tasks · GET /lms/coverage
```

---

## 5. Modules & Shared Services

### 5.1 Module Contract

A module is a versioned package declaring: namespaced ontology fragments (TBox/RBox), capture prompt fragments and operators, record validators, import/export adapters, naming schemes, item types with generators/graders, annotation policies (utility & sensitivity defaults), agenda producers, MCP tools, UI panels, event subscriptions, and permissions. Installing shows the ontology diff for approval; the merged TBox must stay consistent. Module facts are full citizens — same journal, annotations, reasoning, capture UX, belief — specialization is vocabulary plus generators, never a parallel store. Modules run sandboxed with declared permissions; LLM access goes through the shared gateway, so the privacy gate applies uniformly. Modules may reference other modules' public terms (e.g., `ref:creator → core:Agent`).

### 5.2 Shared Services

- **Verbalizer** — any axiom → NL, template-first with LLM fallback, per configured language; always honors current names/pronouns (§7.2). Used by confirmation, browsing, quiz generation, MCP resources.
- **Agenda** — one queue of dated obligations from all modules: occasions (birthdays, anniversaries), due commitments (return the drill), keep-in-touch cadences, review sessions. Modules publish `AgendaItem`s; the engine handles lead times, snooze, and `agenda.due` events. Replaces per-module reminder systems.
- **Search** — unified label / full-text / vector retrieval over entities, axiom verbalizations, notes, and reference segments; powers entity resolution, retro-linking, and MCP search.
- **LLM Gateway** — provider-agnostic (remote + local); constrained JSON output; enforces the sensitivity gate on every prompt assembled anywhere in the system.

---

## 6. Module: Polyglot Language Learning (`ll:`)

**Goals.** An owner knowing e.g. Spanish, English, French and learning Russian can be quizzed *from any base language toward the target* (and reverse); the representation is traversable across languages; mastery is per direction and skill; cross-linguistic structure (cognates, false friends, contrasts) is exploited.

**Interlingual hub-and-spoke.** Meanings are language-neutral `Concept` nodes; words are per-language `Lexeme`s linked to concepts. Translation is a traversal `Lexeme → Concept → Lexeme` — never a default word→word edge — which scales linearly in languages and makes any-base quizzing trivial.

```
ll:Language (es, en, fr, ru, … each tagged native|fluent|learning)
ll:Concept          interlingual meaning; linked to WordNet/Wikidata when possible
ll:Lexeme           lexicalizes → Concept (m:n) · inLanguage · register
  hasForm → ll:WordForm (case, number, gender, tense, aspect, …)
  cognateOf ↔ · falseFriendOf ↔ · confusableWith ↔ · derivesFrom (etymology)
ll:GrammarTopic · GrammarRule (appliesTo lexeme/form classes)
  contrastsWith ↔ GrammarTopic     # e.g. Russian aspect ↔ Spanish preterite/imperfect
ll:ExampleSentence  attested usage, links lexemes/forms/topics
```

E.g. `c:DOG` ← es:*perro*, en:*dog*, fr:*chien*, ru:*собака* (+ WordForm "собаки", genitive sg); ru:*магазин* `falseFriendOf` en:*magazine*. SPARQL paths answer "Russian words whose Spanish equivalent shares a Latin root" or "all EN↔RU false friends I've captured" — queries the quiz generator itself uses.

**Capture.** Frequency lists / Anki decks / CSV import through the batch queue; NL ("собака means dog, genitive собаки" → lexeme + link + form); **reading mode**: paste target-language text, unknown lexemes glossed and offered as candidates with the example sentence attached.

**Belief tensor.** Items are keyed `(concept/lexeme, baseLanguage→targetLanguage direction, skill)` with `skill ∈ {recognition, production, form-production, listening, spelling}` — producing *собака* from French is not implied by recognizing it from English. Directions share stability partially (correlated boost, configurable). Priors: cognates start high (es:*constitución* → ru:*конституция*), false friends start low and high-priority.

**Quizzing.** The dispatcher picks item → weakest skill → base language (round-robin | weakest-direction | owner-weighted): translation both ways (production graded against the concept's full lexicalization set, so synonyms count), form production ("genitive sg of собака?"), cloze from stored sentences, MCQ with ontology distractors (same-concept-family lexemes; false friends injected deliberately), contrast probes from `contrastsWith`, and LLM cases for elucidation. Wrong answers that map onto a neighboring lexeme auto-create `confusableWith` pairs → targeted discrimination drills.

```
GET /ll/concepts/{id}/lexicalizations?langs · GET /ll/lexemes/{id}/related?rel
GET /ll/mastery/summary?targetLang · POST /ll/import · GET /ll/reader/gloss
```

---

## 7. Module: Personal Relationship Management (`crm:`)

**Goals.** Traditional contact management (names, addresses, email, phone, online accounts and employment) joins people, organizations, relationships, life events, interactions, gifts, preferences and follow-up in one semantic model. Reasoning operates over the social graph, while the learning engine keeps the owner *fluent* in relationships (names, dates, kids, preferences) rather than treating lookup data such as phone numbers as memory material.

[Monica](https://www.monicahq.com/features), an open-source Personal Relationship Management system, is informative product-design inspiration. Its [published feature set](https://github.com/monicahq/monica#features) and [API resource model](https://www.monicahq.com/api) motivate typed contact methods, addresses, relationships, activities, notes, reminders, tasks, gifts, pets and labels. Noesis does not adopt Monica's storage schema or API: these features remain ordinary journaled axioms and projections, with no parallel contact store. The implementation plan and detailed mapping are recorded in [PRM_PROPOSAL.md](PRM_PROPOSAL.md).

### 7.1 Ontology

```
crm:Agent ⊒ crm:Person, crm:Organization   (⊑ core:Person, core:Organization)
crm:NamedEntity ⊒ crm:Person, crm:Organization, crm:CompanionAnimal
Data: birthday (partial dates ok) · namePronunciation · metOn/metAt

crm:ContactMethod
  subclasses: EmailAddress · TelephoneNumber · OnlineAccount · PostalAddress
  contactFor → Agent · contactKind → extensible string token
  contactValue (time-varying) · contactLabel · contactPurpose
  contactStatus (active|retired|invalid, time-varying)
  preferenceRank (time-varying)
PostalAddress:
  formattedAddress · streetAddress · extendedAddress · locality · region
  postalCode · countryCode

crm:ExternalIdentifier: identifierFor → Agent · identifierScheme · identifierValue
crm:Employment:
  employmentFor → Person · employer → Organization
  jobTitle · department · workLocation
  employmentStatus (active|ended, time-varying)

crm:Interaction  reified: participants · date · channel · note ·
                 kind from an extensible vocabulary ·
                 mentionedTopic → any KB entity      # ties talk to the whole KB
crm:LifeEvent · crm:Gift {to/from, occasion, status ∈ idea|planned|given|received}
crm:Preference (likes/dislikes/allergies/topics-to-avoid)
crm:ContactNote: about → Agent · noteBody · noteKind · recordedAt
crm:Circle: member → Agent
crm:FollowUpPlan: followUpWith → Agent · cadenceDays · qualifyingChannel · paused
crm:Reminder: about → Agent · due · recurrence · occasion
crm:CompanionAnimal: companionOf → Agent

Informative FOAF alignment:
  crm:Agent        ⊑ foaf:Agent
  crm:Person       ⊑ foaf:Person
  crm:Organization ⊑ foaf:Organization
  crm:Circle       ⊑ foaf:Group
  crm:member       ⊑ foaf:member

Relationships — cardinality-free by design:
  knows (symmetric) ⊒ friendOf, partnerOf
  partnerOf: NO functionality/cardinality — concurrent partners are first-class
  spouseOf ⊑ partnerOf (also cardinality-free)
  parentOf/childOf: inverses, no arity or gender assumptions;
    kind via reified Parenthood (biological|adoptive|step|foster|chosen)
  chosenFamilyOf · siblingOf · reportsTo · mentorOf · introducedBy
  worksAt: Person → Organization   (time-varying → fluent)
  crm:Relationship (reified): 2+ participants · kind from an EXTENSIBLE
    vocabulary (partner, spouse, queerplatonic, metamour, co-parent, …)
    + free-text self-description · time-bounded as a fluent
RBox default:  worksAt ∘ worksAt⁻ ⊑ colleagueOf        (over graph:current)
Rule-derived:  metamourOf ← partnerOf ∘ partnerOf − (partnerOf ∪ identity)
Opt-in packs:  in-law/kinship chains — they encode family structures
               that do not hold universally
```

No disjointness between relationship kinds (a co-parent can be a friend and a former partner). Anniversaries attach to *relationships*, not persons, so each partnership has its own dates and gift/occasion logic iterates per relationship.

Contact methods, addresses and employments are reified because each Agent may have several simultaneous values and each value has its own label, purpose, preference, provenance and history. The current fluent capture surface is single-valued per subject/property; reification gives each method or employment its own time-varying status without weakening that invariant. Active Employment records derive the compatibility fact `worksAt`, with their premises retained as its justification. "Last contacted", contact completeness, the current contact card and the next keep-in-touch date are projections, never stored facts.

### 7.2 Identity & Names

- `hasName` is a fluent from `NamedEntity` to Name objects: `nameValue · nameKind ∈ {chosen, legal, nickname, professional, former} · script/language variants` (the `ll:` verbalizer transliterates: Alice / Алиса). `NamedEntity` covers people, organizations and companion animals without making animals `core:Agent`s. A rename is one supersession (§3.6).
- **Former names:** default `sensitive`; excluded from verbalization, search suggestions, quizzes, and MCP. Per name the owner chooses *retain* (hidden, e.g. for legal documents), *suppress* (never displayed, encrypted journal only), or *purge* (hard delete with journal scrubbing).
- The Verbalizer always uses the **current** name and pronouns, including about past periods ("Alice worked at Acme in early 2026"), unless configured otherwise per person.
- `pronouns`: time-varying, optionally per-language (grammatical agreement — Russian past tense, Romance adjectives). `gender`: free-text self-description; never an enum, never inferred from names, pronouns, or relationships.
- Life events include transition milestones and coming-out: default `sensitive`, never surfaced in quizzes or briefings without explicit opt-in — who-knows-what about a person's identity can be safety-critical.
- Name/pronoun supersessions create the *highest-priority* change items: the entrenched old answer is exactly what must be overwritten — the system helps the owner not misname people.

### 7.3 Capture

"Had lunch with Sarah and her husband Marco; she just started at Molina Labs; their daughter Lía turns 5 on May 12" → one confirmable bundle: Interaction(participants), spouseOf, worksAt-fluent opened (+ NEW org), parentOf, birthday. Quick templates: "gift idea for X: …", "met X at Y through Z". vCard/calendar importers land in the batch queue with lowered `truthConfidence` until confirmed.

Structured contact commands cover contact creation, typed methods, addresses, employment, interactions and follow-up plans. Each command produces one atomic intent bundle and passes through ordinary pre-commit consistency and policy validation. vCard 4.0 is the interchange target: `FN`/`N`/`NICKNAME` map to Name objects; `EMAIL`/`TEL`/`IMPP`/`URL` to ContactMethod; `ADR` to PostalAddress; `ORG`/`TITLE`/`ROLE` to Employment; `RELATED` to relationship candidates; and `UID` to ExternalIdentifier. Import matches yield reviewable identity candidates, never automatic `SameIndividual` assertions. Export is disclosure-filtered and uses current values.

FOAF is the linked-data alignment and mapped RDF boundary, not the canonical contact model. FOAF imports translate `Person`, `Organization`, names, mailboxes, phones, online accounts, groups, membership, birthdays and person-to-person `knows` statements into reviewable PRM candidates with source provenance and lowered confidence. Subject IRIs and inverse-functional FOAF properties are match evidence, never automatic identity merges. Disclosure-filtered export maps current names and allowed contact methods; social edges are opt-in. `crm:knows` is not a subproperty of `foaf:knows`: the former admits Organizations, while FOAF's domain and range are Person. The full FOAF ontology is not imported; stable one-way class and membership alignments are installed, while `testing` or `unstable` contact terms remain adapter mappings. [PRM_PROPOSAL.md](PRM_PROPOSAL.md) records the field-level mapping and privacy constraints.

### 7.4 Learning, Agenda & Views

- Default item policy: birthday, current name/pronouns, partners'/kids' names, pronunciation, starred preferences; contact data ignored unless starred. Occasion items front-load before their dates; calendar integration triggers **pre-meeting micro-quizzes** and a briefing (facts + belief tint + open loans/favors from §8 + recent interactions).
- Elucidation cases: "You run into Marco at a conference — who's his partner, and what should you congratulate the family on?" — rubric-graded against the KB.
- Agenda: occasions with lead times; per-person keep-in-touch cadence vs. last qualifying interaction → overdue queue. Birthday and anniversary entries are projections of facts, not duplicated Reminder records.
- Views: contact card (current methods and addresses), person page (belief-tinted facts, timeline, gift ledger, agenda), org page (current employment and derived colleague clusters), graph explorer (inferred edges rendered distinctly).
- Sensitivity defaults: `personal`; postal-address components and health/finance/legal/identity/conflict notes default to `sensitive`; facts learned *through* someone about their org → `internal(that scope)`. Contact methods and addresses are ignored by learning unless explicitly starred.
- Monica-style favorites reuse recall-utility and briefing annotations rather than adding another importance flag. Debts and favors reuse `vf:Commitment` / `vf:Claim`; documents and photos use `ref:Reference` once §3.7 exists.

```
GET /crm/people/{id}/briefing · GET /crm/graph?root&includeInferred
GET /crm/people/{id}/contact-card · GET /crm/follow-ups?due
POST /crm/contact-methods · /crm/interactions · /crm/gifts
POST /crm/import/vcard · GET /crm/people/{id}/vcard
POST /crm/import/foaf · GET /crm/people/{id}/foaf
```

---

## 8. Module: Resources & Accounting (`vf:`)

Adopts the [ValueFlows](https://valueflo.ws) ontology (REA: Resources, Events, Agents) directly — its distinctions map onto personal needs exactly: *ownership vs. custody* **is** lending/borrowing; *commitments and claims* **are** favors. One alignment axiom: `vf:Agent ≡ core:Agent`, so the Marco holding your drill is the same Marco in §7. The owner is `vf`'s perspective agent `core:me`.

```
vf:EconomicResource   drill, book, bank account
  conformsTo → ResourceSpecification · primaryAccountable → Agent
  accountingQuantity / onhandQuantity     (custodian DERIVED from events)
vf:EconomicEvent      append-only ledger
  action ∈ {transfer, transfer-custody, transfer-all-rights, use, consume,
            produce, work, deliver-service, raise, lower, …}
  provider · receiver · resourceInventoriedAs · quantity · time
vf:Commitment  promised future event (due date) · fulfilledBy → Event
vf:Claim       owed, triggeredBy → Event        vf:Intent  unmatched want/offer
vf:Agreement   bundles related commitments/events ("the drill loan")
```

**Flows.** *Lending:* `transfer-custody(drill, me→marco)` — custody moves, accountability stays; "out on loan" is derivable (`primaryAccountable=me ∧ custodian≠me`); paired return `Commitment(due=+2w)` in an Agreement; the due date enters the Agenda; a return event fulfills it. *Borrowing* is the mirror. *Favors:* helping Ana move = `work` event, optionally raising a `Claim` (she owes one) or fulfilling a commitment I owed; the per-agent **favor ledger** = open claims/commitments both directions; offers and needs are `Intent`s. *Money:* accounts are resources; events adjust quantities; **balances are a fold over the event history** (§3.2 projection principle — never mutable state).

**Capture.** "Lent my drill to Marco yesterday, back in two weeks" → resource match-or-create + custody event + return commitment + agreement, one confirmable bundle. "Ana owes me a favor for Saturday" → work event + claim. Bank/CSV import → draft events in the batch queue.

**Defaults.** Sensitivity: monetary amounts/balances `sensitive`; object loans & favors `personal`; org-context resources `internal(org)`. Utility: module weight low (~0.2) — a ledger is lookup data — except open loans and open favor claims (medium: the things that matter in daily social life), which also surface in §7 briefings. MCP tools: `vf_open_loans`, `vf_ledger(agent)`, `vf_balances` (the last effectively never disclosed, being `sensitive`).

```
GET /vf/loans?direction&status · GET /vf/claims?agent
GET /vf/agents/{id}/ledger · GET /vf/balances · POST /vf/events|commitments|intents
```

---

## 9. MCP Gateway

External LLM agents (desktop assistants, tutors, reading/research agents) are, besides sync, the only "other parties" in this single-user system. The gateway is their sole surface, and it inherits the core invariants rather than bypassing them.

- **Transport & auth:** stdio locally; streamable HTTP remotely with OAuth 2.1 + PKCE. The owner authorizes each agent with an access-scope set and a **disclosure policy**: default `public` only; `internal` grantable per knowledge scope (`internal(org:acme)` exposes that org's knowledge and nothing else); `personal` per explicit grant; `sensitive` never. Every call is journaled per agent; tokens revocable; access history reviewable.
- **Scopes:** `kb.read` (search, fetch, SPARQL, verbalize, entail/explain) · `kb.propose` · `ref.read/propose` · `lms.read` · `lms.review` (tutoring agents deliver questions and record outcomes) · `module.<id>.*` (declared tools).
- **Tools:** `kb_search`, `kb_get_entity` (axioms + verbalizations + belief + references), `kb_query`, `kb_entails`/`kb_explain`, `kb_verbalize`, `kb_propose_facts` (runs the capture pipeline through validation → pending session), `ref_search/get/propose`, `lms_belief`, `lms_get_queue`, `lms_record_review`, plus module tools (`crm_briefing`, `ll_gloss`, `vf_open_loans`, …).
- **Resources & prompts:** `noesis://entity/{iri}`, `noesis://reference/{id}`, `noesis://capture-session/{id}`, and `noesis://ontology/{graph}` so agents reuse vocabulary instead of inventing it. Prompts: `capture_from_conversation`, `tutor_session`, `source_hunt`.
- **Invariants:** (1) *Propose-only writes* — no MCP path commits; everything lands as a pending confirmation session badged with the agent's identity, so the human-in-the-loop guarantee is protocol-level and prompt-injected agents are backstopped. (2) *Filtered reads* — the disclosure policy filters every surface including SPARQL results and justifications; derived facts follow §3.3.1 (disclosed only via a fully-disclosable justification, others redacted with a marker). (3) Rate and result-size limits.

---

## 10. Non-Functional Requirements

| Area | Requirement |
|---|---|
| Single-user | No human accounts/roles; local UI protected by device security; all access control targets external parties (providers, agents, sync, export) |
| Privacy | Local-first; optional E2E-encrypted multi-device sync; per-axiom sensitivity gates all egress; full export (Turtle/OWL + JSON) anytime — no lock-in |
| Performance | Capture round-trip < 3 s p50; incremental consistency < 500 ms at 10⁶ EL axioms; review submit < 200 ms |
| Reliability | Journal is append-only and atomic; every projection reproducible from it; daily snapshots |
| LLM independence | All LLM touchpoints behind one gateway; local-model fallback; every LLM output owner-confirmable or overridable |
| Auditability | Every axiom → source; every belief change → review or decay; every agent call → journal |

## 10.1 Standards Conformance

**What "normative" means here.** A reference is cited normatively only where Noesis both conforms to
it *and* tests that it does. An untested normative citation is a false claim, so the list is short
by construction and grows as coverage does. Everything else is *informative*: it influenced the
design, and we do not promise to conform. Conformance is further **scoped** — we conform on the
constructs we implement, never on a whole specification. §3.1 already takes this stance for OWL 2 DL
("the expressivity ceiling"), and the reference sections inherit it.

**What availability means here.** Retrievability is *recorded*, not required. A reference is cited
on the strength of what it governs; whether it is free to obtain is a fact about the reader's costs,
so citations carry an availability marker — ⊘ means a purchase is required, and an unmarked
reference is freely retrievable — and nothing is excluded for its price alone. This replaces an earlier rule that admitted only freely retrievable documents,
which had the perverse effect of excluding the most directly applicable standards in the areas this
project cares most about — naming, definitions, concept systems and terminology — while admitting
weaker surrogates.

Three consequences are deliberate. A free equivalent is still preferred where one says the same
thing, which is why RFC 3339 stands in for ISO 8601 and ISO/IEC 21778 is cited beside RFC 8259.
Where a paywalled document is cited *normatively*, the requirements it imposes are reproduced in
this repository — the ISO/IEC 11179-5 naming rules are in
`modules/conformance/src/test/resources/mdr/naming.json` in full — so that conformance can be
verified by a reader who has not bought the standard, even though the requirement itself cannot be
read without it. And the testing rule below is untouched: price was never the load-bearing
constraint, evidence is. Two references remain uncited for reasons that are *not* about price:
ISO 15944-4 (which is why §8 cites ValueFlows rather than REA directly) and IEEE 9274.1.1 (xAPI),
where the open vocabulary is the better fit rather than merely the cheaper one.

**Where the references live.** Each module `SPEC.md` carries its own `Normative references` and
`Informative references` sections, scoped to what that module implements. The cross-cutting ones —
cited by more than one module — are below; modules cite up to these rather than repeating them.

| Reference | Cited by |
|---|---|
| [ISO/IEC 21778:2017](https://standards.iso.org/ittf/PubliclyAvailableStandards/) / [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) — JSON | `logic`, `journal` |
| [RFC 7493](https://www.rfc-editor.org/rfc/rfc7493) — I-JSON | `logic`, `journal` (writing only) |
| [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) — JSON Canonicalization Scheme | `logic`, `journal` |
| [RFC 3987](https://www.rfc-editor.org/rfc/rfc3987) / [RFC 3986](https://www.rfc-editor.org/rfc/rfc3986) — IRI, URI | `logic` |
| [RFC 3339](https://www.rfc-editor.org/rfc/rfc3339) — timestamps | `journal`, `lms` |
| [RDF 1.1 Concepts](https://www.w3.org/TR/rdf11-concepts/) | `logic` |
| [XSD 1.1 Part 2](https://www.w3.org/TR/xmlschema11-2/) — datatypes | `logic` |
| [BCP 47](https://www.rfc-editor.org/info/bcp47) — language tags | `logic`, `ll:` (§6) |
| [OWL 2](https://www.w3.org/TR/owl2-syntax/) — structure, semantics, profiles | `logic`, `reasoner` |
| ⊘ ISO/IEC 11179-5:2015, *Metadata registries — Part 5: Naming principles* | `vocab`, `logic` (via `modules/vocab/NAMING.md`) |

**Conformance testing.** `modules/conformance` runs corpora against these, separately from the
module suites: those ask whether the implementation does what we intended, these ask whether what we
intended matches the specification. Known departures are recorded in
`modules/conformance/DEVIATIONS.md` with the clause each departs from — a conformance failure that
is not a recorded deviation is a bug, and must never become a skipped test.

**Informative references.** The following influence the design without making a conformance claim
and are candidates for narrower normative use as matching subsystems and corpora are built:
[SKOS](https://www.w3.org/TR/skos-reference/) and
[SKOS-XL](https://www.w3.org/TR/skos-reference/skos-xl.html) for §7.1's extensible relationship
vocabulary and §7.2's Name objects; [OWL-Time](https://www.w3.org/TR/owl-time/) for the fluent
boundaries of §3.6; [Web Annotation](https://www.w3.org/TR/annotation-model/) plus
[Media Fragments](https://www.w3.org/TR/media-frags/) for the locators of §3.7, which §3.7 already
gestures at; [OntoLex-Lemon](https://www.w3.org/2016/05/ontolex/) for §6, whose hub-and-spoke design
it already matches; [SHACL](https://www.w3.org/TR/shacl/) for §3.5.4's validation step;
[ODRL](https://www.w3.org/TR/odrl-model/) and [DPV](https://w3c.github.io/dpv/) for §3.3's
disclosure policy and sensitivity levels; and [PROV-O](https://www.w3.org/TR/prov-o/) for §10's
auditability requirement. For §7, [RFC 6350](https://www.rfc-editor.org/rfc/rfc6350), the
[W3C vCard Ontology](https://www.w3.org/TR/vcard-rdf/) and the
[FOAF Vocabulary Specification](https://xmlns.com/foaf/spec/) inform contact and linked-data
interchange, while
[Monica's feature set](https://www.monicahq.com/features) and
[public API](https://www.monicahq.com/api) inform the PRM product surface; none is a conformance
claim yet.

[ISO/IEC 21838-2](https://standards.iso.org/ittf/PubliclyAvailableStandards/) (Basic Formal
Ontology) belongs on the same list, and is the likeliest of any of them to become normative. It is
the published answer to §12.6's open question about how much upper ontology belongs in `core:`, and
the checking is already possible: ISO/IEC 21838-1 §3.23 defines conformance of a domain ontology to
a top-level ontology as consistent extension, and its Annex D.2 — informative, but mechanical —
spells that out as two conditions Noesis can decide today, that the merged ontology is consistent
(the §3.5.4 pre-flight) and that every domain term reaches a TLO term through a chain of `is-a`
relations (a closure query). What is missing is the alignment itself, not the means to check it.
[ISO/IEC 19788-1](https://standards.iso.org/ittf/PubliclyAvailableStandards/) (Metadata for Learning
Resources) is the candidate for §9's reference metadata, and
[ISO/IEC 2382-36](https://standards.iso.org/ittf/PubliclyAvailableStandards/) for §4's learning
vocabulary.

**The terminology and metadata-registry standards.** These are cited informatively below and
purchased rather than free (⊘). They are the settled answers in areas where §5–§7 currently invent
their own, and they are grouped here because they stand or fall together: each becomes normative
only when the vocabulary carries the artefact it governs.

| Reference | Governs | What would make it normative |
|---|---|---|
| ⊘ ISO/IEC 11179-4:2004, *Metadata registries — Part 4: Formulation of data definitions* | §5's module terms, once they carry definitions | Its §4.1 sets five requirements on a definition: singular, stating what the concept *is*, a descriptive phrase, only common abbreviations, no embedded definitions. Module terms carry no definitions at all today, so the requirements have nothing to bind to. A `definitions` seam on `Module`, then a corpus |
| ⊘ ISO/IEC 5394:2024, *Criteria for concept systems* | §6's concept graph, and §5's ontology as a concept system | Its requirements are qualitative — comprehensiveness, clarity, extensibility, stability (§5.3) — so it informs rather than binds. Its one hard `shall` (§5.2.4, naming per ISO 704) points at a standard this project does not hold |
| ⊘ ISO 1087:2019, *Terminology work and terminology science — Vocabulary* | the words §6 and §7.2 use for *concept*, *designation*, *term*, *definition* | A vocabulary standard binds by being used as defined. Normative once the spec's own glossary states the alignment term by term |
| ⊘ ISO 5127:2017, *Information and documentation — Foundation and vocabulary* | §3.7 and §9's language for documents, references and locators | The same gate, for the reference model rather than the concept model |
| ⊘ ISO/IEC 29100:2024, *Privacy framework* | §3.3's sensitivity and disclosure model | Its terminology (PII, PII principal, PII controller, third party) is settled where §3.3 names its own concepts, and its privacy principles are the published frame for "fail closed". Normative when the disclosure model is stated in its terms and the mapping is tested. The 2024 revision supersedes 29100:2011; the older edition is free and is *not* what is cited here |

Paywalled references are given by designation, date and title rather than as links, per ISO/IEC
Directives, Part 2 §10.3 — a catalogue URL is neither stable nor retrievable without a purchase.

**On the vocabulary of this section.** The terms are used in the sense the standards bodies give
them. [ISO/IEC Directives, Part 2](https://www.iso.org/directives-and-policies.html) §15.1 defines a
normative reference as one cited such that "some or all of [its] content constitutes requirements of
the document", and §10.2 puts everything else in the Bibliography — which is what the module
`Informative references` sections are. ISO/IEC Guide 2 §3.1 defines the "normative document" being
referenced. The availability rule above is theirs too: §10.2 admits a normatively referenced document
that is either free or available on fair, reasonable and non-discriminatory commercial terms, which
is what an ISO standard sold at list price is. One difference is deliberate — the Directives nowhere
require that conformance be *tested* before a reference is cited normatively. That is this project's
addition, and with price no longer filtering anything, it is the only thing keeping the table above
honest.

## 11. Reference Stack (non-normative)

RDF quad store with RDF-star (Jena TDB2 / Oxigraph); ELK (EL) with HermiT/Openllet escalation; OWL API for explanations. Services in Kotlin/JVM or Python (owlready2 + rdflib); PostgreSQL for operational state; pgvector/Qdrant; in-process event bus. LLM via provider-agnostic gateway (Anthropic API + Ollama local); MCP official SDK. ValueFlows RDF vocabulary imported as-is. Local-first client (web/PWA, optional Tauri shell); Cytoscape.js graph view.

## 12. Risks & Open Questions

1. **Confirmation fatigue** — the main UX risk, amplified by agent-generated proposals; batch queues and per-class auto-policies must scale before any auto-commit mode ships.
2. **Fluent/event seam** — two temporal mechanisms (§3.6/§8); facts near the boundary must be modeled event-side to avoid double bookkeeping.
3. **Belief model validity** — α-update + exponential decay is a starting point; log everything from day one for FSRS-style per-owner refitting.
4. **Justification blow-up** — explanation is worst-case expensive; cap count/size, degrade to min/max combination.
5. **Concept granularity (`ll:`)** — polysemy splitting; default to WordNet granularity, split on demand at capture.
6. **Core upper ontology** — `core:Agent/Person/Organization` exist for scoping and alignment; how much more (identifiers, merge/dedup) belongs in core is open.
7. **MCP exposure** — even read-only access aggregates personal data into third-party context; mitigations: `public`-default disclosure, scoped `internal` grants, `sensitive` exclusion, per-agent audit and revocation.
8. **Classification correctness** — the sensitivity model is only as good as its labels; conservative heuristics, reclassification review queues, and the justification-based derivation rule bound the damage.
9. **Link rot** — optional archival snapshots at link time so locators outlive URLs.
10. **Utility feedback loops** — unimportant-scored facts are never quizzed, hiding their errors; behavioral boosts self-reinforce. Mitigations: ε-exploration, the "still important?" queue, agent reads discounted in signals.
11. **Contact identity and merge safety** — names, email addresses and phone numbers are evidence,
    not identity keys; imports need explainable candidate matching and explicit owner-confirmed
    merges without losing journal provenance. FOAF inverse-functional properties remain
    interoperability statements and never bypass that confirmation boundary.

---

*End of specification v0.6.*
