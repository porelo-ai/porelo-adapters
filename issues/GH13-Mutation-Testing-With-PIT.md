# [GH13](README.md#gh13) — Mutation Testing With PIT

**Status:** Accepted.

**What must be true.** A maintainer can run mutation testing over this repository's Java code by hand and read which
surviving mutants are real gaps in its tests and which are harmless.

## The shape

- A `pit` Maven profile, run by hand, not in CI and with no threshold.
- PIT and its JUnit 5 plugin pinned.
- Run on a VM where tests call real tools.
- The model is the `pit` profile of `porelo-message-matrix`, on its branch `pit-try` until it is pushed, with the report
  `pit-014d86a/report.md` in that repository's handover directory.

## Acceptance

- A report from a run sorts the survivors into real gaps and harmless ones.
