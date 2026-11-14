# GH11 — The Adapters' Agent Works In A Porelo Task

```json
{
  "$schema": "issue-1.json",
  "id": "GH11",
  "title": "The Adapters' Agent Works In A Porelo Task",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "porelo-adapters' agent works in a Porelo task on the move's host as `Agent Adapters`; first its workflows' publishing, `gh` and artifact upload held to `PORELO_PUBLISH` and the forge's registry read, with a workflow test",
  "openQuestions": 0
}
```

**Why this size.** three workflows and `settings.xml` switched by the forge's variables, with a workflow test, before the
move; then the trial move's steps, this issue worked to Review inside the task, and the way back.

**What must be true.** porelo-adapters' agent works in a Porelo task on the move's host, as the project's procedure for an agent's
move lays out, and takes this issue to
Review inside the task with the host's Forgejo build green. It talks only in the project's room on the host's homeserver, with the operator, as `Agent Adapters`, from
the nickname `Adapters` that porelo-project's `project.yml` is to give the repository `github`.

## Plan

Read in today's `porelo.yml`, `settings.xml` and the four workflows, against the host's forge settings
(`PORELO_PUBLISH` `false`, `PORELO_DEPLOY_PROFILE` `forge-release`, `MAVEN_SNAPSHOTS_URL` and
`PORELO_PACKAGES_READ_TOKEN`, `JF_URL` `https://jfrog.invalid` with a placeholder token).

- **What already holds on a forge, read, not run:**
  - `build.yml` builds in the runner on `ubuntu-24.04` and asks for no rented machine; the pinned GraalVM comes from
    the runner's `JAVA_HOME_25_X64` through `porelo-machines`, as on GitHub.
  - *The pushed commits' messages* and *The reader answers as one* read only the checkout and the built executables.
  - `shared-rules.yml`'s scheduled step reads `main`'s commits from the checkout, never GitHub's API.
  - The contract suite reads recordings, never a live forge; `record-forgejo.sh` is run by hand and is not in a build.
  - This repository deploys no Maven snapshot: nothing on the forge's registry is read from here.
- **What does not hold, to be changed here before the move:**
  - `build.yml`: *Which channel*, *Publish the packages* and *The package is indexed* run on every push to `main` and
    on a tag, so on the forge they upload to `https://jfrog.invalid` with a placeholder token and fail every merge.
    *A release depends only on releases* and *Publish the contract suite to Central* run on a tag. Each runs only
    where `vars.PORELO_PUBLISH` is `true`, and a first step refuses a value that is neither `true` nor `false`.
  - `build.yml`: `actions/upload-artifact` refuses every server but GitHub's; it runs only where `PORELO_PUBLISH` is
    `true`, as the moved agents' builds do.
  - `delete-runs.yml` calls GitHub's API with `gh`; its job runs only where `PORELO_PUBLISH` is `true`.
  - `settings.xml` reads Sonatype's snapshots alone. A profile active when `MAVEN_SNAPSHOTS_URL` is set adds the
    forge's registry with the read token, and the three workflows that build stop at once on a forge that names no
    registry or no token, as porelo-message-sluice's do.
  - `build.yml`: *rpm, to read the built package* keys its cache by `ImageOS` and `ImageVersion`, which only
    GitHub's images set, and stops where they are missing and `rpm` is not installed. Where the runner names neither,
    the key is read from `/etc/os-release`'s `ID` and `VERSION_ID` instead.
  - `porelo.yml`'s comment says nothing reads it; Porelo builds a task's image from it today. The comment is
    corrected; its GraalVM and `shfmt` pins equal the workflows' today.
- **Red first:** a test in `contract`, the module that ships no executable, reads every workflow and fails where a step or job that names
  `secrets.JF_ACCESS_TOKEN`, `secrets.OSS_SONATYPE_TOKEN`, `gh ` or `actions/upload-artifact` is not held to
  `vars.PORELO_PUBLISH == 'true'`. Harm prevented: a token sent to a placeholder host, or a build red on every merge on
  the forge. It is red on today's `build.yml` and `delete-runs.yml`.
- **The move,** in the procedure's order, once the change is on `main`:
  - The repository is named in the host's `forge_repositories`, and the coordinator switches its upstream in
    `project.yml`.
  - `porelo task start --repository github --agent claude` on the host, attended.
  - Inside the task: the five skills fetched and checked, this issue set to Review, the forge's Build and *Shared
    rules check* of the task's pull request read through the `forgejo` reader, both green, and the Build after the
    merge green with nothing published.
  - The reading before the hand-over: a reader on the host, started apart from the task, reads the pull request's
    diff against the repository's skills and posts its findings in the host's room; a fix amends the task's commit.
  - The move's check, red before, green after; then the way back.
- **Not in the task:** the Artifactory and Sonatype credentials, Codex CLI and Antigravity CLI, and the VM; nothing
  runs between the development machine and the task.

## Acceptance

- The workflow test, seen red on today's workflows and green after.
- On the host's forge: the Build and *Shared rules check* of the task's pull request green, and the Build after the
  merge green with its publishing steps skipped; on GitHub, the next Build after the change still publishes and indexes
  both adapters' packages.
- The move's check for this repository red before the start and green after, and this issue in Review in the commit
  the operator merges.

## To be checked

- That the forge's runner image has `rpm`, or names `ImageOS` and `ImageVersion`; the key's fallback is built either way.
- That the forge's runner serves the label `ubuntu-26.04` that the *Workflow check* runs on, and `actions/cache` with a
  cache server; where it does not, the label or the cache is changed with the publishing steps.

- **The repository alone moves.** `porelo` and `porelo-ai.github.io` move by issues of their own repositories.
- That the forge's runner builds both native executables and their packages within the job's 30 minutes; measured
  on GitHub only.
- That `porelo-build-api` and the forge clients at the versions `pom.xml` names are on Sonatype's snapshots as the
  local build reads them, since the task resolves nothing from this machine.
- That the task's egress reaches GitHub's release downloads, which `porelo.yml`'s image needs while it is built.
- With the coordinator: `project.yml` gives the repository `github` the nickname `Adapters`, and its description names
  both forges' build readers.
