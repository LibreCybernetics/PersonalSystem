<!--
Delete the product section only if nothing the owner can see changes. "It is only a CLI flag" and
"it is only the error text" are both owner-facing.
-->

## What changed, and why

## Product

- **Story / journey step:** <!-- PRODUCT.md, e.g. US-11, J5.2 -->
- **Friction:** <!-- ledger row removed, added, or unchanged -->
- **Docs updated:** <!-- PRODUCT.md §4 / §5 / §6, UX.md, README.md — or "none needed, because …" -->

## Evidence

<!-- TESTING.md § "Evidence required by change type". Counts and output come from the run, not from
reading the source. -->

- **Suites run and results:**
- **Statement / branch coverage and report:**
- **Mutation score** (affected modules):
- **Launcher transcript** for the journey step, checked against the story's acceptance criteria:

```
```

## Checks

- [ ] `nix flake check` passes
- [ ] `cli/testOnly dev.librecybernetics.noesis.cli.*` passes, including `ProductTraceSuite`
- [ ] New failure paths follow the error rubric in `UX.md` §4
- [ ] New commands appear in a `PRODUCT.md` journey and follow the grammar in `UX.md` §2
- [ ] Documentation and implementation agree in both directions (`AGENTS.md`)

## Findings

<!-- Specification/implementation disagreements found along the way, reported rather than silently
resolved. -->
