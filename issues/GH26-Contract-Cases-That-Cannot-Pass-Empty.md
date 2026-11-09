# GH26 — Contract Cases That Cannot Pass Empty

**Status:** Idea.

**What must be true.** An adapter that extends the published build contract suite cannot pass the run, trigger and
failed-test cases while answering none of what they hold.

- The evidence check of a case compares only the job lists its expectation names: a recorded `/actions/runs/<id>/jobs`
  answer that the expectation leaves out entirely is never compared, so an adapter may drop a whole run's jobs and
  still pass.
- `AdapterContract` asks for non-empty answers in `many-runs`, `log-tail`, `many-jobs` and `shared-logs`, but not in
  `triggers` (distinct `event`s, jobs tied to their runs) or `failed-tests` (named failed tests and a job page); those
  checks run only inside the suite's own tests for GitHub and Forgejo, so another adapter's recordings can leave the
  fields out and pass.
