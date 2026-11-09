# GH21 — The Stories-Git Adapter Sets States

**Status:** Analyzed; blocked by [GH20](README.md#gh20).
**Size:** L - writes into the planning repository and into the repositories that hold the issues, needs the stories
index as a library from another repository, and pushes with a token lent per call.

**What must be true.** A state set on Porelo's board - an issue's, or a story's while it has no issue - stands in the
planning repository's files, its stories index and each changed issue's file and index, committed and pushed, all of
it or none, and is refused when anything moved since the board read it.

## Plan

Read in the stories' heads and the issue indexes as [GH20](README.md#gh20) reads them, in `porelo-release`'s
`Stories` (`stories index`, package-private today) and `IssueIndex`, and in the issue rules: an issue's state is its
`**Status:**` line and its row's group in `issues/README.md`, changed in the same commit.

- `SetIssueStates(changes, revision)`: refuse with `StaleRevision` when any clone's head is not the revision's, after
  a fetch; refuse with `StateNotAllowed` when a target state is not in the workflow; then, per repository, change the
  issue's `**Status:**` line, move its row to the target group, regenerate the stories index from the heads, and make
  one commit per repository.
- `SetStoryState(story, state, revision)`: only for a story whose `issues` is empty; otherwise `StateNotAllowed`.
- A commit's message is one line saying what moved, with `[skip ci]`, and carries only Porelo's own trailers.
- The push goes over https with the token from the call on standard input to a credential helper, never in a URL,
  an argument, `.git/config` or a log. A push refused because the remote moved is `StaleRevision`; the local commit is
  dropped, so the clone equals the remote again.
- All or none within one repository is one push. A change touching several repositories pushes them in turn; when a
  later one fails, the earlier ones are undone by a new commit on their tip, and the call fails naming what it
  undid.
- Red first, in the [GH19](README.md#gh19) suite against this adapter: a two-issue change whose second state is
  unknown leaves the first issue's file unchanged.

## Acceptance

- The contract suite's write cases green, each seen red once: half a change pushed, a stale revision taken, the token
  found in `.git/config` or a log after the call.
- In the fixture, after a move, the issue file's `**Status:**` line, its index row's group and the stories index
  agree, and `porelo-release check-citations` accepts the planning repository.

## Open questions

- Setting an issue's state writes in the issue's own repository, not in the planning repository, so a token scoped to
  the planning repository alone cannot do it. Which: one token with contents write on every repository of the project;
  the issue states kept in the planning repository instead, a format change for every repository; or the board sets
  only states of stories without issues, issues' states staying with their agents?

## To be checked

- The stories index as a library: asked of Agent Machines, so the adapter calls the same code `porelo-release stories
  index` runs instead of keeping a copy.
- The planning repository's new working states (`In analysis`, `Under estimation`, `Reworking`, `Finishing`) taken by
  the stories index and `check-citations`.
- Whether git runs as a program on the host the adapter runs on, or the adapter needs a library for it in its native
  image.
