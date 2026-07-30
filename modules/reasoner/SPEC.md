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

## 4. Consistency

Consistency checking currently detects disjoint class membership, simultaneous same/different
individual assertions, and irreflexive self-loops, including violations reached through inference.
Every inconsistency includes conflicting axioms and a journal-backed justification.

Profile checking is *not* here. EL membership is a syntactic property of an axiom, defined by OWL 2
Profiles §4, and inspects no graph, closure or justification — so `Profile` lives in `noesis-logic`
beside the axiom algebra. It warns rather than rejects, because OWL 2 DL is the system ceiling.

## 5. Query contract

Basic graph patterns run over closure triples, so asserted and inferred facts are queried uniformly.
Patterns join repeated variables by equality. Unsupported or malformed textual patterns fail
explicitly rather than being guessed.

Evaluation and surface syntax are separate. `Query` evaluates the *algebra* — `BasicGraphPattern` —
which is the part a standard covers: SPARQL 1.1 §5 defines BGP matching and §18 the algebra it
evaluates over. `PatternSyntax` is Noesis's own shorthand, with no standard behind it. Keeping them
apart means a real SPARQL parser can be placed in front of the same evaluator without touching
evaluation, and means a SPARQL BGP conformance corpus can drive the evaluator directly rather than
through a private notation.

## 6. Replacement engines

A future external reasoner adapter must preserve the public closure, support, explanation,
consistency, and saturation contracts. Returning facts without reconstructable journal-backed
justifications is not compatible, even if entailment answers are otherwise correct.

## 7. Normative references

This module cites nothing normatively yet. That is a deliberate report of the current state rather
than an oversight: the two candidates each fail the bar that a normative citation must be one we
conform to *and* test.

- **[SPARQL 1.1 Query Language](https://www.w3.org/TR/sparql11-query/) §5, §18** — `Query` implements
  the conjunctive core and nothing else (deviation D9). Conformance would need the W3C suite, whose
  manifests are Turtle, and a SPARQL parser to read them. Recorded as follow-up F2.
- **[OWL 2 Direct Semantics](https://www.w3.org/TR/owl2-direct-semantics/)** — governs what §4's
  consistency check *means*, and is cited normatively by `noesis-logic` for the axiom cases. This
  module's rule set is a sound but incomplete subset, so it inherits that citation rather than
  making its own.

## 8. Informative references

- [SPARQL 1.1 Entailment Regimes](https://www.w3.org/TR/sparql11-entailment/) — the precise account of §5's "patterns run over closure triples": the OWL 2 Direct Semantics regime is exactly "evaluate BGPs against entailed rather than asserted triples", including the finiteness caveats. The reference to adopt if §5 is ever made normative.
- [OWL 2 Conformance](https://www.w3.org/TR/owl2-conformance/) — supplies the vocabulary §1 uses informally for *sound*, *complete*, and what a reasoner may not claim when `saturated` is false.
- [OWL 2 Profiles](https://www.w3.org/TR/owl2-profiles/) §4 — cited normatively by `noesis-logic`, where `Profile` now lives.
- [SWRL](https://www.w3.org/submissions/SWRL/) — the closest published shape for §2's rule abstraction. A Member Submission, not a Recommendation.
- **Justifications have no open standard.** Nothing can be cited for §3. The nearest published account is the Horridge/Parsia/Sattler justification literature and the OWL API's interfaces; §3's guarantees are Noesis's own normative text and are load-bearing for privacy disclosure, derived belief, and contradiction UX.
