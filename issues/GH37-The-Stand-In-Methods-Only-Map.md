# GH37 — The Stand-In's Methods Only Map

```json
{
  "$schema": "issue-1.json",
  "id": "GH37",
  "title": "The Stand-In's Methods Only Map",
  "state": "Estimated",
  "size": "S",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The stand-in's Varlink methods only check and map their parameters and call one method of a review service, which holds the comment, resolve and decision use cases and is tested without a socket; built with the stand-in's next change"
}
```

**Why this size.** One module, `standin`: `StandIn` split in two and about thirty of `StandInTest`'s cases moved to the
service; no contract, nothing that ships. The moved tests are most of the work.

**What must be true.** Each of the stand-in's `Tasks1` methods checks its parameters, maps them to a record, calls one
service method and maps the answer back; the service decides what the review does without knowing Varlink, and its
tests call it directly. The split is built with the stand-in's next change, not on its own before.

## Plan

- Read today: `StandIn` (about a thousand lines) registers `Projects`, `WatchProjects`, `Pending`, `Review`,
  `Comments`, `Comment`, `ResolveThread`, `Approve` and `Reject` on `VarlinkServer`, and each handler does the use case
  itself on parameter maps:
  - `Comment` takes the push's lock, answers a repeated `id` with the earlier comment, checks the anchor against the
    blob, sends to the forge through `GitHubReviewWrite` when `toForge` is set (`send`, `forgeParent`) and stores the
    result in the round's run (`store`);
  - `Comments` pages a run by its own cursor (`cursor`, `offset`) and lays the local thread state over it
    (`overlayLocalState`);
  - `ResolveThread` writes `Push.localThreadState`; `Approve` refuses a moved commit with `MovedSinceReview`;
  - `refresh` and `defaultBranch` re-read the pull requests and point the mirror's head at every push's base.
  The refusals are thrown inside the use cases as `VarlinkException`s (`refusal`, `failed`), so the use cases know the
  transport today.
- Move the use cases into a `ReviewService` in `standin`, over `Pushes`, `Push`, `Git` and `GitHubSource`, with one
  record per call's input and answer and a refusal of its own (name and parameters), or `ReviewRefusal` where it is
  one already. `StandIn` keeps `register`, the parameter checks (`required`, `optional`, `flag`, `wholeRound`,
  `invalid`), the mapping of records and refusals to Varlink, and the token guard and log line (`guarded`, `checked`,
  `clean`, `carriesToken`).
- Red first: a `ReviewServiceTest` that comments the same `id` twice and expects one comment and one forge write,
  compiled against the service before it exists. The use-case cases of `StandInTest` (cursors, refusals of a comment,
  repeated ids, resolve and reopen, approve, stacked pushes) move there; `StandInTest` keeps one socket case per
  method, the wrong-type parameter cases, the token cases and the socket directory's mode.
- This repository's adapters need nothing: `GitHubReader` and `ForgejoReader` implement `BuildReader` without
  transport, and the Varlink side of `Build2` is `porelo-build-api`'s `BuildMain`, which only maps.

## Acceptance

- No handler in `StandIn` takes a lock, reads or writes a push's state, or calls the forge; `ReviewServiceTest` covers
  each use case without a socket and goes red when the repeated-`id` check is removed.
- `StandInReplayTest` and the operator's steps for the IDE's review display pass unchanged.

## To be checked

- Whether `oneIdCalledAtOnceFromSeveralConnectionsMakesOneComment` keeps its meaning as a service test with threads, or
  stays a socket test beside a service one.
- The lock order the code names (the forge's lock, then each push's) holds across the service's methods.
