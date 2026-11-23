# GitHub adapter

This module reads GitHub Actions through `org.fuin.porelo.Build2`, depending on `porelo-build-api`, `porelo-wire` and the `github` forge client from Porelo, and holds no state between calls.
The `native,dist` profiles build `target/porelo-adapter-github` and its Debian and RPM packages, installed at `/usr/libexec/porelo/builds/github`; the forge token arrives over the owner-only socket and never reaches the log blob host.
Its review conversion takes every comment GitHub holds: a body of up to 65,536 characters, any span, suggestion and path length, as [decided](../../doc/decisions.md#the-review-profile-takes-what-github-takes).
See the [adapter documentation](../../doc/index.md) and the [contract suite](../../contract/README.md).
