# Noesis Logic

`noesis-logic` defines the persisted semantic language shared by every other Noesis module. It owns
identifiers, typed literals, the supported OWL-style axiom algebra, annotations, temporal fluents,
and the partial subject–predicate–object view used by queries and exports.

The module deliberately contains no journal IO, inference engine, policy resolution, or application
service. `journal` persists values from this language; `reasoner` derives consequences from them;
`core` applies Noesis lifecycle and privacy policy.

## Public surface

- `Iri`, `AxiomId`, `FluentId`
- `Literal`, `PartialDate`
- `Axiom`, `ChainStep`
- `Node`, `Triple`, `Triples`
- `AxiomAnnotations`, `AnnotationPatch`, `Patch`, `Sensitivity`
- `Fluent`, `EndReason`
- `Vocab`

Only class assertions, object/data assertions, subclass axioms, and subproperty axioms have a
faithful single-triple representation. `Triples.of` returns `None` for constructs such as property
chains and disjointness rather than flattening them misleadingly.

## Compatibility

Journal compatibility makes the Circe representation part of this module's public contract.
`AxiomId.of` hashes canonical JSON; changing field order, discriminator configuration, default
encoding, or case names can therefore change identities even when Scala values look equivalent.
Update the golden tests and provide a journal migration before making such a change.

See [SPEC.md](SPEC.md) for the normative language and compatibility contract. The root
[SPEC.md](../../SPEC.md) remains authoritative for system intent.

## Commands

```bash
nix develop --command sbt -batch logic/compile
nix develop --command sbt -batch "logic/testOnly noesis.logic.*"
nix develop --command sbt -batch logic/stryker
```
