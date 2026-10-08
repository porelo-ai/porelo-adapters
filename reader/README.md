# reader

The build reader itself: it answers Sokar's `org.fuin.sokar.Build1` questions - where a branch points, what the
build of a commit did - from the GitHub Actions API, built on the published `sokar-build-api`. It is not Sokar and
holds no state of its own between calls.

- **Built natively** with `-Pnative` into `target/sokar-build-github`, which a package installs as
  `/usr/libexec/sokar/builds/github`.
- **The forge token arrives in each call** over the reader's owner-only socket, and never goes to the host that
  serves a job's log.
- Depends on `sokar-build-api` alone, never on Sokar's implementation; what it reads is in the
  [documentation](../doc/index.md).
