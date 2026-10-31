# GH05 — Publish Without A Downloaded Client

**Status:** Review.
**Size:** S - one workflow file, `.github/workflows/build.yml`, and no contract another repository reads.

**What must be true.** The Build's `Publish` speaks to Artifactory without downloading a client into the job that
holds the token: no `jfrog/setup-jfrog-cli` and no `jf` in `.github/`, as in porelo.

## Why

`Publish` runs `jfrog/setup-jfrog-cli`, which downloads the JFrog CLI without a checksum into the job beside
`JF_ACCESS_TOKEN`. porelo dropped it on 2026-10-18: its `Publish` and smoke test go through
`.github/scripts/artifactory.sh`, `curl` over Artifactory's REST interface, with the token in a curl config file of
mode 600 and each upload carrying its SHA-1 and SHA-256, which Artifactory checks. It was proven there by the smoke
test on a branch and the first `Publish` on main, whose indexes listed every package.

## Plan

- Read in `.github/workflows/build.yml`, job `Build, packages and publish`: the `jfrog/setup-jfrog-cli` step goes; the
  step *Publish the packages* uploads each `.deb` and `.rpm` with `curl -fsS -K - -T`, the token's header reaching curl
  as its config on standard input once per call, with no shell trace on, never in a file or on a command line; each
  upload carries `X-Checksum-Sha1` and `X-Checksum-Sha256`, which Artifactory checks, and the `.deb` the matrix
  parameters `deb.distribution`, `deb.component` and `deb.architecture`, without which it is stored and never indexed.
- No copy of porelo's `.github/scripts/artifactory.sh`: the shared rules let no repository keep a copy of a helper
  another one has, and buildtools plans one published upload helper for every Publish. The calls stay inline in the
  step, as in porelo-message-sluice, until that helper exists.
- *The package is indexed, not merely stored* reads the index anonymously with `curl -fsSL`, as the refusal of an
  already published release does already, so only the upload step holds the token.
- Both steps refuse before any request a `JF_URL` that is not `https://`, a glob that does not match exactly one
  package, and a checksum that cannot be computed; every call takes `--proto =https`, the index reads also
  `--proto-redir =https`. An upload counts only on a 2xx, since curl's `-f` passes a redirect, which the upload does not
  follow so that the token stays with its host. A release whose index cannot be read is not uploaded. The read-back
  reads the index into a variable before matching it, since `grep -q` leaving a pipe early fails curl under `pipefail`
  once the index outgrows a pipe's buffer, and says what curl last answered.
- Red first: the steps' scripts replayed on the host, taken from `build.yml` as the runner runs them, against a stand-in
  Artifactory over HTTPS that records each request, checks the token and the checksums it is sent, and serves the index.
  Each guard is broken in a copy and the replay seen to fail: the `.deb` properties, a shell trace (the token in the
  output), a fail-open release index, the token check, the checksum headers and their assignment, the `https` checks,
  the 2xx check on a redirected upload, an index redirected to plain http, the single-package check, the piped read-back
  on a large index, an authenticated read-back. A token holding a newline already fails without a request and without a
  fragment of it in the output, so it needs no check of its own.

## Acceptance

- `command grep -rn 'jfrog\|jf ' .github/` finds nothing.
- The replay against the stand-in server passes and each of its failure paths has been seen red.
- The first `Publish` on main uploads all four packages and the read-back finds both `.deb` files in the snapshots
  index; the `.rpm` files are listed in `porelo-dist-rpm/snapshots/`.
