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
| `core:`, `crm:`, `ll:`, `note:`, `ref:`, `noesis:` | prescriptive | this project |
| `vf:` | descriptive | ValueFlows |
| `foaf:` | descriptive | FOAF Vocabulary Project |
| `rdf:`, `rdfs:`, `xsd:`, `owl:`, `geo:` | descriptive | W3C |

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

`note:` is populated by the implemented notes module; `ref:` remains bound but empty because the
reference model of SPEC §3.7 is not implemented. Settling both conventions before their first term
was cheaper than renaming afterwards, and the distinction that cost most to get wrong remains:
`note:Daily` is the dated page, while the append-only log of §3.2 is the journal. A namespace that
used either word for the other would make both unreadable.

`core:` carries the only cross-namespace agreement: `vf:Agent ≡ core:Agent`. That is an equivalence
axiom rather than a shared name, which is what the uniqueness rules mean by "one name per item" — two
namespaces may denote one thing, and the axiom is where that is said.

`foaf:` is an interchange vocabulary rather than the canonical PRM model. Its convention is
descriptive because the names come from the
[FOAF Vocabulary Specification](https://xmlns.com/foaf/spec/); Noesis maps a deliberately limited
subset without minting local terms in that namespace. The canonical contact model remains in
`crm:`, while vCard is an interchange format and therefore introduces no bound ontology namespace.

## What counts as a declared term

The four seams of the module contract: the ontology, the policy book, the item policy book and the
templates. A term that reaches none of them is a Scala `val` and not a registered name — the suite
cannot see it, and neither can the system. Ten such terms exist today; they become registered names
when a module installs them.

## The part this register does not cover

ISO/IEC 11179-5 governs *names*. It says nothing about definitions, and the terms registered here
have none — no module carries a definition for any term it declares. Two standards govern that gap
and agree with each other: ISO/IEC 11179-4:2004 §4.1 (a definition shall be singular, state what the
concept is rather than only what it is not, be a descriptive phrase, use only common abbreviations,
and embed no other definition) and ISO 704:2022 §6.4–§6.5 (writing intensional definitions, and the
circular, inaccurate and negative definitions to avoid). Both are purchased and neither binds until
`Module` grows a `definitions` seam. Until then a term here is named but not defined, and the
Scaladoc beside its declaration is not a definition in either standard's sense.

## Normative reference

- ISO/IEC 11179-5:2015, *Information technology — Metadata registries (MDR) — Part 5: Naming
  principles*, §2.2.2, §7, §9.2–§9.7. Purchased; not freely retrievable. The rules it requires to be
  documented are reproduced in full in the corpus, so this project's conformance can be checked
  without access to the standard, even though the requirement itself cannot be read without it.
