# Noesis Journal Specification

**Status:** Implemented module contract  
**Authority:** Refines root `SPEC.md` §3.2, §3.5.6, and §10.

## 0. Scope

The journal module owns the append-only log *and* the serializations that read and write it. Both
directions live together on purpose: a format that can be written but not read back is a format
whose round trip nobody checks, and the two halves share their term syntax (`RdfTerms`) rather than
transcribing it twice and drifting.

- **JSON Lines** (§4) — the journal's own on-disk form, read and written.
- **N-Triples** (`NTriples`) — read and written. Line-based and prefix-free, so a term carries its
  own absolute IRI and parsing needs no context.
- **Turtle** (`Turtle`) — written. This is what root SPEC §10 promises for export, so the output has
  to be a document other tools accept: the `@prefix` block is generated from the bindings it
  abbreviates rather than maintained by hand, and a term is abbreviated only when the abbreviation
  is a legal `PNAME_LN`, falling back to an absolute IRI when it is not.

## 1. Source-of-truth contract

The journal is an append-only sequence of operations. Everything outside it is a discardable
projection. There is no update or delete operation; retraction and correction are themselves
journaled facts about history.

## 2. Entry and commit ordering

- `JournalEntry.seq` is the ordering authority and starts at one.
- Sequence numbers are contiguous within one journal.
- Timestamps are informational and must not be used to order replay.
- A commit preserves the input operation order.
- `lastSeq` is zero for an empty journal and otherwise the greatest assigned sequence.

## 3. Operation protocol

The persisted operations are assertion, retraction, annotation patch, sensitivity
reclassification, dispute/undispute, fluent open/close, and atomic fluent supersession. Every new
operation must be handled by core state replay and event reconstruction before it can be written.

## 4. JSON Lines format

New writes store one atomic `JournalFrame` per non-empty line:

```text
{ "formatVersion": 1, "entries": [...], "checksum": "<sha256>" }
```

`entries` is a non-empty, ordered commit bundle. `checksum` is lowercase hexadecimal SHA-256 over
the canonical JSON encoding of `entries`. The reader also accepts the legacy format of one
`JournalEntry` per line; this branch is unambiguous because a frame has `formatVersion`.

Both forms use this profile:

1. each record is one JSON object conforming to RFC 8259 and restricted to I-JSON (RFC 7493);
2. serialized in the canonical form of `noesis.logic.Canonical` — RFC 8785 applied after absent
   optionals are dropped deeply;
3. written on a single line, terminated by LF, encoded as UTF-8;
4. with no byte-order mark and no whitespace outside string values.

There is no standards-body specification for JSON Lines, so the above *is* the specification. The
IETF alternative, RFC 7464, frames records with a leading RS (0x1E) byte; it is deliberately not
used, because a control byte per record defeats the properties the format was chosen for — a line
that is greppable, diffable in git, and recoverable by hand.

JSON-record framing has one implementation, `JsonLines`, shared by the journal and the plainer logs
beside it. `JournalFrame` adds commit framing, sequencing and checksums; the review log remains one
plain `Review` per line.

Semantic payload codecs come from `noesis-logic`; their discriminator and default-value behavior are
part of the compatibility contract.

Malformed complete non-empty lines are fatal. Silently skipping a line is forbidden because it
would build a projection from only part of the source of truth. A non-LF-terminated final journal
fragment is the one recoverable case: opening truncates it to the last LF before replay, because it
cannot be a complete accepted frame. The review log has no equivalent recovery rule.

## 5. Atomicity, concurrency and durability

- A commit is one frame, so no accepted prefix can contain only part of its operations.
- A JVM path lock and an exclusive OS file lock serialize separately opened handles and processes.
- Sequence validation and number assignment occur under that lock. Sequence starts at one and must
  remain contiguous.
- `appendIfCurrent` writes only if the durable final sequence still equals the caller's expected
  prefix. A stale semantic pre-flight therefore retries instead of appending against changed facts.
- The assertion operation's stored identifier must equal the content-derived identifier of its
  axiom.
- The frame is flushed with `FileChannel.force(true)` before append returns.
- A complete frame with a bad checksum, unsupported version, invalid sequence or invalid payload is
  fatal.

Opening creates missing paths and tightens POSIX permissions to `0700` on the containing directory
and `0600` on the file. Persistence paths must be regular files and may not be symlinks. Replacing
or deleting a file after a journal handle opens causes that handle to fail closed.

`JournalArchive.capture` acquires the journal and review locks in canonical path order and copies
both raw logs before releasing either. This gives archive assembly one coordinated two-log
snapshot. Archive manifest and restore semantics belong to the CLI rather than this module.

The guarantee does not include authenticated tamper evidence, encryption at rest, remote or
distributed filesystems whose locks/fsync do not honor local filesystem semantics, or crash-tail
recovery for the plain review log.

## 6. Compatibility

Readers must replay existing valid journals exactly. Version-1 commit-frame readers retain the
legacy one-entry decoder, so an existing valid journal can be appended to without a rewrite; old
lines and new frames may coexist. A future wire-format change requires golden fixtures, a version
discriminator or unambiguous decoder, and a migration that preserves sequence order, operation
meaning, and axiom identities.

Two changes at `0.1.0-SNAPSHOT` exercised this, and only one of them kept axiom identities.

**Typed literals kept them.** Literals moved from a circe sum with a `type` discriminator to a
lexical/datatype pair; the decoder tells the two apart by which key is present rather than by a
version field, so existing journals replay unchanged.

**Two changes broke them.** Adopting RFC 8785 reordered canonical members, and expanding compact
names into absolute IRIs changed what those members contain — each changes every `AxiomId`. Neither
is recoverable by a decoder, because the identifier *is* the content. Both were taken deliberately
before any released journal existed to migrate, and the migration for the second is the constructor
itself: a journal written when compact names were stored decodes through `Iri.apply`, so its
identifiers expand on the way in, and its axioms acquire the identifiers they would have had.

The guarantee in §6 of the logic specification — that an unchanged axiom keeps its `AxiomId` across
releases — therefore takes effect at the first tagged release, not from the start of this branch.
Saying so is the point: a compatibility promise the history disproves is worth less than none.

## 7. Normative references

Cited normatively only where Noesis conforms *and* the conformance is tested; departures are in
`modules/conformance/DEVIATIONS.md`.

| Reference | Governs |
|---|---|
| [ISO/IEC 21778:2017](https://standards.iso.org/ittf/PubliclyAvailableStandards/) / [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) — JSON | §4.1. The two are intended to define one syntactic language; the ISO text is cited because it is the freely retrievable one. Scope: what the reader accepts, tested against the reader that replays the journal |
| [RFC 7493](https://www.rfc-editor.org/rfc/rfc7493) — I-JSON | §4.1. I-JSON matters specifically: it forbids duplicate keys, lone surrogates and integers beyond 2^53, each of which would break replay determinism. Scope: **writing** — every line Noesis emits is an I-JSON message. Reading is not restricted to I-JSON (D9), and an unpaired surrogate is substituted rather than refused (D10) |
| [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) — JCS | §4.2, via `noesis.logic.Canonical` |
| [RFC 3339](https://www.rfc-editor.org/rfc/rfc3339) | `JournalEntry.at`. RFC 3339 is a profile of ISO 8601-1 and is what is implemented; the wider standard is not held, so it is not cited |
| [RDF 1.1 N-Triples](https://www.w3.org/TR/n-triples/) | `NTriples`, reading and writing. No blank nodes (D6) |
| [RDF 1.1 Turtle](https://www.w3.org/TR/turtle/) §6 | `Turtle`, writing only. Prefixed names, IRIREFs, literal syntax and the `@prefix` directive |

## 8. Informative references

- [RFC 7464](https://www.rfc-editor.org/rfc/rfc7464) — JSON Text Sequences. The framing alternative considered and rejected in §4.
- [W3C PROV-O](https://www.w3.org/TR/prov-o/) — maps onto journal entries and the capture provenance of root SPEC §3.5.6; would give root SPEC §10's "every axiom → source" an interchange format.
- [RFC 9162](https://www.rfc-editor.org/rfc/rfc9162) — Certificate Transparency, as a design template for the tamper-evidence and recovery guarantees §5 explicitly does not yet make.
