# GH07 — The Forgejo Adapter Answers As The GitHub One

**Status:** Accepted; blocked by [GH06](README.md#gh06).

**What must be true.** A project whose `builds:` names `forgejo` gets from `porelo build status`, and an `online` task
from its pushes, the same answer in the same shape as from GitHub: the commit, the verdict, the jobs and a failed
job's log, read from Forgejo Actions by the adapter `porelo-adapter-forgejo` at `/usr/libexec/porelo/builds/forgejo`.

## Why

From porelo-project [PJ62](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj62) and
[PJ61](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj61): a task must hear its verdict from
the local forge as it does from the central one.

## Acceptance

- The adapter passes [GH06](README.md#gh06)'s suite, its answers recorded once from a Forgejo run in podman.
- Against a Forgejo run in podman, `porelo build status` answers a red and a green run in the shape GitHub's answers
  take.

## To be checked

- Whether Forgejo's Actions API answers the runs, jobs and logs of a commit at all, or only commit statuses, and from
  which version; if it does not, what the adapter reads instead.
- Whether it can tell a commit whose workflows have not started from one with no workflow, and how it reports a
  cancelled job; if it cannot tell them apart, `porelo`'s text of `Build2` for `queued` and `unknown` says what an
  adapter answers then.
- Whether the adapter is the `github` one with another address, which the suite decides.
- Whether a Forgejo on the host's own network needs a certificate authority the host names.
