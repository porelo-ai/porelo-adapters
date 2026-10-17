# GH03 — The Code Read Against The Skills It Names

**Status:** Analyzed.
**Size:** S - one module, `reader` (three classes, ~390 lines, ~250 of tests), built as a native image, read against
five skills; one commit.

**What must be true.** The code of this repository has been read against the skills `AGENTS.md` names, and each
finding is either fixed with a test seen to fail first, or declined with the reason written here.

## Why

`AGENTS.md` has named five skills since the repository was made public, and none of its code was read against them.
The reader holds a forge token and reads answers from another host, which is where such a reading found the most in
the other repositories.

## Plan

**The skills, read against these versions** (from `https://fuinorg.jfrog.io/artifactory/agent-skills/`, each zip
checked against its SHA-256 before it is unpacked; a newer version taken while this is open is named in the commit):

    graal               2026.910.3  b24ec6272fb13490e32977c21d4543869a7e85ed474fdc0ff34022fbddd88bf6
    java-code-review    2026.906.3  802f86339442ed9fa9d5bca14b6d16e1f6420ae92a047b18bba11239e32c698b
    test-quality        2026.906.3  9c5d23ad7a6d9c6f5c3679f3cc44a7fcc2fb3cc1c0f0d0ba4a2c0f7812428bad
    security-audit      2026.906.3  a46fd1be07bd539b955291537dea0f24f65a2f8b15d40d50a6a9098bbd992747
    clean-code          2026.906.3  5cf777a24630298b4b801c934f599722978c21aeac53d61683243908fc041c7b

**What is read:** `reader/src/main` (`GitHubReader`, `Http`, `package-info`) and `reader/src/test`, and its
`pom.xml`; `security-audit` first - the token, where it is sent, what of an answer is believed - then `graal` for the
native build (metadata, class initialization, resources), then the rest. The findings are listed here: fixed (with
the test seen red) or declined (with why). A finding is a guess until a failing test reproduces it.

## Done so far

Read against `graal` 2026.910.3, `java-code-review`, `test-quality`, `security-audit` and `clean-code` 2026.906.3, each
zip checked against the SHA-256 above, and beside them `concurrency-review` and `solid-principles` 2026.906.3
(`solid-principles` checked against `bd47cdab9126dde76cf1703472167a1c88c85306a4e0263a8f2db4c462740cf5`). The fixes are
one commit, `3b17303`; each test below was seen red with its fix undone, then green.

**Fixed, with a test:**

- **A commit's runs were read from the first 50 only**, so a failed run beyond them left a success. Every page is read
  now, 100 a page, up to the 1000 runs GitHub lists of one commit; beyond that the verdict is unknown, saying why.
  `everyPageOfACommitsRunsIsReadSoAFailedRunOnTheSecondIsTheVerdict`, `moreRunsThanGitHubListsAreNoVerdict`.
- **The 50 jobs handed over were the first 50 GitHub listed**, so in a larger matrix the failed job, and its log, could
  be the one left out, and the detail said no single job failed. A failed job is kept before one that did not fail,
  in GitHub's order, and each run's jobs are read page by page.
  `aFailedJobBeyondTheMostJobsHandedOverIsKeptWithItsLog`.
- **A job's log was held whole in memory**, twice over, of which 64 KiB are handed over; a log larger than the heap
  ended the reader on the host and its commit never got a verdict. The log is read through, keeping its last 64 KiB.
  `HttpTest.aLogIsReadThroughKeepingOnlyItsEnd` against a stand-in on 127.0.0.1, and in `GitHubReaderTest` that the
  log and its redirect are both asked for their end alone. The memory bound itself is read from the code, not
  measured.
- **A secondary rate limit, a 403 with `retry-after` or saying so, was reported as a refused credential**, which sends
  a person to replace a token that is right. It is a rate limit now, waited out for as long as GitHub asks, a minute
  where it does not say. `aSecondaryRateLimitIsWaitedOutNeverReportedAsARefusedCredential`.
- **A redirect from the API was taken as an answer**: GitHub answers 301 for a renamed or moved repository, and its
  body read as "no build yet" or "no such branch", forever. It is refused as no such repository, saying the project
  file names an old address, and the token does not follow it.
  `aRedirectFromTheApiIsRefusedNeverReadAsNoBuildOrNoBranch`.
- **An answer that is not JSON** - a proxy's page - **ended the call as a failed reader**, which Porelo reports as its
  own installation at fault; a 403 that is a page lost its refusal the same way. The first is the forge not
  answering, the second still a refused credential. `anAnswerThatIsNotJsonIsAForgeThatDidNotAnswerNeverAFailedReader`.
- **An upstream whose owner or name is `.` or `..`** took the API path, and the token with it, outside the repository.
  It names no repository now, and nothing is asked.
  `anUpstreamWhoseOwnerOrNameIsADotSegmentIsNoRepositoryAndNothingIsAsked`.

**Fixed without a test**, since they name no harm: every assertion in the tests states its reason, and
`NoOldNameTest` runs git with `GIT_CONFIG_GLOBAL=/dev/null` and `GIT_CONFIG_NOSYSTEM=1`.

**Measured in the native image** (`-Pnative package`): no reachability metadata is needed or kept here. The binary,
started by `porelo-build-api`'s own client, serves, finds its interface description, answers a failed build against a
stand-in on 127.0.0.1 with the last 64 KiB of a 16 MiB log fetched without an `Authorization` header, and refuses a
secondary rate limit as one. TLS in the image was not measured: nothing here reaches an https host.

**Declined:**

- **A `x-ratelimit-reset` or `retry-after` too long for a `long`** ends the call as a failed reader. Only an API that
  lies sends one, and such an API can say anything else as well; the failure is loud.
- **A token sent in clear to an `http://` API**: Porelo refuses a `builds.api` that is not `https://` before it starts
  a reader, and a second check here would be the same rule in two forms.
- **A log's redirect may name any host**: nothing but the log's body is read there, and no header is sent.
- **A relative or malformed `Location`** ends the call as a failed reader; GitHub sends an absolute one, and the
  failure is loud.
- **A branch with a `.` component** is not checked: git refuses such names, and Porelo asks only for the branches it
  names itself.
- **A body read has no deadline of its own**, only one for the answer's headers: Porelo bounds every call and ends a
  reader that does not answer.
- **A log the blob host does not serve refuses the whole look as unreachable**, rather than handing the verdict
  without it: the next round asks again, which is right while that host is away.
- **`Http.Response` holds a `byte[]` in a record**, so it compares by identity; nothing compares two answers.
- **`GitHubReader` holds the HTTP, the pages and the verdict together**: it reads one forge, about 500 lines, and a
  split would add seams without a second user.
- **Concurrency**: the reader keeps no state between calls, and the one `HttpClient` is safe to share.

## Acceptance

- The skills read against, their versions and the commit are listed here.
- Every finding is fixed with a test seen to fail without the fix, or declined with the reason written here.
- The reactor, the native build and the shared checks are green on that commit.
