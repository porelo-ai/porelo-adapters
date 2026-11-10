# GH18 — Every Run Of A Commit In The Build Answer

```json
{
  "$schema": "issue-1.json",
  "id": "GH18",
  "title": "Every Run Of A Commit In The Build Answer",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 4,
  "blockedBy": [],
  "covers": "Every `Build2.Build` answer of the GitHub and Forgejo readers carries `runs`, one name and result per run of the commit, queued and running too, at most 50 in the forge's order; the contract suite and its recordings hold it; waits for `porelo-build-api`'s `runs`"
}
```

**Why this size.** both readers, the published contract suite and its recordings change, against a new field of
`porelo-build-api`.

**What must be true.** Every `Build2.Build` answer of the GitHub and the Forgejo reader carries `runs`, one entry per
run of the commit with its name and its result, also while the build is queued or running, so Porelo shows one line
per workflow instead of only a verdict over the runs that exist now.

## Plan

The contract it builds against, as `porelo-build-api` will state it:
- `type Run (name: string, result: string)` beside `Job`; `Build(...) -> (verdict, jobs, runs: ?[]Run, detail)`.
- `name` is the workflow or pipeline as the forge spells it. `result` is lower case: the conclusion once finished,
  otherwise the state (`queued`, `waiting`, `in_progress`, ...).
- One entry per run of the commit at every answer; a rerun replaces its run; at most `Build.MOST_RUNS` (50), in the
  forge's order. `Build.runs()` is never null: a reader that names no run leaves it empty, and the wire then omits
  `runs`. `Build`'s constructor makes each text one line and lower-cases a result; it keeps every run the reader
  gives, in its order, a repeated or an empty name included, up to the first 50. Both forges list the newest run
  first, so the newest stand.

Today's code:
- `GitHubReader.build` lists the commit's runs (`/actions/runs?head_sha=`, 100 a page, up to 10 pages). A failed run
  is the verdict even while another still runs, and that answer already carries jobs; queued and running answer
  `new Build(verdict, "")`. GitHub's list holds a run once, at its latest attempt (measured: a run attempted three
  times is one entry with `run_attempt` 3), so a rerun already replaces its run. So does Forgejo's (measured against
  Forgejo 17 in podman: after a rerun through the API the commit's list still has one entry, the same `id`, with new
  `started` and `stopped` times). An outstanding run's `conclusion` is
  `null`, which `lower()` turns into `""`. Two answers are `unknown` after a run list was read: none, and more runs
  than ten pages hold.
- `ForgejoReader.build` lists the runs (50 a page, until `total_count`) and answers the same way, failure first. A
  Forgejo run names its workflow by `workflow_id` (the file, as the job names already show it) and has one `status`,
  already lower case, also while outstanding (the `queued` recording's run is `waiting`). An unknown status is
  answered `unknown` after the list was read.
- Rate limits, a refused credential, a missing repository and an unreachable forge throw `BuildRefused`; no `Build`
  is answered there.

The rule the readers follow: every `queued`, `running`, `success`, `failure` and `cancelled` answer carries `runs`;
every `unknown` answer names no run (an empty list, absent on the wire), also when a run list was read.

Steps:
1. Red first, in `porelo-build-contract`:
   - `CaseExpectation` reads a `runs` array from `expect.json`, each entry with one `nameFrom` pointer (to a run's
     `name` on GitHub, its `workflow_id` on Forgejo) and one `resultFrom` pointer (GitHub: `conclusion` once
     finished, `status` while outstanding; Forgejo: `status`). Its evidence check holds that the entries cover every
     recorded run, in the recorded order, and refuses a fabricated one, as it does for jobs; `CaseExpectationTest`
     gains the fabricated-run cases.
   - `AdapterContract.compare` splits today's early return: `unknown` asserts no `runs`; `queued` and `running`
     assert no jobs and the expected `runs`; the final verdicts assert jobs and `runs`.
   - `runs` is added to the `expect.json` of the eleven build cases per forge that answer a verdict (`queued`,
     `running`, `passed`, `failed`, `cancelled`, `logs-none`, `logs-failure`, `logs-all`, `log-tail`, `many-jobs`,
     `shared-logs`), read from what the forge recorded. `no-build` stays without; the cases that answer no `Build`
     (`head`, `absent-branch`, the refusals, the pull request cases) are untouched.
   - The suite goes red on both readers.
2. `GitHubReader`: `runs(found)` maps each listed run to `Run(name, conclusion == null ? status : conclusion)` in
   GitHub's order, passed into every non-`unknown` answer; `Build` keeps the first 50.
3. `ForgejoReader`: the same from `workflow_id` and `status`.
4. A `many-runs` case of 51 or more runs with distinct names and results, outstanding so that no job lists are asked:
   GitHub on one page, Forgejo on two (50 a page), generated from one recorded run as `many-jobs` is from one
   recorded job (`RecordedForge.generatedBody` gains the kind `workflow-runs` and a `totalCount` for one page of a
   longer list). The expectation lists every recorded run and the contract keeps the first 50, as `many-jobs` does
   for jobs; it is seen red with the expectation's cap removed and with the reader's order reversed.
5. The reader unit tests that compare a whole `Build` (`ForgejoReaderTest.aJobThatNeverExecutedHasNoLog` and the
   like) take `runs`. A changelog entry under `changelog/unreleased/` (`added`), and `doc/` where the answer is
   described.

Files: `contract/.../CaseExpectation.java`, `AdapterContract.java`, `ContractCase.java`, `RecordedForge.java`,
`CaseExpectationTest.java`;
`adapters/github/.../GitHubReader.java` and its tests; `adapters/forgejo/.../ForgejoReader.java` and its tests; the
eleven `expect.json` per forge and the new `many-runs` recordings.

It waits for a `porelo-build-api` snapshot whose `Build` carries `runs`.

Findings of the code's reading declined, each with its reason:
- A GitHub run `completed` without a conclusion, or with a conclusion while outstanding: GitHub gives a conclusion
  exactly when a run is completed, as every recording shows, so the result follows the conclusion's presence.
- An expectation with no run list, a `nameFrom` of the other forge's field, a recorded name `Build` would change, and a
  generated body without a name: each fails a case loudly instead of letting it pass, since `AdapterContract` requires
  recorded runs beside every verdict, the reduced recordings carry only the forge's own name field, and the answer is
  compared with every recorded run.

## Acceptance

- The contract suite asserts `runs` on every recorded case of both forges, a queued and a running one included, and was
  seen red against today's readers.
- A commit with more than 50 runs answers exactly the first 50, in the forge's order.
- Every `unknown` answer carries no `runs`, `no-build` included; seen red with a run in its place.
