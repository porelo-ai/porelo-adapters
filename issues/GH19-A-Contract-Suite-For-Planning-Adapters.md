# GH19 — A Contract Suite For Planning Adapters

**Status:** Analyzed.
**Size:** M - one new published test module and its cases over `org.fuin.porelo.Planning1`, a contract another
repository defines; no adapter code.

**What must be true.** Whoever writes a planning adapter - `stories-git` here, or a custom one - proves it answers
`org.fuin.porelo.Planning1` as Porelo expects by extending one published JUnit 5 suite, and an adapter that writes half
a change, takes a stale revision, sets a state the workflow does not have or shows its token fails that suite.

## Plan

Read in today's `contract/` (`AdapterContract`, `JavaExecutable`, `ContractCase`, `CaseExpectation`), which does the
same for `Build2`, and in the interface as agreed for Planning1: `describe` with `protocolVersion`, `name`, `version`,
`capabilities` (`write`, `watch`, `issues`); `GetWorkflow`, `ListStories`, `GetStory`, `ListStoryIssues`, `GetIssue`,
`SetStoryState` (a story without issues only), `SetIssueStates` (all or none), `WatchChanges`; errors
`StaleRevision`, `NoSuchStory`, `NoSuchIssue`, `StateNotAllowed`, `NotSupported`.

- A grouping module `planning/` beside `adapters/`, its first part `planning/contract`, artifact
  `porelo-planning-contract`, published under `Apache-2.0`, as others build their adapters against it. It depends on
  Porelo's `planning/api` and `wire` only.
- `PlanningContract`, the suite an adapter's test extends: it starts the adapter's main through `JavaExecutable`, as
  the build suite does, over `serve <socket>`, and supplies a store through an abstract `Fixture` the adapter's test
  implements (for `stories-git`: bare git repositories it makes in a temporary directory, with
  `GIT_CONFIG_GLOBAL=/dev/null`).
- The cases, each with the harm it prevents:
  - the workflow's states, order and phases are answered as the fixture holds them, a renamed state included;
  - a write against a stale revision is refused with `StaleRevision` and changes nothing;
  - `SetIssueStates` with one change the workflow refuses writes none of the others;
  - an unknown story or issue is `NoSuchStory` / `NoSuchIssue`, never an empty answer;
  - a capability `describe` does not name is refused with `NotSupported`, never done silently;
  - the token a call carries appears in no answer, error, log line, argument list or file the fixture can read
    afterward;
  - `WatchChanges` reports a change made behind the adapter's back, and a read after it shows that change.
- The suite owns its questions and answer rules; an adapter supplies only its main class, its fixture and its
  capabilities.
- Red first: the stale-revision case against a stand-in adapter that ignores the revision.

## Acceptance

- `./mvnw -B -s settings.xml -pl planning/contract -am verify` green, and every case seen red once against a stand-in
  adapter broken for it: half a change written, a stale revision taken, an unknown state set, the token echoed in an
  error.
- `porelo-release check-readmes` and `check-releases` accept the module: README present, its pom flattened.

## To be checked

- Planning1's shape as Porelo's `planning/api` defines it once it stands, agreed with Agent Core in the room; the cases
  follow it.
- How a call carries the adapter's settings and token: per call, as `Build2` does, so the adapter holds no secret.
