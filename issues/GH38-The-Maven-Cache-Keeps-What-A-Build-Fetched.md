# GH38 — The Maven Cache Keeps What A Build Fetched

```json
{
  "$schema": "issue-1.json",
  "id": "GH38",
  "title": "The Maven Cache Keeps What A Build Fetched",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The workflows' Maven cache is saved again whenever a Build fetched something new, so versions that move in the parent or a BOM without a pom here changing are fetched once, not by every Build"
}
```

**Why this size.** One file, `.github/actions/pinned-jdk/action.yml`, and a check of two Builds' logs; no contract, nothing
that ships. Under an hour.

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
  `maven-<os>-` as prefixes; save under a key that adds the run's id after the job, only when the job fetched something
  (the restored key's tree compared with the repository after the build, or a marker of Maven's downloads), so the
  cache does not grow by one copy per Build.
- The version range is not this repository's: the declaring pom is found with `dependency:tree -Dverbose` and the range
  is asked of its owner, so it is pinned there.
- Red first: a Build whose log shows a download from Maven Central after a cache hit, then the same pom state built
  twice, the second Build's log with no `Downloading from central`.

## Acceptance

- Two Builds in a row on the same commit: the second's log shows a cache hit and no `Downloading from central`, and the
  first saved its cache when it had fetched something.
- The number of Maven caches in the repository's cache list stays bounded over five Builds.

## To be checked

- Which pom declares the Bouncy Castle range, and whose it is.
- Whether the save step's condition is best read from Maven's download log or from a comparison of the trees.
