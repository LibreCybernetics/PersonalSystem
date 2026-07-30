# Noesis Journal Specification

**Status:** Implemented module contract  
**Authority:** Refines root `SPEC.md` §3.2, §3.5.6, and §10.

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

The file backend stores one `JournalEntry` JSON object per non-empty line using UTF-8. Semantic
payload codecs come from `noesis-logic`; their discriminator and default-value behavior are part of
the compatibility contract.

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
