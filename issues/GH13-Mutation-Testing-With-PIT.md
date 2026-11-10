# GH13 — Mutation Testing With PIT

```json
{
  "$schema": "issue-1.json",
  "id": "GH13",
  "title": "Mutation Testing With PIT",
  "state": "Accepted",
  "size": "-",
  "priority": "Medium",
  "rank": 3,
  "blockedBy": [],
  "covers": "A `pit` profile run by hand, with PIT and its JUnit 5 plugin pinned, and a report sorting the surviving mutants into real gaps and harmless ones"
}
```

**What must be true.** A maintainer can run mutation testing over this repository's Java code by hand and read which
surviving mutants are real gaps in its tests and which are harmless.

## The shape

- A `pit` Maven profile, run by hand, not in CI and with no threshold.
- PIT and its JUnit 5 plugin pinned.
- Run on a VM where tests call real tools.
- The model is the `pit` profile in the root `pom.xml` of `porelo-message-matrix`, on its `main`.

## Acceptance

- A report from a run sorts the survivors into real gaps and harmless ones.
