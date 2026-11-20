# GH37 — The Stand-In's Methods Only Map

```json
{
  "$schema": "issue-1.json",
  "id": "GH37",
  "title": "The Stand-In's Methods Only Map",
  "state": "Accepted",
  "size": "S",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The stand-in's Varlink methods only check and map their parameters and call one method of a review service, which holds the comment, resolve and decision use cases and is tested without a socket; built with the stand-in's next change"
}
```

**Why this size.** One module, `standin`: `StandIn` split in two, its tests moved to the service; no contract, nothing
that ships.

**What must be true.** Each of the stand-in's `Tasks1` methods checks its parameters, maps them to a record, calls one
service method and maps the answer back; the service decides what the review does without knowing Varlink, and its
tests call it directly. The split is built with the stand-in's next change, not on its own before.

## Plan

- Read today: `StandIn` (one class of about a thousand lines) registers `Projects`, `WatchProjects`, `Pending`,
  `Review`, `Comments`, `Comment`, `ResolveThread`, `Approve` and `Reject` on `VarlinkServer`, and each handler does
  the use case itself on parameter maps: `Comment` takes the push's lock, answers a repeated `id` with the earlier
  comment, sends to the forge through `GitHubReviewWrite` when `toForge` is set, and stores the result in the round's
  run; `ResolveThread`, `Approve` and `Reject` change the push's state the same way. The token guard, the error
  mapping and the log line (`guarded`, `checked`, `clean`) are transport and stay.
- Move the use cases into a `ReviewService` in `standin`, with records for each call's input and answer, over
  `Pushes`, `Push`, `Git` and the forge source; `StandIn` keeps only `register`, the parameter checks
  (`required`, `optional`, `flag`, `invalid`), the record mapping and the token guard.
- Red first: a `ReviewServiceTest` that sends the same comment `id` twice and expects one comment and one forge
  write, compiled against the new service before it exists. The use-case cases of `StandInTest` move there; the
  socket tests keep one case per method for the mapping.
- This repository's adapters need nothing: `GitHubReader` and `ForgejoReader` implement `BuildReader` without
  transport, and the Varlink side of `Build2` is `porelo-build-api`'s `BuildMain`, which only maps.

## Acceptance

- No handler in `StandIn` takes a lock, reads or writes a push's state, or calls the forge; `ReviewServiceTest` covers
  each use case without a socket and goes red when the repeated-`id` check is removed.
- `StandInReplayTest` and the operator's steps for the IDE's review display pass unchanged.
