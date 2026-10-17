# reader

The build reader itself: it answers Porelo's `org.fuin.porelo.Build1` questions - where a branch points, what the
build of a commit did - from the GitHub Actions API, built on the published `porelo-build-api`. It is not Porelo and
holds no state of its own between calls.

- **Built natively** with `-Pnative` into `target/porelo-build-github`, which a package installs as
  `/usr/libexec/porelo/builds/github`.
- **The forge token arrives in each call** over the reader's owner-only socket, and never goes to the host that
  serves a job's log.
- Depends on `porelo-build-api` alone, never on Porelo's implementation; what it reads is in the
  [documentation](../doc/index.md).
