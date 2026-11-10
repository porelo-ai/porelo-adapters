# GH23 — The Tests Follow The Pyramid

**Status:** Accepted.

**What must be true.** Every flow of the build adapters, their contract suite and the review stand-in is proven by
unit tests over fakes that run in seconds; integration tests show only what a real socket, process, git or TLS adds,
and nothing needs a runner or a VM to see a flow work.

## The audit

Measured on `main` with `./mvnw -B -s settings.xml clean verify`: 307 tests, none skipped, 51 s in the tests and
1:14 min in all. Two other builds ran beside it, so the times are high. The native build with its packages
(`-Pnative,dist verify`) took 2:28 min in an earlier measurement on a fresh clone.

| Level | Tests | Share | Time | Suites |
|---|---|---|---|---|
| Unit | 175 | 57 % | 2.2 s | `GitHubReviewReadTest` 58, `GitHubReviewWriteTest` 62, `ForgejoReaderTest` 35, `GitHubReaderTest` 10, `TrustedCertificatesTest` 10 |
| Integration | 132 | 43 % | 48.7 s | `StandInTest` 46 (11.1 s), `ForgejoContractTest` 23 (18.3 s), `GitHubContractTest` 21 (14.2 s), `CaseExpectationTest` 17, `StandInReplayTest` 8 (1.9 s), `RecordedForgeTest` 8, `HttpsTest` 5 (2.4 s), `HttpTest` 3, `NullMarkedPackagesTest` 1 |
| End-to-end | 0 in Maven | - | runner only | `build.yml`'s native build, `describe` smoke test, package and linkage checks; the publish and index checks |

The unit share is below the 70-80 % the pyramid asks for. Nearly all run time is in three integration suites.

### Flows proven only through an integration or end-to-end run

- **The readers' answer mapping**: `GitHubReader#head` (unknown branch or repository, missing commit), `#build` for
  queued, running, cancelled and successful runs and the order of `runs`, `#jobs` with its log policies, and the
  token-free log redirect; `ForgejoReader#openPullRequest`, `#open` (fork filtering, pages) and `#head`. These run
  only through `GitHubContractTest` and `ForgejoContractTest`, each case a child JVM, a socket and an HTTP server.
  `ForgejoReaderTest`'s fake has no `Http#post`, so nothing below the contract suite reaches pull requests.
- **The recordings' evidence rules**: `CaseExpectation#read` and `RecordedForge`'s parsing run only behind a started
  `HttpServer` in `CaseExpectationTest` and `RecordedForgeTest`, though they send no request.
- **The log tail and the trusted authorities**: `Http#tail` is shown by streaming 8 MiB over loopback in `HttpTest`;
  `TrustedCertificates`' trust choice by TLS handshakes in `HttpsTest`, after nine `keytool` processes.
- **The review stand-in**: every `StandIn` call, `Push`, `Submission` and `GitHubSource` runs only over a started
  stand-in with fixtures written by real `git`, in `StandInTest` and `StandInReplayTest`.
- **`GitHubReviewWrite#send`, `ReviewDocument` and `RepositoryPath`** are reached only through the stand-in's suites.
- **The workflows' own logic**: the push range of `check-commits`, the release channel and snapshot check, the pull
  request's origin and the workflow trigger check are inline shell or Python, run only on a runner.

### Waits, sleeps and the network where a fake would do

- `StandIn#watchProjects` waits a fixed 5 s between rounds (`WATCH_INTERVAL_MILLIS`), with no clock to inject.
- `StandInTest` loads the 11 fixture pushes through `git` before every one of its 46 tests.
- `TestCertificates` runs `keytool` nine times as processes, each with a 30 s bound.
- `record-forgejo.sh` sleeps fixed times while Forgejo starts; it records by hand and is not a test of the build.

## Plan

The fixes, each its own issue when it is analyzed:

1. **XS** - Parse recordings without a server: `RecordedForge`'s reading and `CaseExpectation` tested in-process;
   `CaseExpectationTest` starts no `HttpServer`, `RecordedForgeTest` keeps only its socket and header cases.
2. **S** - `Http#tail` over in-memory streams and `TrustedCertificates`' trust choice against a trust manager;
   certificates made in-process or kept as fixtures; one handshake stays in `HttpsTest`.
3. **M** - The readers' mapping as unit tests over the `Http` fake (with `post` for Forgejo), and the contract cases
   played against the reader in-process; one process-and-socket case per adapter stays as the integration test.
4. **XS** - `GitHubReviewWrite#send`, `ReviewDocument` and `RepositoryPath` tested in `adapters/github` itself.
5. **M** - `git` behind a small interface in the stand-in, with an in-memory fake and an injected clock for the watch
   interval; `StandIn`'s calls, `Push`, `Submission` and `GitHubSource` as unit tests; one narrow real-git test stays.
6. **S** - The workflows' inline logic (push range, channel and snapshot rule, pull request origin, trigger check)
   moved into commands with unit tests, so a workflow only calls them.

## Acceptance

- The unit share is at least 70 % by count, and each module's unit tests run in under a minute.
- Every flow above has unit tests over fakes; an integration test names what only it can show.
- No plan of this repository needs a VM or a runner to see a flow work.

## To be checked

- Where the workflows' inline logic moves (fix 6): into this repository's tests, or into the shared release tool.
