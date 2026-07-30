# Naming convention register

**Status:** Current-state documentation
**Authority for the rules themselves:**
`modules/conformance/src/test/resources/mdr/naming.json`

ISO/IEC 11179-5:2015 §2.2.2 defines conformance for a *system* rather than for a name: a namespace
conforms when every item in it is named in accordance with a naming convention whose scope,
authority, semantic, syntactic, lexical and uniqueness rules (§9.2–§9.7) are documented. §7 allows
that convention to live in a reference document. This is that document, and this is why the citation
in SPEC §10.1 is normative rather than aspirational — `NamingConformanceSuite` enforces both halves:
that every bound namespace has a convention, and that every declared term obeys it.

## Where the rules live

The six rules per namespace are in the corpus, not here. That is deliberate: a convention stated in
prose and again in a test is two conventions, and the second one drifts. The corpus entry is the
convention; this document explains the choices behind it and nothing else.

## Prescriptive and descriptive

§7 draws a distinction the register depends on. A *prescriptive* convention says how names shall be
formed and expects an authority to enforce it. A *descriptive* one records how names already are,
for items whose naming was never ours to control.

| Namespace | Kind | Authority |
|---|---|---|
| `core:`, `crm:`, `ll:`, `ref:`, `noesis:` | prescriptive | this project |
| `vf:` | descriptive | ValueFlows |
| `rdf:`, `rdfs:`, `xsd:`, `owl:` | descriptive | W3C |

The distinction decides what a failing name *means*. A `crm:` term that breaks its rule is a bug to
fix by renaming. A `vf:` term that breaks its rule is a mistyped import — the name upstream is
correct by definition, so the fix is to the import, never to ValueFlows. Recording the descriptive
conventions is not ceremony: it is what makes that second case fail at all.

## Why the namespaces are split the way they are

`noesis:` is the only namespace with no semantic rule, and that is its point. Minted entity
identifiers are meaningless by design (§9.4 permits a convention to state that names convey no
meaning), so that correcting what an entity *is* never invalidates its identifier. The register
therefore also forbids vocabulary terms there — a class named in the entity namespace would tie the
ontology to identifiers chosen to be arbitrary.

`ref:` is bound but empty. SPEC §9 is not implemented, and settling the convention before the first
term exists is cheaper than renaming afterwards.

`core:` carries the only cross-namespace agreement: `vf:Agent ≡ core:Agent`. That is an equivalence
axiom rather than a shared name, which is what the uniqueness rules mean by "one name per item" — two
namespaces may denote one thing, and the axiom is where that is said.

## What counts as a declared term

The four seams of the module contract: the ontology, the policy book, the item policy book and the
templates. A term that reaches none of them is a Scala `val` and not a registered name — the suite
cannot see it, and neither can the system. Sixteen such terms exist today, mostly SPEC §7.2's Name
model; they become registered names when a module installs them.

## Normative reference

- ISO/IEC 11179-5:2015, *Information technology — Metadata registries (MDR) — Part 5: Naming
  principles*, §2.2.2, §7, §9.2–§9.7. Purchased; not freely retrievable. The rules it requires to be
  documented are reproduced in full in the corpus, so this project's conformance can be checked
  without access to the standard, even though the requirement itself cannot be read without it.
