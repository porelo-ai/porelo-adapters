# GH02 — Review Comments Read And Written

**Status:** Analyzed; blocked by sokar [B171](https://github.com/sokar-ai/sokar/blob/main/issues/base/README.md#b171).
**Size:** M - one module (`reader`), but it implements a contract `sokar` defines (`Review1` beside `BuildReader`), with
recorded GitHub answers and the write token's scope in `doc/`.

**What must be true.** The GitHub reader answers `Review1` beside `Build1`: it reads a pull request's review comments,
their threads and suggested changes, and writes comments and replies - each as `sokar`'s comment model ([B166](https://github.com/sokar-ai/sokar/blob/main/issues/base/README.md#b166)) says it,
with its anchor, text, suggestion, thread and origin.

## Why

`sokar` delivers forge review comments into a task and the agent's replies back through the forge adapter ([B171](https://github.com/sokar-ai/sokar/blob/main/issues/base/README.md#b171)). The
adapter for GitHub is this reader; what it already does for builds, it does for reviews.

## The shape

- `Review1` methods: list a pull request's comments and threads since a point, and write one comment or reply. The
  reader translates GitHub's review comments, `suggestion` blocks and resolved state into the model and back.
- Reading uses the read token it uses for builds; writing uses a token of its own with write access to pull requests,
  which `sokar` hands it from the vault for that one call. The reader holds neither between calls.
- Each account is said as GitHub's login, so `sokar` can check it against the project's list.
- `describe` names `Review1` when the reader serves it.

## Plan

- `sokar` [B171](https://github.com/sokar-ai/sokar/blob/main/issues/base/README.md#b171) adds the contract to `builds/api` beside `BuildReader`: a `ReviewReader` with list-since and write
  methods and the comment model's record. This repository takes that version of the API.
- `reader/.../GitHubReader.java` implements it: `GET /repos/{owner}/{repo}/pulls/{n}/comments` and `.../reviews`
  for comments, threads and `suggestion` blocks; `POST .../pulls/{n}/comments` (a new comment, with `commit_id`,
  `path`, `line`, `side`) and `.../comments/{id}/replies`, through `Http`, with the write token handed per call.
- `describe` names `Review1` once it is served.
- Red first: recorded answers in the module's tests - a review with a thread, a reply and a suggestion read into the
  model exactly; a reply written as the request GitHub documents.
- `doc/` says `Review1` and the write token's scope (pull requests: write).

## Acceptance

- Seen to fail first, then green, against recorded GitHub answers: a review with a thread, a reply and a suggestion is
  read into the model exactly; a reply written is the request GitHub documents, with the anchor's commit, path, line and
  side.
- `doc/` says `Review1` and the write token's scope.

## To be checked

- Whether a review's top-level body (not anchored to a line) is a comment of its own or a thread's head.
