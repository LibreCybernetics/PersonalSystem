# Noesis Journal

`noesis-journal` owns the append-only operation log that is Noesis's sole source of truth. It defines
the persisted operation protocol, ordered entries and commits, plus in-memory and JSON Lines
implementations.

The journal records operations rather than projected state. Retraction, annotation changes, fluent
boundaries, and supersession are all new entries; no API updates or deletes history. Core state,
current graphs, entailments, learning items, and balances are rebuilt from replay.

## Implementations

- `InMemoryJournal` provides the same ordering/atomic-bundle interface for tests and scratch work.
- `JsonLinesJournal` appends one checksummed, versioned JSON object per atomic commit and fails
  loudly on malformed complete content. It still reads legacy one-operation lines.
- `JsonLines` provides the plain append/read format used by auxiliary logs such as reviews.
- `JournalArchive` captures journal and review bytes under both files' locks and validates immutable
  archived journal bytes without modifying them.

The file backend coordinates separately opened JVM and process writers with process and OS file
locks. It validates sequence continuity while holding the lock, writes one frame for the whole
commit, and fsyncs before returning. A checksum covers the canonical entry list; a non-LF-terminated
final fragment is truncated on open, while corruption in a complete line is fatal. Conditional
append lets semantic validation bind to the exact durable prefix it checked.

Persistence files reject symlinks and are tightened to owner-only permissions on POSIX filesystems.
The review log uses the same locking, fsync and permission boundary, but stays one plain JSON record
per line.

See [SPEC.md](SPEC.md) for the normative format and replay contract. The root
[SPEC.md](../../SPEC.md) remains authoritative for system intent.

## Commands

```bash
nix develop --command sbt -batch journal/compile
nix develop --command sbt -batch "journal/testOnly noesis.journal.*"
nix develop --command sbt -batch journal/stryker
```
