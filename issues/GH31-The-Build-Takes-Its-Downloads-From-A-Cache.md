# GH31 — The Build Takes Its Downloads From A Cache

```json
{
  "$schema": "issue-1.json",
  "id": "GH31",
  "title": "The Build Takes Its Downloads From A Cache",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 7,
  "blockedBy": [],
  "covers": "The Build fetches nothing from outside that a cache could hold, `rpm` included, and each of the repository's caches keeps only its newest entry"
}
```

**Why this size.** One workflow step and the caches of the `pinned-jdk` action; no code and no contract another
repository reads.

**What must be true.** A Build of this repository fails only when what it builds is wrong, not because a mirror outside
answered late: every download it makes that a cache can hold comes from one, and no cache grows past its newest entry.

- Today the `pinned-jdk` action caches the wrapper's Maven, the Maven repository, shfmt and the GraalVM. One download
  is not cached: `build.yml`'s step `rpm, to read the built package` runs `sudo apt-get update` and
  `sudo apt-get install rpm` against Ubuntu's mirrors on every Build; `check-package-version` reads the built `.rpm`
  with it. On run 42833881818 the step took 6 seconds; its `-qq` output does not say whether `rpm` was already there.
- The Maven repository's cache is keyed by the hash of every `pom.xml` and restored by prefix, so each pom change adds
  an entry and the older ones stay until GitHub evicts them. The other caches are keyed by version or digest and leave
  the old version's entry behind the same way.
- Snapshot metadata that `-U` asks Sonatype for, and the publication's index probes, are answers about now and are not
  cached.

## Plan

- Measure whether `ubuntu-24.04` ships `rpm` (`command -v rpm` in a run, or the runner image's package list). If it
  does, the step only refuses when `rpm` is missing and calls no mirror. If it does not, the `.deb` files `apt-get
  install --download-only` fetches are cached under a key of their names and versions, checked against the digests
  in the package index, and installed with `dpkg -i` from the cache.
- Each cache in the `pinned-jdk` action and the step above keeps only its newest entry: after a save under a new key,
  the entries of the same prefix with another key are deleted through the Actions cache API, which needs
  `actions: write` for that step, or GitHub's own limit is used if a repository setting can delete entries after a
  set time.
- Test levels: none in the unit suite; the change is a workflow. It is seen working in a Build with an empty cache
  and in one after it, whose log shows no mirror called and one entry per cache.

## To be checked

- Whether the `ubuntu-24.04` image ships `rpm`.
- Whether a repository setting deletes cache entries after a set time, and what retention GitHub applies today.
