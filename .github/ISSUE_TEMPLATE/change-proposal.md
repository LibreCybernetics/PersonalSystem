---
name: Change proposal
about: Propose a change to what Noesis does, before proposing how
title: "Proposal: "
labels: proposal
---

## Who it is for, and what they are trying to get

<!-- One of the roles in PRODUCT.md §1.1 (Capturer, Learner, Curator, Auditor, Exiter) or a
non-human actor from §1.2, and one of the outcomes in §2. If it serves none of them, say so — that
is a real answer, and usually means the proposal belongs in §3's non-goals instead. -->

## The story

As the <role>, when <situation>, I want <capability>, so that <outcome>.

## Acceptance criteria

<!-- Commands and observable output, in the shape PRODUCT.md §5 uses. These become the launcher
transcript TESTING.md requires, so write them as something a person can actually run. -->

```
Given
When
Then
And
```

## What it changes

- **Journey step(s):** <!-- PRODUCT.md §4, e.g. J3.2 — or "new journey" -->
- **Friction removed or added:** <!-- ledger id from §6, or a new row -->
- **Spec sections:** <!-- SPEC.md sections this depends on or contradicts -->

## Gates

<!-- PRODUCT.md §7, answered honestly. -->

- **Principle gate:** does it weaken any `DESIGN.md` implementation invariant? If yes, it is
  rejected as written — say what a version that does not would look like.
- **Reachability:** is this exposing something already built and tested, or building something new?
- **Unrecoverability:** does it sit on J1 or J8?
- **Evidence:** what does `TESTING.md` require for this change type?

## Alternatives considered

<!-- Including doing nothing, and accepting the friction with a stated reason. Accepted frictions
are legitimate outcomes; F11, F12 and F13 are three of them. -->
