# GH35 — The Docs Say What The Code Does

```json
{
  "$schema": "issue-1.json",
  "id": "GH35",
  "title": "The Docs Say What The Code Does",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every sentence of the user-facing pages - the READMEs, `doc/` but its decisions, the changelog's latest section - checked against the code; what the code does not bear out is corrected, and where the code is wrong a bug is filed instead"
}
```

**Why this size.** One reading of ten pages against the code, every finding checked by hand, and corrections in
documents alone; no behavior changes.

**What must be true.** A person who reads this repository's READMEs, `doc/index.md`, `doc/contract.md` or the
changelog finds no command, path, default, dependency, message or recording the code does not bear out.

## Plan

- A reader reads `README.md`, every module's `README.md`, `doc/index.md`, `doc/contract.md`, the changelog's latest
  released section and `changelog/unreleased/` page by page against the code, and lists each claim the code does not
  bear out with the code that contradicts it.
- Each finding is checked in the code. Where the code is right, the page is corrected; where the page is right and
  the code is wrong, a bug is filed instead. A released changelog entry that was true at its release stays.
- Found and corrected: the GitHub adapter builds against the `github` forge client too, which the root and module
  READMEs and the pom's comment denied; `doc/contract.md` named fixed capture days in each recording's `source`, called
  Forgejo's recorded `triggers` and `failed-tests` generated, and gave every failed log the 383,260-byte original; the
  quoted refusal of a token in `doc/index.md` lacked the permissions the message names.

## Acceptance

- Each corrected sentence matches the code it was checked against; the tests tagged `documents`, `check-citations`
  and `check-commits` pass.
