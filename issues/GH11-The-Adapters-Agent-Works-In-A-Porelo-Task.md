# GH11 — The Adapters' Agent Works In A Porelo Task

**Status:** Idea.

**What must be true.** porelo-adapters' agent works in a Porelo task on the move's host, as porelo-project
[PJ59](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj59) lays out, and takes this issue to
Review inside the task with the host's Forgejo build green. It talks in the project's room as `Agent Adapters`, from
the nickname `Adapters` that porelo-project's `project.yml` is to give the repository `github` (porelo
[B298](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b298)).

## What moves, and what to check

- **The repository:** `porelo-adapters`, the repository `github` in porelo-project's `project.yml`, alone. `porelo`
  and `porelo-ai.github.io`, kept by the same agent today, move by issues of their own repositories.
- **The task's image:** this repository's `porelo.yml`, Ubuntu by digest with the pinned GraalVM, `shfmt` and `rpm`,
  for `-Pnative,dist`. Its comment still calls it a draft nothing reads; it changes with the move.
- **Forgejo's build:** `build.yml` builds in the runner and asks for no rented machine, but nothing switches its
  publishing today: `publish-snapshots` uploads the packages to Artifactory on every push to `main`, and `release`
  deploys with `-Pcentral-sonatype-release` and uploads on a tag. Before the move both depend on a variable present on
  both forges, as porelo-project
  [PJ60](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj60) asks; this repository has no
  [PJ60](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj60) issue of its own, so that change is part of this one.
- **The contract suite** reads a forge's recordings, never a live forge from inside the task.
