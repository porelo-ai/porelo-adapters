# GH01 — Release The Reader As A Package

**Status:** Review.
**Size:** S - built: the publish job and the packages; the first push published `0.4.2~snapshot.3` and `.4` to the
`snapshots` index.

**What must be true.** Somebody who runs Porelo installs the GitHub build reader from the same `releases` indexes as
Porelo and its agents, and `porelo` finds it there without a step of their own. A tag `v<version>` of this repository
publishes exactly that version, and a push to `main` publishes a snapshot of it, as every other Porelo repository does.

## The shape

- `build.yml` runs on `v*` tags as well. A publish job after the build builds the native reader once and packages it
  as a `.deb` and an `.rpm` that put `porelo-build-github` in `/usr/libexec/porelo/builds/`, where
  `InstalledBuildReader.PACKAGED` already looks.
- The job uploads both packages to the `releases` indexes for a tag and to the `snapshots` indexes for `main`
  (`porelo-dist-deb`, `porelo-dist-rpm`), and waits until each index lists the file it uploaded.
- Before the upload: a tag must carry its own version and no snapshot (`check-releases`). The packages are installed
  in a clean Debian and a clean Fedora, and there the reader answers `describe` with `"forge":"github"`.
- The package depends on `porelo`. Its version follows this repository's version and sorts as the agents' packages do:
  `~snapshot.<run>` below the release.
- Only what is released is published: the jars stay unpublished, as now.

## Acceptance

- A tag's run publishes `porelo-build-github` `<version>` to both `releases` indexes. On a clean machine with Porelo
  installed from them, `apt install` or `dnf install` puts the reader where `porelo` finds it, and a task with
  `builds: { forge: github }` follows its builds without anything copied into the account.
- A push to `main` publishes a snapshot that a later tag's package replaces on upgrade.
- Seen to fail first:
  - a tag whose version is not the pom's stops before anything is uploaded;
  - a package that puts the reader anywhere but `/usr/libexec/porelo/builds/` fails the install check;
  - an upload the index does not list fails the run.

## As built, 2026-10-09

- **The packages:** `-Pnative,dist verify` in `reader` builds `porelo-build-github_<version>_amd64.deb` and
  `porelo-build-github-<version>-1.x86_64.rpm`, putting the reader at `/usr/libexec/porelo/builds/github` and depending on
  `porelo`, `libc6 (>= 2.34)` and `zlib1g` (`libc.so.6(GLIBC_2.34)(64bit)`, `libz.so.1(ZLIB_1.2.2)(64bit)` in the rpm).
  The version is the reader's with `-SNAPSHOT` as `~snapshot.<run>`.
- **Checked in the build, shared through the build tools rather than copied from the agents:** `porelo-release
  check-linkage` holds the reader to the declared C library and zlib, and the floor to Debian 13's glibc 2.41;
  `porelo-release check-package-version` reads both packages' version and the rpm's release.
- **`build.yml`:** a pinned runner (`ubuntu-24.04`), the packages built with the run number, a push to `main`
  publishing to `snapshots`, a tag `v<version>` to `releases` after `check-releases`, a release already there refused,
  and the index checked to list the upload - as the agents publish.
- **Measured:** the packages install beside a local `porelo` in clean `ubuntu:26.04`, `debian:13`, `fedora:44` and
  `fedora:43`, and the reader answers `describe` there. The workflow's channel step refuses a tag that is not the
  version, a snapshot under a tag, a release on a branch and a release on a snapshot Porelo.
- **Open:** the upload itself, first by the operator's push to `main`; the acceptance leg is left for later.
