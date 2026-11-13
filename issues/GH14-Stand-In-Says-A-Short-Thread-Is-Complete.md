# GH14 — The Stand-In Says A Short Thread Is Complete

```json
{
  "$schema": "issue-1.json",
  "id": "GH14",
  "title": "The Stand-In Says A Short Thread Is Complete",
  "state": "Estimated",
  "size": "XS",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "The stand-in counts a run as complete although a review thread's comments were not read to their end"
}
```

**What must be true.** A person looking at the stand-in's run sees it as partial whenever a review thread's comments were
not read to their end. Today `GitHubSource.comments` keeps what it has and reports nothing when GitHub answers a further page
with no node, or when the page limit stops it with more to come, so with every other list finished the run counts as
complete. Traced in the code, not yet reproduced by a test.

**Why this size.** One file of `standin` and one new test class; no contract. **Estimate:** 9 minutes, the measured
median of XS.

## Plan

- `GitHubSource.comments` returns `List<GitHubGraphQl.Comment>` and drops whether the thread's comments ended: it stops
  with `hasNextPage` still true when `threadComments` answers no node, or after `PAGE_LIMIT` (30) pages.
  `GitHubSource.threads` then returns `true` once the thread list itself ended, whatever its comments did.
- `comments` returns the existing `Paged<GitHubGraphQl.Comment>` with `complete = !info.hasNextPage()`, and `threads`
  returns `true` only when the thread list ended and every thread's comments are complete. `GitHubSource.read` already
  passes that into `GitHubReviewRead.Native`, which marks the run `partial`.
- Red first, unit level over recorded GraphQL answers, no socket and no git: a thread whose comment page says more
  follows and whose next page answers no node; and a thread with more than `PAGE_LIMIT` comment pages. Both assert the
  thread read is incomplete and the first pages' comments are kept; both pass today.
- The run is falsely complete only when every review comment the REST list returned was already named by the read
  thread pages; otherwise `GitHubReviewRead` holds the unnamed comment and the run is already `partial`.

## Acceptance

- Both unit tests seen red on today's code and green after the change.

