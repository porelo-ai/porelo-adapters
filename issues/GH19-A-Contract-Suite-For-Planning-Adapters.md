# GH19 — A Contract Suite For Planning Adapters

**Status:** Implementing.
**Size:** L - one new published test module over `org.fuin.porelo.Planning1`, a contract another repository defines,
with its own loopback git server for the credential, concurrency and failure cases; no adapter code.

**What must be true.** Whoever writes a planning adapter - `stories-git` here, or a custom one - proves it answers
`org.fuin.porelo.Planning1` as Porelo expects by extending one published JUnit 5 suite, and an adapter that writes half
a change, takes a stale revision, sets a state the workflow does not have or shows its token fails that suite.

## Plan

Read in today's `contract/` (`AdapterContract`, `JavaExecutable`, `ContractCase`, `CaseExpectation`), which does the
same for `Build2`, and in the interface as agreed for Planning1: `describe` with `protocolVersion`, `name`, `version`,
`capabilities` (`write`, `watch`, `issues`) and `credentials`, the credential kinds it takes (`github-app`, `token`);
`GetWorkflow`, `ListStories`, `GetStory`, `ListStoryIssues`, `GetIssue`,
`SetStoryState` (a story without issues only), `SetIssueStates` (all or none), `WatchChanges`, `Refresh` (a fetch
at once); an issue carries `whatMustBeTrue` and `subAgents`,
each with its brief's text; errors
`StaleRevision`, `NoSuchStory`, `NoSuchIssue`, `StateNotAllowed`, `NotSupported`.

- A grouping module `planning/` beside `adapters/`, its first part `planning/contract`, artifact
  `porelo-planning-contract`, published under `Apache-2.0`, as others build their adapters against it. It depends on
  Porelo's `planning/api` and `wire` only.
- `PlanningContract`, the suite an adapter's test extends: it starts the adapter's main through `JavaExecutable`, as
  the build suite does, over `serve <socket> <directory>`, with a home, a temporary directory and a working directory
  of its own, and a home git configuration and hooks that touch a canary file if anything runs them.
- The store comes from an abstract `Fixture` the adapter's test implements: seed a planning repository and issue
  repositories, change one behind the adapter's back, refuse a credential, stall, redirect to another host.
  `GitHttpFixture`, part of the suite, does it for a git-based adapter: bare repositories served on loopback by git's
  own smart HTTP backend, which checks every request's `Authorization` and records it.
- The cases, each with the harm it prevents:
  - the workflow's states, order and phases are answered as the fixture holds them, a renamed state included;
  - an unknown story or issue is `NoSuchStory` / `NoSuchIssue`, never an empty answer;
  - a capability or credential kind `describe` does not name is refused with `NotSupported` before anything is read
    or written;
  - stale state: a write with an old revision, and a write after a commit pushed behind the adapter's back to the
    repository written and to another of the project, are each refused with `StaleRevision`, every remote and the
    adapter's reads unchanged;
  - all or none: `SetIssueStates` with one change the workflow refuses, with one unknown issue, and with the same
    issue twice changes no remote; `SetStoryState` on a story with issues is `StateNotAllowed`; a valid change over
    two repositories whose second push is refused leaves the first remote as it was, and no read shows the
    unpushed commit;
  - two `SetIssueStates` at the same revision in parallel, beside a `Refresh`: exactly one wins, the other is
    `StaleRevision`, and the remotes hold only the winner's change;
  - credentials: each request carries the credential of its own call - a second call with a token the remote refuses
    fails, never succeeding with the first call's - and `Authorization` never follows a redirect to another host;
  - the token appears in no answer, error, log line, argument list, child environment, nor in any file under the
    directory, the home or the temporary directory, after a push that succeeds and after one refused;
  - containment: a story or issue name with `..` or `/`, and an issue file that is a symbolic link to the canary, are
    refused; nothing outside the directory and the remotes changes, and the canary is never read or created, so no
    host git configuration or hook ran;
  - no hang: a refused credential and a stalled remote end a write, a `Refresh` and a fetch of `WatchChanges` within
    a bounded time, as a refusal or an error on the stream, which stays open;
  - sub-agent briefs: an issue's listed briefs come with `GetIssue`, each with its file's text; a listed brief that
    is missing, a symbolic link or outside the issue's `.sub` directory fails the call naming it; a brief file the
    list does not name is never delivered;
  - `WatchChanges` reports a change made behind the adapter's back, at once on `Refresh`, and while it is open every
    other call is answered.
- The suite owns its questions and answer rules; an adapter supplies only its main class, its fixture and its
  capabilities.
- Red first: the stale-revision case against a stand-in adapter that ignores the revision.

## Acceptance

- `./mvnw -B -s settings.xml -pl planning/contract -am verify` green. A test-only reference adapter over one file
  passes every case its store can serve, and the cases that need a git store are skipped naming the missing ability.
- Each case is seen red once against a stand-in adapter broken for it. The reference adapter's switchable faults
  prove a stale revision taken, half a batch written, the token echoed in a refusal, an unlisted brief delivered and
  a refresh blocked behind a watch. The cases that need a git store (the token left in a file, the first call's token
  reused, a path outside the directory written, a host hook run, a remote waited on without end, a half push over two
  repositories) are seen red against `stories-git` broken for each, in [GH20](README.md#gh20) and
  [GH21](README.md#gh21).
- `porelo-release check-readmes` and `check-releases` accept the module: README present, its pom flattened.

## To be checked

- Planning1's shape as Porelo's `planning/api` defines it once it stands, agreed with Agent Core in the room; the cases
  follow it.
