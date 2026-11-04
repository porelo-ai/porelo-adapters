# GH16 — The Old GitHub Package Leaves The Channels

**Status:** Idea.
**Size:** S - twelve published files removed in two steps, a few lines of documentation, no code; M if a transitional package is chosen.

**What must be true.** `apt install 'porelo*'` installs and updates every Porelo package on `snapshots` and on `releases`, on a
new host and on one that has `porelo-build-github`, and `porelo-build-github` stays out of both channels.

## Why

`porelo-adapter-github` replaced `porelo-build-github` and declares `Conflicts` and `Replaces` for it, or `Obsoletes` in the
rpm. Both install `/usr/libexec/porelo/builds/github`, the one path two packages share on either channel. Nothing publishes
the old name anymore, yet its files still lie in the channels:

| Channel | Debian | RPM |
|---|---|---|
| `releases` | `0.5.0` | `0.5.0` |
| `snapshots` | `0.5.0~snapshot.7`, `0.5.1~snapshot.9`, `.11`, `.13`, `.14` | the same five |

Measured in fresh Ubuntu 26.04 and Fedora 44 containers against the published indices, with a removal simulated on a filtered
copy of the index and nothing changed in the channels. The apt rows are `apt-get -s`, which solves dependencies without unpacking.

| Line | Case | Result |
|---|---|---|
| apt | `releases` today | passes, installs the old reader |
| apt | `snapshots` today | refused: `porelo-adapter-github : Conflicts: porelo-build-github` |
| apt | `releases` and `snapshots` today | refused, the same |
| apt | `snapshots` alone, old package removed there | passes |
| apt | both sources, old package removed from `snapshots` only | refused by `0.5.0` in `releases` |
| apt | `releases` with an adapter added, using the snapshot's index entry, old package kept | refused |
| apt | the same, old package removed | passes |
| apt | old package really installed, removed from the index | refused: `'porelo*'` also selects the installed package |
| apt | the same, after `apt purge porelo-build-github` | passes |
| `apt-get upgrade`, `full-upgrade` | the same host, before the purge | Porelo moves on, the old reader stays and no adapter arrives |
| `apt install porelo-adapter-github` | the same host | passes, removes the old package |
| dnf | each channel and both sources, old package still published | resolves; the test transaction, run on each channel alone, passes without a file conflict |

Removal alone therefore does not settle a host that has the old package. A Porelo of `0.5.0` speaks only
`org.fuin.porelo.Build1` and the adapter only `Build2`, while Porelo refuses a reader whose protocol it does not know: read
in the code and the documentation, not run. Taking `0.5.0` out of `releases` before an adapter and a Porelo speaking `Build2`
are released there would leave that channel without a GitHub reader.

## Plan

- From `snapshots`, delete the five Debian files under `porelo-dist-deb/pool/main/p/porelo-build-github/` and the five rpm
  files under `porelo-dist-rpm/snapshots/`. This can happen at any time. Until the second step, a host with both sources stays
  refused.
- From `releases`, delete `porelo-build-github_0.5.0_amd64.deb` and `porelo-dist-rpm/releases/porelo-build-github-0.5.0-1.x86_64.rpm`,
  right after the published index shows the first released `porelo-adapter-github`, built against a released Porelo that speaks
  `Build2`.
- After each step, read both indices anonymously with retries until the name is gone, then run the line on a fresh host.
- Keep `Conflicts`, `Replaces` and `Obsoletes` in `adapters/github`, since they remove the old package from a host.
- Write what a host with the old package does once in `doc/index.md`, and correct what the changelog entry says.
- Nothing here adds a test: the published-index check of the line belongs to the repository that publishes Porelo itself, and it
  turns red when the name returns.

**What could bring it back.** No current build can: `main` publishes only `porelo-adapter-github` and `porelo-adapter-forgejo`,
the former repository name redirects here, and no other repository publishes the name. A re-run of an old run can, since it runs
its old commit with its old workflow. The `v0.5.0` run refuses only while its package is in the index, so after the deletion
a re-run republishes it. Nothing in this repository prevents that re-run.

## Acceptance

- Both published indices list no `porelo-build-github`, in Debian and in rpm metadata.
- The line passes on `snapshots` and on `releases`, each alone and on both together, in a fresh Ubuntu 26.04 container.
- A host with the old package really installed is taken through the documented way and ends with `porelo-adapter-github`
  installed, `porelo-build-github` gone.

## Open questions

- May the ten files of `snapshots` be deleted now, and the two of `releases` at the first release of the adapter? A deleted
  release cannot be installed anymore.
- A host with the old package: one documented `sudo apt purge porelo-build-github`, or a transitional package? Recommended is the
  purge: the only release is `0.5.0` and the name stays gone. A transitional `porelo-build-github` that depends on the adapter
  would keep the line working without a step, but keeps the name in both channels and installs an empty package on every new
  host; it also needs a version bound on the `Conflicts`. It has not been run.

## To be checked

- Whether `JF_ACCESS_TOKEN`, the account the build publishes with, may delete in these paths. Only a run's own probe file has
  been deleted with it, in a step that ignores failure, so this is unknown until tried. No agent holds a token for it.
- Whether Artifactory recalculates both indices on a deletion, and how long that takes: expected, not observed.
- `dnf` on a host with the old package really installed: `Obsoletes` should replace it, not run.
- Whether `Depends: porelo` needs a lower bound at the first Porelo that speaks `Build2`, so that the adapter cannot be installed
  beside a Porelo that refuses it.
