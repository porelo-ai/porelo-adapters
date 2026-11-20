# GH38 — The Maven Cache Keeps What A Build Fetched

```json
{
  "$schema": "issue-1.json",
  "id": "GH38",
  "title": "The Maven Cache Keeps What A Build Fetched",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The workflows' Maven cache is saved again whenever a Build fetched something new, so versions that move in the parent or a BOM without a pom here changing are fetched once, not by every Build"
}
```

**Why this size.** The CI's own actions alone: `pinned-jdk/action.yml`, a new `maven-cache-save/action.yml` and its
step in the three workflows that build with Maven, and a check of two Builds' logs; no contract, nothing that ships.
Under an hour.

**What must be true.** A Build whose dependencies were fetched by an earlier Build reads them from the cache, also when
they changed through the parent or an imported BOM, so a busy day at Maven Central fails no Build.

## Plan

- Read today: `pinned-jdk/action.yml` caches the runner's local Maven repository under `maven-<os>-<hashFiles('**/pom.xml')>`. The
  versions come from `porelo-parent` and the BOMs it imports, which move without any pom here changing, and
  `actions/cache` saves nothing after an exact hit. Measured in the last Build (run 43098574862, job 130784173960):
  `Cache hit for: maven-Linux-6de4c83c…`, then `not saving cache`, and in between 21 downloads, among them new snapshots
  of `porelo-build-api`, `porelo-wire` and `porelo-forge-client-github` and, from Maven Central itself,
  `bcprov-jdk18on` and `bcutil-jdk18on`'s `maven-metadata.xml`, which a version range resolves online in every Build.
- Split the cache into `actions/cache/restore` and `actions/cache/save`: restore by `maven-<os>-<pom hash>-` and
  `maven-<os>-` as prefixes; save under a key that adds the run's id and attempt, only when the job fetched something,
  so the cache does not grow by one copy per Build. Whether it fetched is read from the tree: a marker is touched right
  after the restore, and the job saves when a `.jar` or `.pom` outside the project's own version changed status after
  it. The tree is what the cache saves, and it needs no log of each Maven call in the job.
- The version range is not this repository's: `sshj` 0.40.0 depends on `bcpkix-jdk18on` 1.80, whose pom asks for
  `bcutil-jdk18on` `[1.80,1.81)`, and that one for a `bcprov-jdk18on` range. It reached `check-actions` through
  `porelo-machines` up to 0.5.2-SNAPSHOT. 0.5.3-SNAPSHOT, which `porelo-parent` names now, excludes `sshj`'s Bouncy Castle
  and pins 1.85. With it, the debug log of a local `check-actions` resolves only 1.85, with no range.
- Red first: a Build whose log shows a download from Maven Central after a cache hit, then the same pom state built
  twice, the second Build's log with no `Downloading from central`.
- Read by another model. Every finding was weighed and none changed the code:
  - A job that fetched a newer snapshot of a dependency saves a new cache. That is the purpose of the change. Excluding
    snapshots would bring back the fetch measured above.
  - A saved cache carries what it restored, so superseded snapshot files stay in it. GitHub removes a cache unused for
    seven days and the oldest beyond the repository's 10 GB, so the five Builds of the acceptance show whether that is
    enough.
  - A tag's Build saves a cache that only that tag can read. Tags are rare here, and the 10 GB bound holds them too.

## Acceptance

- Two Builds in a row on the same commit: the second's log shows a cache hit and no `Downloading from central`, and the
  first saved its cache when it had fetched something.
- The number of Maven caches in the repository's cache list stays bounded over five Builds.
- A push that changes only `.github/` and `issues/` starts no Build. The first two Builds are the daily Shared rules
  check and the next push with content.
