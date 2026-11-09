# GH24 — Each Run Named By Its Trigger, With Its Jobs

**Status:** Backlog.
**Size:** M - both readers, the contract suite and its recordings, against two optional fields `porelo-build-api` must
add to `Build2` first.

**What must be true.** When a commit is built by more than one run - a push and a pull request, or two workflows - a
person reading the build sees one line per run naming its workflow and what triggered it, each queued, running or
finished, and every job under the run it belongs to; while any run is outstanding the list of runs is never empty.

## Plan

The contract today (`Build2`): `Run (name, result)` names each run, also while queued or running; `Job (name, result,
log)` names no run, so the five jobs of `build.yml` built once for the push and once for the pull request are listed
twice with nothing telling them apart.

The contract this builds against, agreed with `porelo-build-api`'s owner and added there first, both fields optional
so a reader from before still answers; Porelo keeps an answer to these bounds, so the readers answer within them:
- `Run` gains `event: ?string`, the forge's trigger in lower case as it spells it (`push`, `pull_request`, ...), and
  `id: ?string`, the forge's run id as text; each one line of at most 64 code points, absent when blank.
- `Job` gains `run: ?string`, the id of the run it belongs to; Porelo drops one that names no `Run.id` of the reply.

Today's code:
- `ForgejoReader.build` reads `/actions/runs?head_sha=` and answers `named(runs)` - the run's `workflow_id` and
  `status` - at every answer, outstanding ones included; `jobs(...)` already asks `/actions/runs/<id>/jobs` per run,
  so the run of each job is known and dropped.
- `GitHubReader.build` does the same over `/actions/runs` and `/actions/runs/<id>/jobs`.
- The recordings keep only the fields read today, so they hold no trigger.

Taken from the plan's reading, each with a test seen red first:
- A run's `id` is taken only as a positive integer (`[1-9][0-9]{0,18}` once formatted with `Long.toString`, never a
  JSON double's text); a run without one is not asked for its jobs and answers no `id`. `event` is a string or absent:
  `null`, blank, not a string or not one printable line of at most 64 code points answers none.
- A job's `run` is the run it was listed under, and on GitHub its `run_id` when given, which must be that run; every
  `Build.Job` branch carries `run` and `url`, green and log-less jobs too. Runs are taken once per `id`; GitHub's
  `/jobs` asks `filter=latest`.
- The verdict fails closed (a wrong verdict found in today's code): on GitHub only `success`, `skipped` and `neutral`
  count as green, `cancelled` as cancelled, `failure`, `timed_out` and `startup_failure` as failed, and any other
  conclusion - `action_required`, `stale`, none - answers `unknown` naming it, unless another run failed. On Forgejo a
  failed run decides before a run of a status it does not know.
- Jobs are asked of at most `Build.MOST_RUNS` runs, failed runs first, then in the forge's order, so a commit with
  hundreds of runs makes at most 50 job requests; a job whose run is beyond the runs answered has no `run`.
- The contract suite compares `event`, `id` and `run` against the recording's evidence, each job list's
  `/runs/<id>/jobs` path tying its jobs to their run.

Steps:
1. Red first, in the contract suite: a recorded commit built by two runs of one workflow with different triggers
   (on porelo-ai a push and a schedule run; on Forgejo a push and a pull request) is answered with two runs that differ
   in `event`, and every job's `run` names one of their ids; a commit whose runs are all queued answers every run, none
   dropped; a recorded `action_required` run answers `unknown`.
2. Both readers fill `event`, `id` and `run` from the answers they already read; recordings for both forges, GitHub's
   from porelo-ai's real runs, Forgejo's made with `record-forgejo.sh`.
3. The contract suite's README names the new fields.

## Acceptance

- The contract suite's new cases are seen red against each reader before its change, green after, for GitHub and
  Forgejo.
- A queued commit's answer lists every run with its state; an empty `runs` is never answered while the verdict is
  queued or running.
- `./mvnw -B -s settings.xml -Pnative,dist verify` green.

## To be checked

- That a run's answer names its trigger on both forges, and under which field: measured while recording anew.
- Which reader the host carried when a running build answered no run: one from before `runs`, or this one; and
  whether Porelo's `build status` prints `runs` - measured on a host, asked of Agent Core for the printing.
