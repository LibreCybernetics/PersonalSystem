# Noesis Logic Specification

**Status:** Implemented module contract  
**Authority:** Refines root `SPEC.md` §3.1 and §3.6; the root specification wins on system intent.

## 1. Scope

The logic module defines the values in which Noesis represents semantic claims. Its expressivity is
the implemented RDFS core plus the OWL role constructs required by the shipped vocabularies. OWL 2
DL is the system's ceiling, not a claim that this algebra presently implements all of SROIQ(D).

## 2. Identifiers and literals

- An `Iri` is an opaque string value. Minted entities use `noesis:e/<uuid>`; vocabulary terms use
  readable prefixed names.
- `AxiomId` is the prefix `ax_` followed by the first twelve SHA-256 bytes of the axiom's canonical
  JSON encoding.
- `FluentId` is a minted `fl_<uuid>` identifier.
- Literals support strings with optional language tags, decimals, booleans, partial dates, and
  instants. Partial dates distinguish absent components from invented values.

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
