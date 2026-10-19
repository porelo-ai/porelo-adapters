# GH05 — Publish Without A Downloaded Client

**Status:** Idea.

**What must be true.** The Build's `Publish` speaks to Artifactory without downloading a client into the job that
holds the token: no `jfrog/setup-jfrog-cli` and no `jf` in `.github/`, as in porelo.

## Why

`Publish` runs `jfrog/setup-jfrog-cli`, which downloads the JFrog CLI without a checksum into the job beside
`JF_ACCESS_TOKEN`. porelo dropped it on 2026-10-18: its `Publish` and smoke test go through
`.github/scripts/artifactory.sh`, `curl` over Artifactory's REST interface, with the token in a curl config file of
mode 600 and each upload carrying its SHA-1 and SHA-256, which Artifactory checks. It was proven there by the smoke
test on a branch and the first `Publish` on main, whose indexes listed every package.

## The shape proposed

- `.github/scripts/artifactory.sh` taken over from porelo byte for byte.
- `Publish`'s uploads become `art_put`, the .deb with `deb.distribution`, `deb.component` and `deb.architecture`.
- The refusal of a release already in the index and the indexing read-back stay as they are.
- Red first against a stand-in server, as porelo's was checked; the proof is the first `Publish` on main with every
  package found in the index.
