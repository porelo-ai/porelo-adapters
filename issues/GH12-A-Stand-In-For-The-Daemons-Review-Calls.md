# [GH12](README.md#gh12) — A Stand-In For The Daemon's Review Calls

**Status:** Implementing.
**Size:** L - a varlink server of seven calls, a bare clone the review fetch reads, fixture pushes, and the GitHub to
SARIF conversion in both directions it takes from [GH02](README.md#gh02); several days, and it builds against two other
repositories' unpublished work.

**What must be true.** An IDE's review display can be tested against a real GitHub pull request before Porelo's daemon
serves its review calls: a test-only tool, never shipped, serves on a unix socket the daemon's `Tasks1` calls that the
review display uses - the waiting pushes and a push's review with their level, class and per-push counts, a run's
comments as Porelo's SARIF review profile, a new comment or reply, and resolving or reopening a thread - reading and
writing that pull request through the forge clients, converting GitHub and SARIF in both directions. What only the
daemon decides - source level, risk class, completeness, holding and release - it answers with fixed values, each
plainly marked as a stand-in's. It is deleted once the daemon serves those calls.

## Why

The review display's calls are specified before the daemon implements them; testing the display against a real
forge then finds what the contract and the conversion miss early, on the GitHub side that the GitHub adapter's review
work needs anyway.

## Plan

- **What it serves**, on `org.fuin.porelo.Tasks1` over a unix socket through `porelo-wire`'s `VarlinkServer`, in the
  shapes the daemon's gate interface has today and its review additions are drafted in: `Pending` and `Review` with
  each push's level, class, approvals needed and per-push counts and runs; paged `Comments` of one run as one profile
  document; `Comment`, a reply by `replyTo`, `toForge`; `ResolveThread` with `reopen`; `Approve` and `Reject` bound to
  the push's commit, which never push anything and say so in their answer. Every other method answers `Failed` naming
  the stand-in.
- **Where the pushes come from:** one configured GitHub repository and pull request numbers, given on the command line,
  never guessed. Each pull request is one push: its head is the push's commit, its merge base the review's base. A bare
  clone the stand-in owns mirrors the repository, the head under `refs/porelo/incoming/<name>`, refetched when the head
  moves, so the review fetch reads both sides through it. `Review`'s diff, log and files come from git over that clone;
  each file's rank is `ORDINARY` and its reason says it is the stand-in's.
- **The conversion, taken from [GH02](README.md#gh02):** reviews, review comments, replies and threads read through the
  `github` client and converted to the review profile (anchors and diff side, outdated with its original anchor, the
  suggestion fence as `fixes`, thread state and IDs, author evidence, provenance times), and a submitted comment
  rendered as the native request the client sends. It lives where [GH02](README.md#gh02) needs it, in `adapters/github`,
  so [GH02](README.md#gh02) builds on it unchanged.
- **The daemon's decisions as fixed values,** each named in the answer as the stand-in's: a comment written through the
  socket is `I1`; a GitHub comment `E1` when its author association is `OWNER`, `MEMBER` or `COLLABORATOR`, else `E0`;
  a reply the stand-in writes itself on a command `A`. Risk class, approvals and completeness come from a fixture
  file per push. Nothing the stand-in answers clears a finding or approves anything.
- **Fixture pushes beside the GitHub ones:** synthetic pushes whose commits the stand-in makes in its clone, each
  serving profile documents from files - file and general threads, an earlier round and an outdated thread, an old-side
  thread, a suggestion inside and one outside its anchor, a hostile URI and a bidi or zero-width character in text,
  `partial`, `failed` and `unknown` runs, more results than one page holds, a push at `E0` and one at `C3` waiting for a
  second approval - and the refusals the review display maps: `PullRequestMoved` on a `toForge` comment,
  `MovedSinceReview` on `Approve`, one anchor refusal.
- **The interface text:** no published artifact carries `Tasks1`'s interface, so the stand-in carries its own copy of
  just the methods it serves and their types: the gate's as the daemon has them today, and the review additions as
  drafted, marked as draft, with a header line naming the daemon commit it was copied from; it is deleted with the
  stand-in.
- **Module:** `standin/` beside `contract` and `adapters`, a plain jar run with `exec:java`, never packaged, published
  or deployed (`maven.deploy.skip`, no `dist` profile), its README saying what it is and that it goes when the daemon
  serves the calls.
- **What reaches GitHub:** as the drafted daemon contract has it, a comment without `toForge` and every resolve stay
  local; a comment with `toForge` is sent at once, without the release step the daemon will have, and its answer says
  so. The stand-in's own replies (`A`) are served only locally (operator).
- **Where:** the forge clients' test repository `porelo-ai-test/forge-clients-live`, with a fine-grained token of its
  test user limited to that repository, which the operator makes (operator).
- **The token** reaches the stand-in through its environment and goes to the client per call; it is in no answer, log
  line, file or command line, and the socket's directory is the caller's own, mode 0700.
- **Red first:** `StandInTest` connects to the stand-in's socket with `porelo-wire`'s client, the GitHub client given a
  replaying `HttpClient`: a `Comments` page whose recorded review comment has its `side` or `line` changed must fail
  naming that field; then a `Comment` with a forged `replyTo` must answer `NoSuchResult` with no write call made; then
  no answer and no log line may carry the token.

## Acceptance

- The review display, pointed at the socket, reads a real pull request's comments and threads, writes a comment and a
  reply that appear on GitHub, and resolves a thread; each fixture push shows what it was made for.
- A read never writes; `Approve` and `Reject` send nothing anywhere; the token appears in no answer, log or file. Each
  guard is seen to fail first.

## To be checked

- Which calls the review display makes before these (a project list, a describe), and how its fetch reaches the bare
  clone (a local path first) - from the plugin's agent.
- Building against the forge clients' local snapshot under another agent's installed area (`maven.repo.local.tail`),
  and whether their recordings are published for a replaying `HttpClient` or must be recorded here.
- What the `github` client offers at 4275da2 for reviews, review comments, replies, threads and resolve, and what
  waits for its next additions (issue comments, one thread by its ID, the merge base); the first round takes general
  comments and the merge base from git where the client has no call yet.
