# GH32 — The Forgejo Adapter Serves The Review's Minimum

```json
{
  "$schema": "issue-1.json",
  "id": "GH32",
  "title": "The Forgejo Adapter Serves The Review's Minimum",
  "state": "Accepted",
  "size": "-",
  "priority": "Medium",
  "rank": 7,
  "blockedBy": ["GH10"],
  "covers": "One person's review on Forgejo: `Review1` beside `Build2` with a pull request's facts, one page of comments and one released comment written, as on GitHub; waits for porelo's `Review1` description and a two-interface `VarlinkServer`"
}
```

**What must be true.** One person reviewing a pull request on Forgejo reads its facts and one page of its reviews and
comments through `porelo-adapter-forgejo`, and writes one released result there, served as `Review1` beside `Build2`
on the adapter's socket - the same minimum the GitHub adapter serves.

- It builds on [GH10](README.md#gh10)'s conversion. From outside it waits on porelo's written `Review1` description and a
  `VarlinkServer` that serves two interfaces, neither in porelo's code today.
- Expected size M, as the GitHub adapter's minimum.
