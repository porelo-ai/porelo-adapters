# GH02 — Review Comments Read And Written

**Status:** soon, after `sokar` B171, which it serves.

**What must be true.** The GitHub reader answers `Review1` beside `Build1`: it reads a pull request's review comments,
their threads and suggested changes, and writes comments and replies - each as `sokar`'s comment model (B166) says it,
with its anchor, text, suggestion, thread and origin.

## Why

`sokar` delivers forge review comments into a task and the agent's replies back through the forge adapter (B171). The
adapter for GitHub is this reader; what it already does for builds, it does for reviews.

## The shape

- `Review1` methods: list a pull request's comments and threads since a point, and write one comment or reply. The
  reader translates GitHub's review comments, `suggestion` blocks and resolved state into the model and back.
- Reading uses the read token it uses for builds; writing uses a token of its own with write access to pull requests,
  which `sokar` hands it from the vault for that one call. The reader holds neither between calls.
- Each account is said as GitHub's login, so `sokar` can check it against the project's list.
- `describe` names `Review1` when the reader serves it.

## Acceptance

- Seen to fail first, then green, against recorded GitHub answers: a review with a thread, a reply and a suggestion is
  read into the model exactly; a reply written is the request GitHub documents, with the anchor's commit, path, line and
  side.
- `doc/` says `Review1` and the write token's scope.

## To be checked

- Whether a review's top-level body (not anchored to a line) is a comment of its own or a thread's head.
