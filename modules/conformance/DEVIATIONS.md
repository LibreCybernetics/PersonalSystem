# Conformance deviations

**Status:** Implementation report
**Authority:** Records where Noesis knowingly departs from a specification cited as normative in a
module `SPEC.md`. Anything not listed here is expected to conform.

A conformance suite is only worth running if "known-fail" is explicit. A skipped test says nothing;
an entry here says what we do instead, why, and what it would cost to close. Every deviation must
name the clause it departs from. If a conformance case fails and is not in this table, that is a
bug, not a deviation.

Deviations are reviewed when the clause they cite is touched. Adding one requires the same evidence
as a behavior change: the case that demonstrates it, and the reason the alternative is worse.

---

## D2 — Years outside 0001–9999 are not supported

**Clause:** XSD 1.1 Part 2 §3.3.9
**What we do:** `PartialDate.render` pads years to four digits with `%04d`. XSD admits more than
four digits and negative years; `PartialDate.parse` rejects any input starting with `-`, so a
negative year cannot be read back at all.
**Why:** no capture path produces one, and supporting them would mean a real date-lexical parser.
**Consequence:** a negative year renders as `-005` rather than the conformant `-0005`, and does not
round-trip.
**How it closes:** EDTF Level 1 spells a negative year `-1985` and Level 2 spells a large one
`Y-17E7`; either way it needs the date-lexical parser this entry has always been waiting for.

## D11 — A date value carries no timezone

**Clause:** XSD 1.1 Part 2 §3.3.9–§3.3.11 (the optional timezone in each lexical space)
**What we do:** `Datatypes` accepts a timezone wherever XSD admits one, because that clause is what
the normative citation is against. `PartialDate.parse` declines it by name — *"a date carries no
timezone in Noesis"* — so `Literal("2026-05-12+02:00", xsd:date)` is a valid term that `asDate`
returns `None` for.
**Why:** a Noesis date is a calendar date about someone's life. XSD's timezoned dates denote
intervals offset from UTC rather than days, and the value model has no zone to record; accepting and
dropping the offset would lose data silently, and inventing a zone field would put a zone on a
birthday.
**Consequence:** an imported literal may be a date this system will not read. It is preserved
verbatim, reported as a date by `isDate`, and refused by the value reader with a message that says
why. Previously the same input failed with `not a number: 12+02:00`, which named neither the cause
nor the boundary.

---

## Partial dates have a standard notation, and it is still not the one we use

Not a deviation — nothing here departs from a cited clause. It is recorded beside D2 because it is
the migration that entry points at, and because the reason it was not taken before no longer holds.

ISO 8601-2:2019 covers every shape a date can take, including the ones D1 named before it was
closed. It was excluded when this project only cited freely retrievable standards, and the exclusion
outlived that rule: the [Extended Date/Time Format](https://www.loc.gov/standards/datetime/),
maintained by the Library of Congress and freely published, is included in ISO 8601-2 as a profile —
all of EDTF's features are supported by Part 2. Aligning with EDTF therefore aligns with ISO 8601-2
without holding it.

| Value | Rendered | Datatype | EDTF | Level |
|---|---|---|---|---|
| `PartialDate.Day` | `2026-05-12` | `xsd:date` | `2026-05-12` | 0 |
| `PartialDate.Month` | `2026-05` | `xsd:gYearMonth` | `2026-05` | 0 |
| `PartialDate.Year` | `2026` | `xsd:gYear` | `2026` | 0 |
| `MonthDay` | `--05-12` | `xsd:gMonthDay` | `XXXX-05-12` | 2 |

Level assignments follow the examples in the LoC specification: Level 1 admits `X` in a component's
rightmost digits (`2004-XX`, `2004-06-XX`), Level 2 admits it anywhere (`XXXX-12-XX`).

The case is weaker than it was, which is worth saying plainly. Narrowing the value model removed the
shapes only EDTF could express, so the remaining argument for it is uniformity — one lexical form
and one datatype instead of four — plus the negative and large years of D2. Against that, three of
the four rows change lexical form, and because `AxiomId` is a digest of the canonical form, **every
axiom mentioning a date would get a new identifier**. That is a journal migration, not a rendering
change. EDTF is now worth adopting with a wider break, or not at all.

## D3 — JCS is not applied to numbers outside the IEEE-754 double range

**Clause:** RFC 8785 §3.2.2.3
**What we do:** `Canonical` emits such a number's own lexical form instead of rejecting the input.
**Why:** rejection would make canonicalization partial, and `AxiomId` needs it total. The branch is
unreachable from Noesis's own encoders: axioms contain no numbers at all now that literals are
lexical, and everything else journaled is a sequence number or a value in `[0,1]`.
**Consequence:** a hand-edited journal containing `1e400` canonicalizes to something JCS does not
define. Covered by the `number-too-wide` case in `LogicSuite`.

## D4 — Irregular grandfathered language tags are rejected

**Clause:** RFC 5646 §2.2.8
**What we do:** `LanguageTag.isWellFormed` implements the `langtag` and `privateuse` productions.
The irregular grandfathered tags (`i-klingon`, `en-GB-oed`, `sgn-BE-FR`, …) do not match.
**Why:** a closed, deprecated list of 26 tags, every one with a modern replacement. Encoding it
would be data, not grammar, and it would need maintaining.
**Consequence:** `i-klingon` is not accepted; `tlh` is.

## D5 — Language tags are checked for well-formedness, not validity

**Clause:** RFC 5646 §2.2.9
**What we do:** check the grammar. Do not check subtags against the IANA Language Subtag Registry.
**Why:** the registry changes independently of any release, so validity is a data question with an
update cadence. Treating it as a code question would bake a snapshot into the binary.
**Consequence:** `qq-Zzzz` is well-formed and would be accepted, though no such language exists.

## D6 — Noesis has no blank nodes

**Clause:** RDF 1.1 §3.4; N-Triples §2
**What we do:** `NTriples.parseLine` rejects `_:label` in either position, with a message naming
this entry.
**Why:** SPEC §3.1 reifies events and n-ary relations as individuals with minted IRIs. Every node
Noesis can express is named, so there is nothing for a blank node to map onto — and a stable name
is what lets annotations, learning items and justifications address it.
**Consequence:** N-Triples documents using blank nodes cannot be read. This excludes most of the
W3C `rdf-tests` corpus, which is the main reason F1 below is not simply "vendor it".

## D7 — Only the conjunctive core of SPARQL is implemented

**Clause:** SPARQL 1.1 Query Language §5, §18
**What we do:** `Query.solve` evaluates basic graph patterns over a closure. No `OPTIONAL`, `UNION`,
`FILTER`, property paths, aggregation, or solution modifiers. `PatternSyntax` is Noesis's own
notation, not SPARQL grammar.
**Why:** BGP matching is what entity resolution, distractor lookup and the module queries in SPEC
§7–§8 actually need.
**Consequence:** no SPARQL conformance can be claimed. `PatternSyntax` is separate from `Query`
precisely so a real parser can be placed in front of the same evaluator later.

## D8 — The OWL 2 axiom algebra is a subset

**Clause:** OWL 2 Structural Specification §8–§9
**What we do:** implement the RDFS core plus the role constructs the shipped vocabularies use.
`Triples.toAxiom` is total and maps any unrecognized predicate to an `ObjectAssertion`, so it is not
the OWL 2 RDF mapping.
**Why:** stated in `modules/logic/SPEC.md` §1 — OWL 2 DL is the ceiling, not a claim of completeness.
**Consequence:** the ternary view is an internal query projection, not a serialization. A real
OWL 2 mapping to RDF would belong beside `Turtle` in `noesis-journal` if OWL-level export is ever needed.

## D9 — I-JSON is guaranteed on write, not enforced on read

**Clause:** RFC 7493 §2.1–§2.3
**What we do:** every line Noesis writes is an I-JSON message, and `IjsonConformanceSuite` tests
that. Reading is circe's `decode`, which accepts a line that is not: duplicate member names (keeping
the last), surrogate and noncharacter code points, and integers beyond 2⁵³−1. `noesis-journal`
SPEC §7 scopes its citation to writing for this reason.
**Why:** circe resolves duplicate names before there is a value left to inspect, so enforcement means
a second pass over the raw text of every line, on the path that replays the whole journal at every
cold start. Noesis wrote every line it reads, so the constraint holds by construction for its own
journals.
**Consequence:** a hand-edited or foreign line that is not I-JSON replays without complaint, and
duplicate `seq` keys would silently pick one. Pinned by the `D9` case in `IjsonConformanceSuite`.
`Ijson.check` in this module is the checker that would close it; the cost is moving it into
`noesis-journal` and holding it at that module's 100% mutation score.

## D10 — An unpaired surrogate in a literal is transliterated, not refused

**Clause:** RFC 7493 §2.1; RDF 1.1 Concepts §3.3
**What we do:** `Canonical` escapes only what RFC 8785 §3.2.2.2 requires, so an unpaired surrogate
reaches `String.getBytes(UTF_8)`, which substitutes `?`. Nothing rejects the literal.
**Why:** no capture path produces one today — `Literal` takes whatever string it is given, and the
CLI's input is decoded text.
**Consequence:** two failures, not one. The literal that replays is not the literal that was
asserted, and — because `AxiomId` is a digest of the same bytes — two literals differing only in
which unpaired surrogate they carry are *one axiom*. This is also an RDF 1.1 §3.3 problem: a lexical
form is a Unicode string, and an unpaired surrogate is not a Unicode scalar value, so the literal is
not a legal RDF term to begin with. The fix belongs at `Literal` construction in `noesis-logic`,
not in the serializer. Pinned by the `D10` case in `IjsonConformanceSuite`.

---

## Follow-up work

These are not deviations; they are corpora that should exist and do not yet.

**F1 — Vendor the upstream corpora.** The vectors under `src/test/resources` are derived from the
clauses their `provenance` blocks cite, and are labelled as such — they are not the specifications'
own published test data. Two corpora should be vendored to replace them:
the [JCS test data](https://github.com/cyberphone/json-canonicalization/tree/master/testdata)
(Apache 2.0) and the literal/IRI slices of [W3C `rdf-tests`](https://github.com/w3c/rdf-tests)
(W3C Test Suite Licence). Both need a licence header and an attribution note. `Manifest` and
`NTriples` are shaped so they drop in beside what is here; D6 limits how much of `rdf-tests` is
usable.

**F2 — SPARQL BGP conformance.** Blocked on D7 and on a SPARQL parser; the W3C manifests are Turtle.

**F4 — One instant, two axioms.** `2026-07-30T12:00:00+02:00` and `2026-07-30T10:00:00Z` denote the
same instant. They are different lexical forms, so they canonicalize differently, so they get
different `AxiomId`s, so the journal holds two facts where there is one. This is not a departure
from a cited clause — XSD 1.1 §3.3.8.2 does not normalize offsets, and RDF 1.1 §3.3 makes the
lexical form the term — but it defeats the deduplication `Literal.parse` already performs for
numerals, where reducing `"1.50"` to `"1.5"` at the boundary is exactly what stops one fact typed
twice from becoming two axioms.

The fix that fits the existing design is the same one: **normalize at the capture boundary, not at
identity**. `Literal.parse` and any importer would convert an `xsd:dateTime` carrying an offset to
its UTC form before the axiom is minted, leaving `Canonical` and `AxiomId` untouched. Three things
that decision has to face, and none is fatal:

- It is a Noesis policy above XSD, not conformance to it, so it belongs in `logic` SPEC §2 beside
  the numeral rule rather than in a normative citation.
- It discards the original offset, which is information — the local time of the event. Today that
  information has nowhere to go, because no vocabulary term records where anything happened
  (D11 and the timezone gap are the same gap seen from two sides). If a place model ever lands, the
  offset belongs beside it rather than inside the timestamp, and
  [RFC 9557](https://www.rfc-editor.org/rfc/rfc9557) is the notation for carrying a named zone
  alongside an RFC 3339 instant. **This ordering is a precondition, not a preference:** normalizing
  first would destroy the only copy of the local time. SPEC §12.12 records the wider question.
- It only helps facts captured *after* it ships. Existing journals keep whatever they hold, since
  rewriting them would change identifiers.

Not done here because it is a capture-semantics change rather than a conformance fix, and because
`asInstant` — the one function that would silently absorb an offset today — has no production
caller, so nothing is currently misreading these.

**F3 — An OWL 2 EL profile corpus.** `Profile` now lives in `noesis-logic` with no reasoner
dependency, so a syntactic profile-checker corpus can run against that module alone.

---

## Closed

**Two partial-date shapes have no XSD datatype** (was D1). Closed by making the shapes
unrepresentable rather than by finding datatypes for them. `PartialDate` is now an enum of `Day`,
`Month` and `Year` — a located date always has a year — so `Datatypes.of` is total onto `xsd:date`,
`gYearMonth` and `gYear`, and `core:partialDate` no longer exists. The two shapes it carried were
artefacts of one type doing two jobs: a year with a day but no month was never a calendar date, and
a wholly unknown date was a second spelling of `None`, which every field holding a date already
had. A yearless date is a *recurrence*, not an imprecise date, and is now `java.time.MonthDay` with
`xsd:gMonthDay` — the same lexical form the old type wrote, so no stored birthday moved and no
`AxiomId` changed. Two latent faults went with it: the ordering was unlawful (`--05-12` and `--06-01`
compared equal, so an `Eq` derived from it called two different dates one) and `lowerBound` was an
`Option` at every call site including those that could never see `None`. `Literal.legacyDate` keeps
old journals replayable, and refuses the two dropped shapes by name instead of guessing.

**Stored identifiers are compact names** (was D1, before renumbering). Closed by expanding a bound prefix in `Iri.apply`,
so `Iri("crm:worksAt")` *is* the absolute IRI and no compact name survives construction. Storage is
now RDF 1.1 §3.2-conformant with no boundary left that could forget to expand. Compact names remain
as input notation and as `Iri.display` for messages. Cost paid: every `AxiomId` changed.

**Minted entity IRIs are not legal Turtle compact names** (was D2). Closed by `Turtle`, which
escapes `PN_LOCAL_ESC` characters — `noesis:e\/alice` — and falls back to an absolute IRI in angle
brackets when a local part cannot be spelled as a `PN_LOCAL` at all. Verified in
`TurtleConformanceSuite` against an independently written transcription of the grammar.
