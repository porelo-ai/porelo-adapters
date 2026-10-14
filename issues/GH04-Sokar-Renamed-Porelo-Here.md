# GH04 — Sokar Renamed Porelo Here

**Status:** Estimated.
**Size:** S - 330 occurrences in 27 of 45 files and 5 paths, one module, and a package Porelo finds by its path.

**What must be true.** The GitHub build reader is Porelo's: `org.fuin.porelo:porelo-build-github`, packaged as
`porelo-build-github` and installed at `/usr/libexec/porelo/builds/github`, where Porelo looks; no tracked file
outside the exceptions says `sokar`. From [PJ41](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj41).

## Plan

**How:** one commit, made by a script this repository's agent runs and reviews, never by hand: `git mv` for every
path naming `sokar`, then `sokar`/`Sokar`/`SOKAR` replaced by `porelo`/`Porelo`/`PORELO`, then the exceptions below
put back. Nothing is renamed before the window is announced.

**What carries the name:** the package `org.fuin.sokar.build.github` and its paths, the artifact and its parent, the
property `sokar.version` and the artifacts it resolves (`sokar-build-api`, `sokar-wire` through `sokar-bom`), the
install path `/usr/libexec/sokar/builds/github` and the package `sokar-build-github`, which depends on `sokar`.

**Exceptions:** the 8 files under `changelog/v*` (released entries), and a sentence that says what the old name was.

**What the script cannot do by replacement:** the Debian pool path `pool/main/s/sokar-build-github` in `build.yml`
becomes `pool/main/p/porelo-build-github` - the letter is the package name's first.

**Published before this builds:** `porelo-parent` `0.2.0`, the build tools `0.5.0`, and `porelo` `0.5.0-SNAPSHOT`
with `porelo-build-api` and `porelo-wire`.

**How it is tested:** red first, a test tagged `documents` that fails while any tracked file outside the exceptions
says `sokar`; then the reactor, the native build, and the package installed beside `porelo` on the ubuntu VM, where
`porelo` finds it and `describe` answers.

## Acceptance

- The test above is red before the script and green after it, and stays.
- On the VM, Porelo finds the reader at its new path and reads a build with it.
