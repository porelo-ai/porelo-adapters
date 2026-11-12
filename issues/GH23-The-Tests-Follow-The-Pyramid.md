# GH23 — The Tests Follow The Pyramid

```json
{
  "$schema": "issue-1.json",
  "id": "GH23",
  "title": "The Tests Follow The Pyramid",
  "state": "Analyzed",
  "size": "L",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The tests follow the pyramid: the readers' mapping, the recordings' rules, the log tail, the trust choice, the review writer and the stand-in proven by unit tests over fakes, one narrow integration test each, and the workflows' inline logic in a tested command",
  "openQuestions": 0
}
```

**Why this size.** Six fixes of two M, two S and two XS, each in one module of this repository and its tests, with no
contract another repository reads; the sixth moves shell that every Porelo repository's workflows share, so it reaches
the shared release tool.

**What must be true.** Every flow of the build adapters, their contract suite and the review stand-in is proven by
unit tests over fakes that run in seconds; integration tests show only what a real socket, process, git or TLS adds,
and nothing needs a runner or a VM to see a flow work.

## The audit

Measured on `main` with `./mvnw -B -s settings.xml clean verify`: 416 tests, none skipped, 52 s in the tests and
1:24 min in all. The native build with its packages (`-Pnative,dist verify`) took 2:28 min in an earlier measurement
on a fresh clone.

| Level | Tests | Share | Time | Suites |
|---|---|---|---|---|
| Unit | 270 | 65 % | 2.0 s | `EvidenceFieldsTest` 62, `GitHubReviewWriteTest` 62, `GitHubReviewReadTest` 58, `ForgejoReaderTest` 35, `RunDetailsTest` 13 (GitHub) and 10 (Forgejo), `GitHubReaderTest` 10, `FailedTestsTest` 10, `TrustedCertificatesTest` 10 |
| Integration | 146 | 35 % | 49.7 s | `StandInTest` 46 (8.9 s), `ForgejoContractTest` 26 (15.5 s), `GitHubContractTest` 24 (17.9 s), `CaseExpectationTest` 17, `RecordedForgeTest` 8, `ForgejoRecorderTest` 8 (runs the recorder's Python and shell), `StandInReplayTest` 8 (1.0 s), `HttpsTest` 5 (4.8 s), `HttpTest` 3, `NullMarkedPackagesTest` 1 |
| End-to-end | 0 in Maven | - | runner only | `build.yml`'s native build, `describe` smoke test, package and linkage checks; the publish and index checks |

The unit share is below the 70-80 % the pyramid asks for. Nearly all run time is in three integration suites.
`ForgejoRecorderTest` stays where it is: what it tests is the recording script, which is shell.

### Flows proven only through an integration or end-to-end run

- **The readers' answer mapping**: `GitHubReader#head` (unknown branch or repository, missing commit), `#build` for
  queued, running, cancelled and successful runs and the order of `runs`, `#jobs` with its log policies, and the
  token-free log redirect; `ForgejoReader#openPullRequest`, `#open` (fork filtering, pages) and `#head`. These run
  only through `GitHubContractTest` and `ForgejoContractTest`, each case a child JVM, a socket and an HTTP server.
  `ForgejoReaderTest`'s fake has no `Http#post`, so nothing below the contract suite reaches pull requests.
- **The recordings' evidence rules**: `CaseExpectation#read` and `RecordedForge`'s parsing run only behind
  `RecordedForge`'s started `HttpServer` in `CaseExpectationTest` and `RecordedForgeTest`, though they send no request.
- **The log tail and the trusted authorities**: `Http#tail` is shown by streaming 8 MiB over loopback in `HttpTest`;
  `TrustedCertificates`' trust choice by TLS handshakes in `HttpsTest`, after nine `keytool` processes.
- **The review stand-in**: every `StandIn` call, `Push`, `Submission` and `GitHubSource` runs only over a started
  stand-in with fixtures written by real `git`, in `StandInTest` and `StandInReplayTest`.
- **`GitHubReviewWrite#send`, `ReviewDocument` and `RepositoryPath`** are reached only through the stand-in's suites.
- **The workflows' own logic**: the push range of `check-commits` (`build.yml`), the pull request's origin
  (`shared-rules.yml`), the release channel and snapshot check, and the workflow load check (`workflows.yml`) are inline
  shell or Python, run only on a runner.

### Waits, sleeps and the network where a fake would do

- `StandIn#watchProjects` waits a fixed 5 s between rounds (`WATCH_INTERVAL_MILLIS`), with no clock to inject.
- `StandInTest` loads the 11 fixture pushes through `git` before every one of its 46 tests.
- `TestCertificates` runs `keytool` nine times as processes, each with a 30 s bound.
- `record-forgejo.sh` sleeps fixed times while Forgejo starts; it records by hand and is not a test of the build.

## Plan

One commit per fix, in this order, each with the level of its tests named. A moved flow's new unit test is seen red by
breaking the mapping it guards, and put back from a copy; the integration test it replaces is then cut to the one case
only it can show.

1. **XS, unit** - `contract`: `RecordedForge` splits its parsing of a recording from serving it, and `CaseExpectation`
   and that parsing are tested in-process; `CaseExpectationTest` starts no server, `RecordedForgeTest` keeps only its
   socket and header cases. Red first: a recording with a missing evidence field accepted.
2. **S, unit** - `adapters/http`: `Http#tail` over in-memory streams and `TrustedCertificates`' trust choice against a
   trust manager, with certificates generated in-process once per class instead of `keytool` processes; `HttpsTest`
   keeps one handshake per trust source. Red first: a tail longer than 64 KiB, and a certificate of an untrusted
   authority accepted.
3. **M, unit** - `adapters/github`, `adapters/forgejo`: the readers' mapping as unit tests over the `Http` fake, which
   gains `post` for Forgejo; the contract cases are played against the reader in-process from their recordings, and one
   process-and-socket case per adapter stays in `GitHubContractTest` and `ForgejoContractTest`. Red first: the log
   request sent with the token after the redirect, and a fork's pull request taken as the project's.
4. **XS, unit** - `adapters/github`: `GitHubReviewWrite#send`, `ReviewDocument` and `RepositoryPath` tested where
   they live, over the client fake. Red first: a path leaving the repository written.
5. **M, unit** - `standin`: `git` behind a small interface with an in-memory fake, and an injected clock for the watch
   interval; `StandIn`'s calls, `Push`, `Submission` and `GitHubSource` as unit tests; `StandInTest` keeps one narrow
   real-git case and loads its fixtures once per class. Red first: a push made between two watch rounds never served.
6. **S, unit** - the workflows' inline logic becomes commands with unit tests, so a workflow only calls them. The push
   range and the pull request's origin are the same shell in every Porelo repository, so they move into the shared
   release tool's `check-commits`, which takes the event's fields; the workflow load check and the channel rule go the
   same way where the tool holds a sibling. What stays here is the call.

## Acceptance

- The unit share is at least 70 % by count, and each module's unit tests run in under a minute.
- Every flow above has unit tests over fakes, each seen red; an integration test names what only it can show.
- `GitHubContractTest`, `ForgejoContractTest` and `StandInTest` together take under 15 s.
- No plan of this repository needs a VM or a runner to see a flow work.

## To be checked

- With the shared release tool's agent: whether `check-commits` takes the push and pull request fields, and whether the
  workflow load check and the channel rule belong there; until then fix 6 waits and the other five are built.
