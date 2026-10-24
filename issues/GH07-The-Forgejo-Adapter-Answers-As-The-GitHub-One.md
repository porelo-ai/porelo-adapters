# GH07 — The Forgejo Adapter Answers As The GitHub One

**Status:** Analyzed; blocked by [GH06](README.md#gh06).
**Size:** M - one adapter module beside the `github` one, a shared HTTP module, recordings made from a Forgejo in
podman, and its packages.

**What must be true.** A project whose `builds:` names `forgejo` gets from `porelo build status`, and an `online` task
from its pushes, the same answer in the same shape as from GitHub: the commit, the verdict, the jobs and a failed
job's log, read from Forgejo Actions by the adapter `porelo-adapter-forgejo` at `/usr/libexec/porelo/builds/forgejo`.

## Why

From porelo-project [PJ62](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj62) and
[PJ61](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj61): a task must hear its verdict from
the local forge as it does from the central one.

## Plan

Read in [GH06](README.md#gh06)'s layout and `GitHubReader`, and measured against Forgejo 17.0.0 (`17.0.0+gitea-1.22.0`)
in podman, its `swagger.v1.json`.

1. **The module** `adapters/forgejo` (`porelo-adapter-forgejo`, package `org.fuin.porelo.build.forgejo`), its main
   through `BuildMain`, forge identity `forgejo`, executable `/usr/libexec/porelo/builds/forgejo`. `Http` moves from
   the `github` adapter into a module both use (`adapters/http`, not published), so no adapter keeps a copy.
2. **Not the `github` adapter with another address**, measured: Forgejo has the runs, jobs and logs, but under
   `/api/v1`, and a run carries one `status` where GitHub has `status` and `conclusion`. What it asks:
   - the head: `GET /repos/{owner}/{repo}/branches/{branch}` (`commit.id`);
   - the runs of a commit: `GET /repos/{owner}/{repo}/actions/runs?head_sha=<sha>&limit=50&page=<n>`;
   - a run's jobs: `GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs`;
   - a job's log, plain text: `GET /repos/{owner}/{repo}/actions/jobs/{job_id}/logs` (`format=text`).
   The token as `Authorization: token <token>`. `Target.api` is required: Forgejo has no public default address.
3. **Verdicts** from the one status: `waiting` and `blocked` queued, `running` running, `failure` failure,
   `cancelled` cancelled, `success` and `skipped` success; any other value `unknown`, with the value in `detail`.
   Precedence and jobs as the `github` adapter: failure first, then outstanding, then cancelled.
4. **Refusals**: 401 and 403 `CredentialRefused`, 404 `NoSuchRepository` (an absent branch told apart by asking the
   repository, as the `github` adapter does), 429 `RateLimited` with `Retry-After`, a 3xx `NoSuchRepository`, an
   I/O failure or a body that is not JSON `Unreachable`.
5. **Recordings** for [GH06](README.md#gh06)'s suite, made once from Forgejo 17.0.0 in podman with a
   `forgejo-runner` beside it running a passing and a failing workflow, reduced like the `github` ones; the script
   that makes them is kept beside them, so a new Forgejo version is recorded the same way.
6. **Packages** `porelo-adapter-forgejo` (`.deb`, `.rpm`) built and published by `build.yml` beside the `github`
   one, with its smoke test and index check.

**Red first:** `ForgejoContractTest extends AdapterContract` with no adapter yet; then each case red once with the
adapter broken for it.

## Acceptance

- `porelo-adapter-forgejo` passes every case of the suite from Forgejo's recordings, each seen to fail once.
- Against a Forgejo 17 run in podman, `porelo build status` answers a red and a green run in the shape GitHub's
  answers take: the commit, the verdict, the jobs, the failed job's log.

## Open questions

- A Forgejo run in podman answers `http://` unless it is given a certificate, and `porelo` refuses a `builds.api`
  that is not `https://`. Should `porelo` accept `http://` for a loopback address only (a `porelo` issue), or does
  the test give Forgejo a certificate from a local authority the host trusts (more setup, no change to `porelo`)?

## To be checked

- The exact status values Forgejo 17 writes, read from the recordings; the API documents none.
- Whether a commit whose workflow has not started is answered with no runs or a `waiting` one, and whether a
  repository with no workflow answers the same: if they look alike, `porelo`'s text of `Build2` for `unknown` says
  so.
- Whether `forgejo-runner` runs a workflow in podman without a Docker socket (its `host` label).
