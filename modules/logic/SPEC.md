# Noesis Logic Specification

**Status:** Implemented module contract  
**Authority:** Refines root `SPEC.md` §3.1 and §3.6; the root specification wins on system intent.

## 1. Scope

The logic module defines the values in which Noesis represents semantic claims. Its expressivity is
the implemented RDFS core plus the OWL role constructs required by the shipped vocabularies. OWL 2
DL is the system's ceiling, not a claim that this algebra presently implements all of SROIQ(D).

## 2. Identifiers and literals

- An `Iri` is an opaque string value holding an **absolute IRI**. Compact names are input and
  display notation only: `Iri.apply` expands a bound prefix, so `Iri("crm:worksAt")` *is*
  `https://noesis.librecybernetics.ws/ns/crm#worksAt` and no compact name survives construction.
  Expanding at the single constructor rather than at each boundary is what makes "everything stored
  is an RDF term" an invariant rather than a convention — there is nowhere left to forget.
  `Iri.display` abbreviates again for messages; serializations do their own rendering, because there
  abbreviating is a decision about a grammar rather than about legibility.
- `AxiomId` is the prefix `ax_` followed by the first twelve SHA-256 bytes of the axiom's canonical
  form. **Canonical means RFC 8785**, applied after absent optionals are dropped deeply. This is
  what makes §6.2 enforceable: without a fixed member order, reordering two fields in `Axiom` would
  silently change every identifier in every journal.
- `FluentId` is a minted `fl_<uuid>` identifier.
- A `Literal` is a lexical form, a datatype IRI, and a language tag present exactly when the
  datatype is `rdf:langString`. Storing the lexical form rather than a parsed value is what RDF
  requires and what keeps `xsd:integer` distinct from `xsd:decimal`, `"1.50"` distinct from `"1.5"`,
  and unknown datatypes round-trippable. `Datatypes` supplies the lexical space and canonical
  mapping for each datatype minted; capture canonicalizes numerals so that one fact typed twice does
  not become two axioms.
- Partial dates distinguish absent components from invented values: `2026`, `2026-05` and
  `2026-05-12` are one type, always located in time, and each carries the XSD date datatype its
  precision determines. A value with no year is not an imprecise date but a recurrence — "12 May"
  names a day in every year — so it is `java.time.MonthDay` with `xsd:gMonthDay`, and a located date
  yields the recurrence it is an instance of. Keeping the two apart is what makes every date value's
  datatype an XSD one; Noesis mints none of its own.

## 2.1 Profile checking

`Profile` reports whether an axiom is inside OWL 2 EL. Profile membership is a purely *syntactic*
property — it inspects no graph, closure or justification — so it belongs beside the axiom algebra
rather than in the reasoner, and a profile-checker conformance suite can run against this module
alone. It warns; it never rejects. OWL 2 DL is the ceiling, not EL.

## 3. Axiom algebra

The implemented cases are:

- TBox: `SubClassOf`, `DisjointClasses`
- RBox: `SubPropertyOf`, `InverseProperties`, symmetric/transitive/irreflexive properties,
  property chains with inverse steps, domain, and range
- Temporal schema: `TimeVarying`
- ABox: class, object, and data assertions; same and different individuals

Adding a case requires exhaustive handling in signature extraction, individual extraction,
Manchester rendering, triple projection where applicable, profile checking, serialization tests,
and reasoner behavior.

## 4. Ternary view

`Triple(subject, property, object)` is a query/export view, not the authoritative representation.
`Triples.of` is defined only where one axiom maps faithfully to one triple. `Triples.toAxiom`
reconstructs those supported cases. Non-ternary OWL constructs must remain explicit axioms.

## 5. Annotations and fluents

Annotations store owner overrides, never cascade-resolved values. `Patch` has three explicit states:
leave, clear, and set; their serialized distinction is mandatory.

A fluent represents one continuous value of a time-varying property. An ongoing fluent materializes
as its ordinary assertion, but the fluent remains the journaled semantic value carrying temporal
boundaries and annotations.

## 6. Compatibility invariants

1. Existing canonical JSON must decode without reinterpretation.
2. An unchanged axiom must retain its `AxiomId` across releases.
3. `Patch.Clear` must never collapse into `Patch.Leave`.
4. Missing optional values retain their documented meaning; decoders must not invent defaults that
   alter semantics.
5. Changes to serialized cases or fields require fixtures for both old and new representations and
   an explicit migration strategy in the journal specification.
6. A decoder must keep reading the forms it has already written. `Literal` decodes both the current
   lexical/datatype encoding and the pre-typed-literal sum, told apart by which key is present.

## 7. Normative references

Cited normatively only where Noesis conforms *and* the conformance is tested. Coverage lives in
`modules/conformance`; every known departure is recorded in `modules/conformance/DEVIATIONS.md`.
Conformance is scoped to the constructs this module implements — §1 sets OWL 2 DL as a ceiling, not
a completeness claim.

| Reference | Governs | Scope |
|---|---|---|
| [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) — JSON Canonicalization Scheme | `Canonical`, and therefore `AxiomId` and every journal line | Full, except numbers outside the IEEE-754 double range (D3) |
| [ISO/IEC 21778:2017](https://standards.iso.org/ittf/PubliclyAvailableStandards/) / [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) — JSON | the serialized form JCS is applied to | Full. One syntactic language stated twice; the ISO text is the freely retrievable one |
| [RFC 7493](https://www.rfc-editor.org/rfc/rfc7493) — I-JSON | what `Canonical` emits | What Noesis writes, not what it accepts (D9). An unpaired surrogate is transliterated rather than refused (D10) |
| [FIPS 180-4](https://csrc.nist.gov/pubs/fips/180-4/upd1/final) — SHA-256 | the digest in `AxiomId` | Full |
| [RFC 3987](https://www.rfc-editor.org/rfc/rfc3987) / [RFC 3986](https://www.rfc-editor.org/rfc/rfc3986) — IRI, URI | `Iri.parse`, and the form every stored identifier takes | Scheme syntax and excluded characters |
| [RFC 9562](https://www.rfc-editor.org/rfc/rfc9562) — UUID | `Iri.fresh`, `FluentId.fresh` | Version 4 only. Obsoletes RFC 4122 |
| [RDF 1.1 Concepts](https://www.w3.org/TR/rdf11-concepts/) §3.3 | the shape of `Literal` | Literals, language-tagged strings, and IRIs as terms. No blank nodes (D6). A lexical form is not checked for being a sequence of Unicode scalar values (D10) |
| [XSD 1.1 Part 2](https://www.w3.org/TR/xmlschema11-2/) §3.3 | `Datatypes` lexical spaces and canonical mappings | The datatypes Noesis writes. Lexical spaces include the optional timezone; the date *value* type does not read one (D11), and years stay inside 0001–9999 (D2) |
| [BCP 47](https://www.rfc-editor.org/info/bcp47) — RFC 5646 §2.1 | `LanguageTag` | Well-formedness of `langtag` and `privateuse` (D4, D5) |
| [OWL 2 Profiles](https://www.w3.org/TR/owl2-profiles/) §4 | `Profile` | EL membership of the implemented axiom cases |
| ISO/IEC 11179-5:2015 §2.2.2, §7, §9.2–§9.7 — naming principles (purchased) | the names `Vocab`, `CoreDatatype` and `Namespaces` declare, and the shape `Iri.fresh` mints | The documented convention per namespace; the rules themselves are in `modules/vocab/NAMING.md` and its corpus |
| [OWL 2 Structural Specification](https://www.w3.org/TR/owl2-syntax/) and [Direct Semantics](https://www.w3.org/TR/owl2-direct-semantics/) | what each implemented axiom case *means* | The implemented subset (D8) |

## 8. Informative references

- [OWL 2 Manchester Syntax](https://www.w3.org/TR/owl2-manchester-syntax/) — the shape `Axiom.manchester` renders towards. A W3C Note, and the rendering is approximate; not conformance-tested.
- [OWL 2 Mapping to RDF Graphs](https://www.w3.org/TR/owl2-mapping-to-rdf/) — what `Triples` would have to implement to be a serialization rather than a query view (D8).
- [RDF 1.2 Concepts](https://www.w3.org/TR/rdf12-concepts/) — Candidate Recommendation as of April 2026. Where triple terms land, and therefore the eventual standard footing for the RDF-star axiom identity of root SPEC §3.1. Tracked, not adopted.
- [EDTF](https://www.loc.gov/standards/datetime/) / ISO 8601-2:2019 ⊘ — the notation `PartialDate` should be using. EDTF is maintained by the Library of Congress, freely published, and included in ISO 8601-2 as a profile, so it is the retrievable form of that standard. Its Level 1–2 unspecified-digit syntax covers all eight shapes `PartialDate` can hold with one lexical form, including the two that have no XSD datatype. Not adopted: the migration rewrites every stored date and therefore every `AxiomId` that mentions one. The mapping, and that cost, are recorded beside D1 and D2 in `modules/conformance/DEVIATIONS.md`.
- [ISO/IEC 24707:2018](https://standards.iso.org/ittf/PubliclyAvailableStandards/) — Common Logic. The ISO framework for a family of logic-based languages, and the standards-body alternative to the path §1 takes. Not adopted: Noesis's axiom language is an OWL 2 subset with a decidable profile as its ceiling, while CL is first-order and undecidable, so a CL dialect would describe the language we deliberately did not build. Relevant again only if interchange with a CL-based system is ever wanted.
- [ISO/IEC 11404:2007](https://standards.iso.org/ittf/PubliclyAvailableStandards/) — General-Purpose Datatypes. The ISO datatype vocabulary, not used: RDF literals are typed by XSD 1.1 Part 2, and a second datatype system would have to be mapped onto the first at every boundary.
- [UAX #15](https://www.unicode.org/reports/tr15/) — Unicode normalization. Not applied: `AxiomId` hashes the lexical form as given, so two normalizations of one name yield two identifiers. A candidate deviation once names are captured from more than one source.
