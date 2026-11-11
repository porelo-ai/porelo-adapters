# GH31 — The Build Takes Its Downloads From A Cache

```json
{
  "$schema": "issue-1.json",
  "id": "GH31",
  "title": "The Build Takes Its Downloads From A Cache",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The Build fetches nothing from outside that a cache could hold, `rpm` included, and no cache keeps a superseded entry past seven days"
}
```

**Why this size.** One workflow step and the caches of the `pinned-jdk` action; no code and no contract another
repository reads.

**What must be true.** A Build of this repository fails only when what it builds is wrong, not because a mirror outside
answered late: every download it makes that a cache can hold comes from one, and no cache keeps a superseded entry for long.

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

- `build.yml`'s step `rpm, to read the built package` takes `rpm` from the image when the image has it, and calls no
  mirror then. Otherwise it restores the `.deb` files of `rpm` and its dependencies from a cache keyed by the runner
  image (`ImageOS` and `ImageVersion`) and installs them with `dpkg -i`, which needs no fresh package lists. Only on a miss does it
  call the archive, `apt-get update` and `apt-get install --download-only` with retries, which checks each file against
  the signed index, and saves what it fetched under that key.
- Old entries go by GitHub's own rule: an entry not read for seven days is deleted. Every key here names a version, a
  digest, a pom hash or a runner image, so an entry that is superseded is no longer read and goes after seven days;
  no step deletes entries itself, so no step needs `actions: write`.
- Test levels: none in the unit suite; the change is a workflow. It is seen working in the Build: the first run fills
  the cache, the next reads it, and neither calls a mirror when the image has `rpm`.
