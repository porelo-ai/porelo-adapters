# GH10 — Forgejo Reviews Converted Into The Profile

```json
{
  "$schema": "issue-1.json",
  "id": "GH10",
  "title": "Forgejo Reviews Converted Into The Profile",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [],
  "covers": "A Forgejo pull request's reviews and comments read into Porelo's SARIF profile, and one released result turned into the `forgejo` client's calls, over the client's records, sharing the forge-neutral review code with the GitHub adapter"
}
```

**Why this size.** Two modules (`adapters/github`'s forge-neutral review code shared, `adapters/forgejo`) and their unit
tests; no contract and nothing served yet. **Estimate:** 56 minutes, the measured median of M.

**What must be true.** The Forgejo adapter turns what the `forgejo` client reads of a pull request - its reviews, line
and general comments - into the same profile document the GitHub adapter writes, and one released result into the
client's calls, holding what Forgejo cannot do; nothing is served over a socket yet. Serving it is
[GH32](README.md#gh32), every further operation [GH33](README.md#gh33).

## Plan

- Share the forge-neutral review code of `adapters/github` (`ReviewDocument`, `ReviewText`, `ReviewRefusal`,
  `RepositoryPath`) with the Forgejo adapter, the forge's and tool's names as parameters, without a dependency of one
  adapter on the other.
- `ForgejoReviewRead` and `ForgejoReviewWrite` under `org.fuin.porelo.build.forgejo.review`, over the `forgejo` client's
  records (`PullRequest`, `PullReview`, `PullReviewComment`, `Comment`): native old and new positions kept, a resolver
  read as resolved, and a reply to a review comment or a thread state held as unsupported on the forge.
- Read in today's code: the `forgejo` client has `pull`, `reviews`, `createReview`, `dismissReview`, `reviewComments`,
  `createReviewComment`, `deleteReviewComment`, `issueComments`, `createIssueComment`, `version` and `permission`, and
  no call for review threads, a reply to a review comment, or resolving a thread; Forgejo 17's API description has none
  either. `createReviewComment` needs an existing review, so a single line comment goes through `createReview`. The
  conversion on GitHub's side builds the profile with Jackson today, not with `java-sarif`.
- Red first, unit level over recorded Forgejo answers: a pull request with reviews and comments read into a profile run
  naming `forgejo`, with its revisions, reviews and comments and the text cleaned as on GitHub; and a reply to a review
  comment held before any call. Both fail today, since no Forgejo review code exists.

## To be checked

- How `PullReviewComment.position` and `originalPosition` map onto the profile's new and old sides, read against
  recorded answers.
