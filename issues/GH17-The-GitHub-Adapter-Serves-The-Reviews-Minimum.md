# GH17 — The GitHub Adapter Serves The Review's Minimum

```json
{
  "$schema": "issue-1.json",
  "id": "GH17",
  "title": "The GitHub Adapter Serves The Review's Minimum",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 3,
  "blockedBy": ["GH14"],
  "covers": "One person's review on GitHub: `Review1` beside `Build2` with a pull request's facts, one page of comments and one released comment written, a token lent per call and no way to a verdict; waits for porelo's `Review1` description and a two-interface `VarlinkServer`"
}
```

**Why this size.** one module and its tests against one contract change; the native check below measured what could have made it L.

**How it stands.** It waits on porelo's written `Review1` description and profile version `"1"`, and on a
`VarlinkServer` that serves two interfaces.

**What must be true.** One person reviewing a pull request in their own repository on GitHub gets its comments and threads
through `porelo-adapter-github`, and sends one released comment at a time, with a token lent for each call and no way to
reach a verdict.

## Why

The conversion both ways stands in `adapters/github`, but the executable does not reach it: `GitHubReader` serves only `Build2`.
[GH02](README.md#gh02) as a whole also holds edits, batches, retries and moving the build calls, and none of that is needed for one
person's first review. The shared contract suite earns its keep with a second adapter, so its safety cases start here as this
adapter's own tests and move into the suite with [GH10](README.md#gh10).

## The shape

`Review1` beside `Build2` in the same executable, one comment per call, with three methods:

- **The pull request's facts:** head, base, merge base, repository ID and whether it comes from a fork.
- **One page of its reviews, comments and threads:** one profile document, with what was held and how complete the read was.
- **One released result written:** a line, file or general comment or a reply, answering the native IDs and the losses.

The document stays the Jackson tree `ReviewDocument` builds; the adapter does not read through `java-sarif`. The host stamps `kind`,
`level` and `guid` after the filter and names a reply by `replyTo`, so a task sends neither native IDs nor author evidence. The
adapter's write goes from the host, after the filter.

**Stays out, in [GH02](README.md#gh02):** edit and delete, resolve and reopen, batch reviews, retry identity with reconciliation,
moving `Build2`'s calls onto the client, the `java-sarif` binding, capability lists by operation.

## Plan

- Move the paging and completeness logic from `standin/GitHubSource` into `adapters/github`; the stand-in then calls it. The fetch of
  base and head stays the host's.
- Serve `Review1` from `GitHubReader`'s entry beside `Build2`, once `VarlinkServer` takes two interface names.
- Add `WriteOutcomeUnknown`: a timeout after GitHub may have taken the comment is neither success nor refusal, and is never retried by
  itself. The next read shows whether it arrived.
- Keep the native metadata in `src/main/resources-filtered`, since `adapters/github` copies only that folder and ignores a file under
  `src/main/resources` without a word. It is generated, not written by hand: measured at 1,161 lines and 95 reflection entries, 3.6 MB
  more in the executable. Whether it stays here or moves into the client's jar is settled with the client's repository.
- Red first, through the installed executable over recorded answers: a recorded pull request read whole; then the safety rules below.
- `doc/index.md` and `doc/contract.md` name the methods, the losses and the measured token scopes.

- Read in today's code: porelo's main has no `Review1` description and its `VarlinkServer` serves exactly one interface
  (one `interfaceName`, refused by `GetInterfaceDescription` for any other). The `github` client already has `pull`,
  `reviews`, `reviewComments`, `createReviewComment`, `replyReviewComment`, `issueComments`, `createIssueComment` and
  `compare`, and the GraphQL `reviewThreads` and `threadComments`; it sends each request once and marks a write whose
  answer was lost as sent but unanswered.
- `Http.overNetwork` keeps its client that trusts the account's authorities private; the GitHub clients get the same
  trust only once `Http` hands them that `HttpClient`.
- The paging moved from `standin`'s `GitHubSource` carries [GH14](README.md#gh14)'s fix with it, so that issue goes first.
- Red first, unit level over a socket: `GetInfo` names `org.fuin.porelo.Review1` beside `Build2`, and
  `GetInterfaceDescription` answers its description; both fail today. The native executable is built once by the
  workflow's `-Pnative` build; no VM.

**Estimate:** 56 minutes, the measured median of M. **Blocked from outside** by porelo's written `Review1` description and
a `VarlinkServer` that serves two interfaces, both still to be built in porelo.

## Acceptance

Each is seen to fail first, through the executable, on the JVM and native:

- A new client record reached at run time without metadata fails the test over the recordings; its message alone does not name metadata.
- A held item reaches the person named, never dropped silently; bidi and zero-width characters are refused.
- No answer, error or log holds the token.
- No served method reaches a verdict, and `Review1` has no parameter for one.
- A mention in a written body notifies no one.
- A `suggestion` fence in a written text is refused in words.
- A timeout on a write answers `WriteOutcomeUnknown`, with exactly one create call made.
- `Build2`'s contract suite still passes.

## To be checked

- `Review1`'s method names and envelope against the written description, once it exists; the three methods above are the shape asked for.
- That the client's `http.send` does not retry: read in two calls only, to be confirmed with the client's repository.
- That TLS carries over: the native check ran without a socket, and `Build2`'s executable uses TLS already.
- The token scopes for reading and writing, and whether a fine-grained token can write to a repository outside its owner's account:
  from memory, not measured. It does not stop one person in their own repository, and might stop a colleague's organization.
