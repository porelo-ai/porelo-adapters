# GH34 — The Issue Heads Are Checked Every Day

```json
{
  "$schema": "issue-1.json",
  "id": "GH34",
  "title": "The Issue Heads Are Checked Every Day",
  "state": "Implementing",
  "size": "XS",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "The daily *Shared rules check* holds every issue head to its schema and the index to its heads, with `porelo-release`'s `issues check`"
}
```

**What must be true.** An issue head the planner's board would refuse - a field missing, a size absent, a rank of null -
or an index that disagrees with its heads fails this repository's daily *Shared rules check*, named by its file and its
field, instead of waiting to be found by whoever reads the board.

**Why this size.** One step in one workflow, run from the `porelo-release` the root pom already pins, which has `issues check`; no
contract, no code.

## Plan

- `.github/workflows/shared-rules.yml` gains a step *The issue heads* after *The documents*, running
  `./mvnw -B -s settings.xml -N exec:exec@release -Drelease.args="issues check issues"` on the checked-out commit.
- No test goes red first: the step is CI only. Its failure path is seen once locally by breaking one head on purpose
  (a `size` removed), reading the named file and field, and putting the head back from a copy.
- A change to the CI's own workflows only, so no changelog entry.

## Acceptance

- The step runs green on `main`'s issues, locally and in a *Shared rules check* run by hand.
- A head with its `size` removed fails the step, and the output names that file and field.
