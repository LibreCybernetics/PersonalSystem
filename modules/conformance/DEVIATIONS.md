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

## D1 — Two partial-date shapes have no XSD datatype

**Clause:** XSD 1.1 Part 2 §3.3.9–§3.3.15
**What we do:** a year with a day but no month (`2026---12`) and a wholly unknown date (`unknown`)
carry `core:partialDate`. The other six shapes map onto `xsd:date`, `gYearMonth`, `gYear`,
`gMonthDay`, `gMonth` and `gDay`.
**Why:** XSD has seven date datatypes and neither shape is among them. Inventing a lexical form for
`xsd:date` that is not in its lexical space would be worse than naming the gap.
**Consequence:** a consumer that only understands XSD datatypes cannot interpret these two.

## D2 — Years outside 0001–9999 are not supported

**Clause:** XSD 1.1 Part 2 §3.3.9
**What we do:** `PartialDate.render` pads years to four digits with `%04d`. XSD admits more than
four digits and negative years; `PartialDate.parse` splits on `-` and so cannot read a negative
year at all.
**Why:** no capture path produces one, and supporting them would mean a real date-lexical parser.
**Consequence:** a negative year renders as `-005` rather than the conformant `-0005`.

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

**F3 — An OWL 2 EL profile corpus.** `Profile` now lives in `noesis-logic` with no reasoner
dependency, so a syntactic profile-checker corpus can run against that module alone.

---

## Closed

**Stored identifiers are compact names** (was D1). Closed by expanding a bound prefix in `Iri.apply`,
so `Iri("crm:worksAt")` *is* the absolute IRI and no compact name survives construction. Storage is
now RDF 1.1 §3.2-conformant with no boundary left that could forget to expand. Compact names remain
as input notation and as `Iri.display` for messages. Cost paid: every `AxiomId` changed.

**Minted entity IRIs are not legal Turtle compact names** (was D2). Closed by `Turtle`, which
escapes `PN_LOCAL_ESC` characters — `noesis:e\/alice` — and falls back to an absolute IRI in angle
brackets when a local part cannot be spelled as a `PN_LOCAL` at all. Verified in
`TurtleConformanceSuite` against an independently written transcription of the grammar.
