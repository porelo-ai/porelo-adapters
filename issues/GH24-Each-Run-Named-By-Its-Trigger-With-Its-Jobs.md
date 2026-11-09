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
so a reader from before still answers; Porelo refuses a reply that breaks a bound:
- `Run` gains `event: ?string`, the forge's trigger in lower case as it spells it (`push`, `pull_request`, ...), and
  `id: ?string`, the forge's run id as text; each one line of printable text, at most 64 characters.
- `Job` gains `run: ?string`, the id of the run it belongs to, which must be one of the reply's `Run.id`.

Today's code:
- `ForgejoReader.build` reads `/actions/runs?head_sha=` and answers `named(runs)` - the run's `workflow_id` and
  `status` - at every answer, outstanding ones included; `jobs(...)` already asks `/actions/runs/<id>/jobs` per run,
  so the run of each job is known and dropped.
- `GitHubReader.build` does the same over `/actions/runs` and `/actions/runs/<id>/jobs`.
- The recordings keep only the fields read today, so they hold no trigger.

Steps:
1. Red first, in the contract suite: a recorded commit built by a push and a pull request run of one workflow is
   answered with two runs that differ in `event`, and every job's `run` names one of their ids; a commit whose runs
   are all queued answers every run, none dropped.
2. Both readers fill `event`, `id` and `run` from the answers they already read; recordings for both forges, Forgejo's
   made with `record-forgejo.sh`.
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
