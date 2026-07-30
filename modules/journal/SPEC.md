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

The file backend stores one `JournalEntry` per non-empty line. The profile is:

1. each record is one JSON object conforming to RFC 8259 and restricted to I-JSON (RFC 7493);
2. serialized in the canonical form of `noesis.logic.Canonical` — RFC 8785 applied after absent
   optionals are dropped deeply;
3. written on a single line, terminated by LF, encoded as UTF-8;
4. with no byte-order mark and no whitespace outside string values.

There is no standards-body specification for JSON Lines, so the above *is* the specification. The
IETF alternative, RFC 7464, frames records with a leading RS (0x1E) byte; it is deliberately not
used, because a control byte per record defeats the properties the format was chosen for — a line
that is greppable, diffable in git, and recoverable by hand.

Framing has one implementation, `JsonLines`, shared by the journal and the plainer logs beside it.
The journal adds sequencing and locking on top; it does not have its own idea of what a line is.

Semantic payload codecs come from `noesis-logic`; their discriminator and default-value behavior are
part of the compatibility contract.

Malformed non-empty lines are fatal. Silently skipping a line is forbidden because it would build a
projection from only part of the source of truth.

## 5. Atomicity and durability

The implemented guarantee is atomicity with respect to concurrent calls through one opened journal
instance: a process-local mutex keeps a commit's lines contiguous and assigns its sequence range
together.

The current backend does not promise:

- Coordination between separately opened processes.
- Recovery from a process or machine failure during a multi-line commit.
- Persistence to stable storage before `append` returns.

Those stronger guarantees require a versioned framing or transaction protocol, cross-process
locking, checksums/recovery rules, and an explicit flush policy before the specification can claim
them.

## 6. Compatibility

Readers must replay existing valid journals exactly. A wire-format change requires golden fixtures,
a version discriminator or unambiguous decoder, and a migration that preserves sequence order,
operation meaning, and axiom identities.

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
| [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) / [RFC 7493](https://www.rfc-editor.org/rfc/rfc7493) — JSON, I-JSON | §4.1. I-JSON matters specifically: it forbids duplicate keys, lone surrogates and integers beyond 2^53, each of which would break replay determinism |
| [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) — JCS | §4.2, via `noesis.logic.Canonical` |
| [RFC 3339](https://www.rfc-editor.org/rfc/rfc3339) | `JournalEntry.at`. The open stand-in for ISO 8601, which is paywalled |
| [RDF 1.1 N-Triples](https://www.w3.org/TR/n-triples/) | `NTriples`, reading and writing. No blank nodes (D6) |
| [RDF 1.1 Turtle](https://www.w3.org/TR/turtle/) §6 | `Turtle`, writing only. Prefixed names, IRIREFs, literal syntax and the `@prefix` directive |

## 8. Informative references

- [RFC 7464](https://www.rfc-editor.org/rfc/rfc7464) — JSON Text Sequences. The framing alternative considered and rejected in §4.
- [W3C PROV-O](https://www.w3.org/TR/prov-o/) — maps onto journal entries and the capture provenance of root SPEC §3.5.6; would give root SPEC §10's "every axiom → source" an interchange format.
- [RFC 9162](https://www.rfc-editor.org/rfc/rfc9162) — Certificate Transparency, as a design template for the tamper-evidence and recovery guarantees §5 explicitly does not yet make.
