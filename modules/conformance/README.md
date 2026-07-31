# noesis-conformance

Conformance to the normative references, as distinct from conformance to our own intentions.

The module suites in `logic`, `journal`, `reasoner`, `core`, `lms` and `vocab` answer *does the
implementation do what we meant*. These suites answer *does what we meant match the specification*.
The two fail differently and are gated differently.

## Layout

```
src/test/resources/          corpora, one directory per specification
  jcs/canonicalization.json    RFC 8785      §3.2.2–3.2.3   30 vectors
  json/syntax.json             ISO/IEC 21778 §2, §4–9       61 vectors
  json/ijson.json              RFC 7493      §2.1–2.3       36 vectors
  xsd/datatypes.json           XSD 1.1 Pt 2  §3.3           53 vectors
  iri/syntax.json              RFC 3987      §2.2           26 vectors
  bcp47/tags.json              RFC 5646      §2.1           31 vectors
  mdr/naming.json              ISO/IEC 11179-5 §2.2.2, §9  10 namespaces
  ntriples/{positive,negative}.nt  RDF 1.1 N-Triples §2, §6
src/test/scala/noesis/conformance/
  Manifest.scala             corpus loading; every case carries the clause it comes from
  Ijson.scala                an I-JSON checker, written against the clauses rather than the writer
  Naming.scala               what the shipped vocabularies declare, and in which role
  ConformanceSuite.scala     the runners
DEVIATIONS.md                the implementation report
```

Vectors are **derived from the clauses their `provenance` blocks cite** — they are not the
specifications' own published test data. Vendoring the upstream corpora is recorded as F1 in
`DEVIATIONS.md`.

`mdr/naming.json` is the exception in shape: its cases are not inputs to check but the naming
conventions ISO/IEC 11179-5 §2.2.2 requires a conforming system to document, and the subject under
test is the shipped vocabulary itself. It is also where a purchased standard's requirements are
reproduced in full, so that conformance stays checkable by a reader without a copy.

## Running

```bash
nix develop --command sbt -batch "conformance/testOnly noesis.conformance.*"
```

## Why this is not in the mutation matrix

`.github/workflows/mutation.yml` holds six modules at a 100% mutation score. This module is
deliberately absent from that matrix, for two reasons that point the same way.

Putting external corpora inside a gated module would **inflate its coverage**. Broad conformance
cases kill mutants incidentally, so a 100% score would stop meaning "the unit suite pins this
behavior" — you could delete a precise boundary assertion and CI would stay green. That erosion is
silent and not recoverable once it starts.

And the infrastructure here — manifest loading, the I-JSON checker — is **test scaffolding with a
large mutation surface and no product contract**. Holding a scanner at 100% is expensive and proves
nothing about the product. It is also deliberately a *second* implementation: `Ijson` walks the raw
text because the constraints it checks are invisible after parsing, and because a conformance claim
verified by the code that makes it is worth little.

The gate here is `DEVIATIONS.md` instead: a failing case must become a recorded, justified deviation
or be fixed. It must never become a skip.

## Adding a vector

Add it to the corpus, not to a suite. If it passes, that is the whole change. If it fails, either
fix the implementation or add a `DEVIATIONS.md` entry citing the clause and saying what Noesis does
instead — and reference that entry's identifier in the case's `id` where it helps.
