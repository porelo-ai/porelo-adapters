# GH29 — The Planning Modules Leave

```json
{
  "$schema": "issue-1.json",
  "id": "GH29",
  "title": "The Planning Modules Leave",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The `planning/` module group - `stories-git`, the planning contract suite, their docs, tests and changelog entries - leaves the repository, and nothing left names it"
}
```

**Why this size.** one module group and what names it, no contract another repository reads.

**What must be true.** `porelo-adapters` builds, tests and ships only build adapters: the `planning/` module group -
the `stories-git` planning adapter, the planning contract suite, their documentation and tests - is gone, and nothing
left in the repository names it.

## Plan

- Remove `planning/` whole (`planning/pom.xml`, `planning/contract`, `planning/stories-git`, their READMEs and test
  resources) and its `<module>planning</module>` in the root `pom.xml`.
- Remove what names it outside: the planning line of the root `README.md`, `citations-exempt.txt`, which held only
  the planning samples, and the unreleased changelog entries that describe only it
  (`planning-batches-and-credential-guards.yml`, `planning-contract-fixtures.yml`, `stories-git-read-safety.yml`,
  `stories-git-reads.yml`), since what they describe never shipped.
- [GH23](README.md#gh23)'s item on the planning adapter's tests goes, with its row's text if it names it.
- No changelog entry: nothing of `planning/` ever shipped. Neither `porelo-planning-contract` nor
  `porelo-planning-stories-git` is in the snapshot repository's index, and the workflow publishes only the GitHub and
  Forgejo packages.
- No new test: nothing that can do harm changes; the build's own module list and the documents' checks show it.

## Acceptance

- `git grep -i -e planning -e stories-git` finds nothing but this issue and the rejected rows of the index.
- `./mvnw -B -s settings.xml -Pnative,dist verify` and `-Pdocuments test` green, and the module list in the build's
  log names no planning module.
