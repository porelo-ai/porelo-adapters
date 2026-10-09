# GH01 — Release The Reader As A Package

**Status:** soon.

**What must be true.** Somebody who runs Sokar installs the GitHub build reader from the same `releases` indexes as
Sokar and its agents, and `sokar` finds it there without a step of their own. A tag `v<version>` of this repository
publishes exactly that version, and a push to `main` publishes a snapshot of it, as every other Sokar repository does.

## The shape

- `build.yml` runs on `v*` tags as well. A publish job after the build builds the native reader once and packages it
  as a `.deb` and an `.rpm` that put `sokar-build-github` in `/usr/libexec/sokar/builds/`, where
  `InstalledBuildReader.PACKAGED` already looks.
- The job uploads both packages to the `releases` indexes for a tag and to the `snapshots` indexes for `main`
  (`sokar-dist-deb`, `sokar-dist-rpm`), and waits until each index lists the file it uploaded.
- Before the upload: a tag must carry its own version and no snapshot (`check-releases`). The packages are installed
  in a clean Debian and a clean Fedora, and there the reader answers `describe` with `"forge":"github"`.
- The package depends on `sokar`. Its version follows this repository's version and sorts as the agents' packages do:
  `~snapshot.<run>` below the release.
- Only what is released is published: the jars stay unpublished, as now.

## Acceptance

- A tag's run publishes `sokar-build-github` `<version>` to both `releases` indexes. On a clean machine with Sokar
  installed from them, `apt install` or `dnf install` puts the reader where `sokar` finds it, and a task with
  `builds: { forge: github }` follows its builds without anything copied into the account.
- A push to `main` publishes a snapshot that a later tag's package replaces on upgrade.
- Seen to fail first:
  - a tag whose version is not the pom's stops before anything is uploaded;
  - a package that puts the reader anywhere but `/usr/libexec/sokar/builds/` fails the install check;
  - an upload the index does not list fails the run.

## To be checked

- Whether the packaging is taken from the agents' repositories as it is there, or shared through the build tools.
