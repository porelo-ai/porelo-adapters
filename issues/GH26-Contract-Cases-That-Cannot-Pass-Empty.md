# GH26 — Contract Cases That Cannot Pass Empty

```json
{
  "$schema": "issue-1.json",
  "id": "GH26",
  "title": "Contract Cases That Cannot Pass Empty",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "The published build contract suite refuses an adapter that drops a recorded run's jobs, or answers no trigger or failed test in the cases that hold them"
}
```

**What must be true.** An adapter that extends the published build contract suite cannot pass the run, trigger and
failed-test cases while answering none of what they hold.

- The evidence check of a case compares only the job lists its expectation names: a recorded `/actions/runs/<id>/jobs`
  answer that the expectation leaves out entirely is never compared, so an adapter may drop a whole run's jobs and
  still pass.
- `AdapterContract` asks for non-empty answers in `many-runs`, `log-tail`, `many-jobs` and `shared-logs`, but not in
  `triggers` (distinct `event`s, jobs tied to their runs) or `failed-tests` (named failed tests and a job page); those
  checks run only inside the suite's own tests for GitHub and Forgejo, so another adapter's recordings can leave the
  fields out and pass.

**Why this size.** A change to the published `porelo-build-contract` (`CaseExpectation`, `AdapterContract`), its tests,
`doc/contract.md` and a changelog entry; no other repository builds against it today. **Estimate:** 56 minutes, the
measured median of M.

## Plan

- `CaseExpectation.Evidence.verifyJobs` compares only the job lists `Evidence.job` registered from `expect.json`. It also
  takes every recorded exchange whose path is a run's `/jobs`, requires that list non-empty, and adds it to the lists
  compared, so a recorded run whose jobs the expectation leaves out fails `containsExactlyElementsOf`.
- `AdapterContract` asks, beside the existing boundary checks of `many-runs`, `log-tail`, `many-jobs` and `shared-logs`:
  for `triggers` at least two distinct non-null `event`s and every retained job tied to its run; for `failed-tests` at
  least one failed job with named failed tests and a page link. The forge-specific exact values stay in
  `EvidenceFieldsTest`.
- Red first, unit level in `contract`: a two-run case whose `expect.json` omits the second run's jobs, which
  `CaseExpectation.read` accepts today; and a `triggers` and a `failed-tests` answer with the fields left out, which
  `AdapterContract` accepts today. Then `GitHubContractTest` and `ForgejoContractTest` stay green, in process and over a
  loopback socket.

## Acceptance

- Each of the three cases seen red on today's code and green after; both adapters' contract tests green.

