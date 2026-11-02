# Decisions

What holds and why: one row per decision, and its full text below.

| Covers | What holds |
|---|---|
| Review conversion | [Review text is parsed, not modeled](#review-text-is-parsed-not-modeled) |
| Review conversion | [A fence that may not be what it seems is held or refused](#a-fence-that-may-not-be-what-it-seems-is-held-or-refused) |
| Review conversion | [What the conversion leaves unchecked](#what-the-conversion-leaves-unchecked) |
| Stand-in | [The stand-in sends a comment at once, and a sent comment is one result](#the-stand-in-sends-a-comment-at-once-and-a-sent-comment-is-one-result) |
| Stand-in | [What the stand-in leaves unguarded, as a test tool](#what-the-stand-in-leaves-unguarded-as-a-test-tool) |

## Review text is parsed, not modeled

What is code, HTML or text in a comment is the answer of a CommonMark parser (`commonmark-java` with GitHub's tables and
footnotes), because a hand-built model kept reading cases otherwise than GitHub renders them.

- A read drops HTML once and parses the result again. When what a reader sees differs, by a tag rebuilt from pieces or
  text turned into code, the comment is held, so a read costs two parses.
- An HTML block other than a comment is held rather than dropped, since GitHub shows its text.
- A write refuses an image, and a character reference in a link's destination or title or in a fence's info, which GitHub
  decodes.
- A code span over several lines is read as prose, its markup dropped and named as a loss, since a line may start a block
  that ends the span.

## A fence that may not be what it seems is held or refused

- A fence indented by one to three spaces may sit in a list item the next line ends, and a fence after a line that may
  open an HTML block may not be a fence at all. A write refuses both and a read holds the comment, rather than modeling
  lists.
- An indented, tilde or uppercase `suggestion` fence stays text, never a fix, which loses nothing that could be applied
  wrongly.

## What the conversion leaves unchecked

Each is accepted because a wrong value here is refused or costs nothing; GitHub changing the behavior named would change
the answer.

- A `porelo/resolverType` of `user` stays, since GitHub's GraphQL types a thread's `resolvedBy` as `User`.
- A thread's `startDiffSide` is not compared, since a comment whose `start_side` differs from its `side` is already held.
- REST IDs are not checked to be positive, since GitHub numbers from 1 and a wrong one costs nothing here.
- `message.markdown` is never sent, so its length is held only by the document's limits.
- A fix with columns in its `deletedRegion` is refused `AdapterRefused` before the columns' type is checked, a refusal
  either way.

## The stand-in sends a comment at once, and a sent comment is one result

The stand-in serves the daemon's review calls for a test, so it has no release step: a comment with `toForge` is sent at
once and its answer says so. A comment without `toForge`, every resolve and the stand-in's own replies stay local.

A `toForge` comment keeps its local result, which takes GitHub's comment, kind and review IDs once sent. GitHub's copy,
matched by that comment ID and never by body, is neither stored again nor held; a reply to it on GitHub joins the local
result's thread. A sent reply keeps the GitHub comment it answered, so a reply to it goes to that root. An edit or
deletion of that comment on GitHub is not shown, since the stand-in reads GitHub's copy only to match it.

## What the stand-in leaves unguarded, as a test tool

It never ships and is deleted once the daemon serves the calls, so these stay. Any of them would be fixed if the code
were to move into something that ships.

- Reading a push's runs while a comment is stored, and a push's base and commit, are not under one lock. A collision or
  a torn pair answers `Failed` or refuses loud, and the next call checks again.
- The socket directory's owner is compared with the home directory's owner, which refuses where it is wrong and never
  lets more through.
- A token encoded in git's header is not searched for in answers, since git never prints that header.
- `Approve`'s `signed` and `Reject`'s `force` and `reason` are not type-checked, since the stand-in reads none of them.
- A `WatchProjects` stream is not tested past its first answer, since every later one passes the same guard.
- That a sent result is bound before a reader can see it has no test, since no seam lets a test stop between the two; a
  reader there sees the result without GitHub's IDs for one call or answers `Failed`.
