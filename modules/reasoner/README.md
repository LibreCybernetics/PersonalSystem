# Noesis Reasoner

`noesis-reasoner` is the pure inference, consistency, explanation, and conjunctive-query library.
It consumes semantic values from `noesis-logic` and has no dependency on journal IO, core state,
policies, learning, or vocabulary modules.

Its `Graph` retains journal-backed support for every base axiom. `Reasoner.closure` propagates that
support as minimal justification sets, which are consumed by disclosure filtering, derived belief,
and contradiction reporting. Facts without correct provenance are therefore not a valid result.

Vocabulary modules extend inference through monotone `Rule` values. The current engine is a bounded,
naive forward-chaining implementation; it is an executable MVP contract, not the production ELK or
HermiT integration described by the root design.

See [SPEC.md](SPEC.md) for soundness, saturation, and justification requirements. The root
[SPEC.md](../../SPEC.md) remains authoritative for system intent.

## Commands

```bash
nix develop --command sbt -batch reasoner/compile
nix develop --command sbt -batch "reasoner/testOnly dev.librecybernetics.noesis.reasoner.*"
nix develop --command sbt -batch reasoner/stryker
```
