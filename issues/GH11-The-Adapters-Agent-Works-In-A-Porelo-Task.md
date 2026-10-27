# GH11 — The Adapters' Agent Works In A Porelo Task

**Status:** Idea.

**What must be true.** porelo-adapters' own agent works in a Porelo task of the project `porelo` on the move's host,
as porelo-project [PJ59](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj59) lays out for
sprint 8, beside porelo-ai.github.io. The task follows this repository through the gate and pushes to the host's
Forgejo, whose build is read through the `forgejo` reader. The agent is reached in the project's room as
`Agent porelo-adapters` or by the nickname `project.yml` gives it (porelo
[B298](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b298)). This issue is taken from start to
Review inside the task, with the forge's build green.

## What moves, and what to check

- **The repository:** `porelo-adapters` alone. `porelo` and `porelo-ai.github.io`, kept by the same agent today, move
  by issues of their own repositories.
- **The task's image:** this repository's `porelo.yml`, Ubuntu by digest with the pinned GraalVM, `shfmt` and `rpm`, for
  `-Pnative,dist`. Its comment still calls it a draft nothing reads; Porelo reads a repository's own image now, so the
  comment changes with the move.
- **Forgejo's build:** `build.yml` builds in the runner and asks for no rented machine; publishing to Artifactory and
  Central is switched on the local forge by a variable present on both forges, as porelo-project
  [PJ60](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj60) asks.
- **The contract suite** reads a forge's recordings, never a live forge from inside the task.
