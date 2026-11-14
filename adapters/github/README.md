# GitHub adapter

This module reads GitHub Actions through `org.fuin.porelo.Build2`, depending on `porelo-build-api`, `porelo-wire` and the `github` forge client from Porelo, and holds no state between calls.
The `native,dist` profiles build `target/porelo-adapter-github` and its Debian and RPM packages, installed at `/usr/libexec/porelo/builds/github`; the forge token arrives over the owner-only socket and never reaches the log blob host.
See the [adapter documentation](../../doc/index.md) and the [contract suite](../../contract/README.md).
