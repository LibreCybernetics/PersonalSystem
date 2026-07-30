# Noesis Reasoner Specification

**Status:** Implemented module contract  
**Authority:** Refines root `SPEC.md` §3.4, §3.3.1, §4.4, and §12.4.

## 1. Inputs and outputs

A `Graph` maps base axioms to the journal-backed `Support` values that make them present. A
`Closure` maps asserted and derived axioms to minimal `Justification` sets and records whether the
configured iteration bound was reached.

The reasoner may return a sound but incomplete closure only when `saturated` is false. Callers must
not silently present such a closure as complete.

## 2. Rules

A rule derives axioms and their justifications from a read-only indexed `ClosureView`. Rules must be
monotone: they may add facts and alternative justifications but never retract or mutate facts.

The built-in rules implement transitive class/property hierarchies, assertion propagation, domain
and range, symmetry, transitivity, inverses, and property chains.

## 3. Justifications

- Within a derivation, premise justifications combine by Cartesian product and premise union.
- Supersets of a smaller justification are discarded.
- Count and size caps may discard alternative explanations but must never remove the fact itself.
- Asserted and fluent-projected support remain distinguishable through every inference step.

These guarantees are load-bearing for privacy disclosure, derived belief, and contradiction UX.

## 4. Consistency and profile checking

Consistency checking currently detects disjoint class membership, simultaneous same/different
individual assertions, and irreflexive self-loops, including violations reached through inference.
Every inconsistency includes conflicting axioms and a journal-backed justification.

EL profile checks are warnings rather than rejections because OWL 2 DL is the system ceiling.

## 5. Query contract

Basic graph patterns run over closure triples, so asserted and inferred facts are queried uniformly.
Patterns join repeated variables by equality. Unsupported or malformed textual patterns fail
explicitly rather than being guessed.

## 6. Replacement engines

A future external reasoner adapter must preserve the public closure, support, explanation,
consistency, and saturation contracts. Returning facts without reconstructable journal-backed
justifications is not compatible, even if entailment answers are otherwise correct.
