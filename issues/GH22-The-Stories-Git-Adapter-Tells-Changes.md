# GH22 — The Stories-Git Adapter Tells Changes

**Status:** Backlog, on the operator's word of 2026-11-09; blocked by [GH20](README.md#gh20).
**Size:** S - one streaming method in the adapter and its contract case.

**What must be true.** A state, story or workflow changed elsewhere - by an agent's push, on another machine - shows on
Porelo's board within a few minutes, or at once when the board asks, without the adapter starting anything.

## Plan

Read in [GH20](README.md#gh20)'s clones and revision and in Planning1's `WatchChanges`, a stream of
`(kind: STORY | ISSUE | WORKFLOW, id, revision)`.

- `WatchChanges` fetches every clone at an interval from the settings, a few minutes by default, and at once on
  `Refresh`; it compares the heads with the last revision it told and replies once per changed story,
  issue or workflow file, with the new revision.
- A fetch that fails is told as an error on the stream naming the repository, never skipped silently; the stream
  stays open and tries again at the next interval.
- It only reads: no write, no commit, no task.
- Red first: [GH19](README.md#gh19)'s watch case, a commit made in the fixture's bare repository behind the adapter's
  back, not reported.

## Acceptance

- The watch case green and seen red once with the comparison broken on purpose.
- A fetch against an unreachable remote appears on the stream as an error naming that remote.
