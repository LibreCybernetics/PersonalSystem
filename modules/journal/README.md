# Noesis Journal

`noesis-journal` owns the append-only operation log that is Noesis's sole source of truth. It defines
the persisted operation protocol, ordered entries and commits, plus in-memory and JSON Lines
implementations.

The journal records operations rather than projected state. Retraction, annotation changes, fluent
boundaries, and supersession are all new entries; no API updates or deletes history. Core state,
current graphs, entailments, learning items, and balances are rebuilt from replay.

## Implementations

- `InMemoryJournal` provides the same ordering/atomic-bundle interface for tests and scratch work.
- `JsonLinesJournal` appends one JSON object per operation and fails loudly on malformed content.
- `JsonLines` provides the plain append/read format used by auxiliary logs such as reviews.

The current atomicity guarantee is process-local: a mutex keeps concurrent appends through one
journal instance contiguous. Cross-process locking, crash-atomic multi-operation framing, and
`fsync` durability are not currently implemented and must not be implied by callers.

See [SPEC.md](SPEC.md) for the normative format and replay contract. The root
[SPEC.md](../../SPEC.md) remains authoritative for system intent.

## Commands

```bash
nix develop --command sbt -batch journal/compile
nix develop --command sbt -batch "journal/testOnly noesis.journal.*"
nix develop --command sbt -batch journal/stryker
```
