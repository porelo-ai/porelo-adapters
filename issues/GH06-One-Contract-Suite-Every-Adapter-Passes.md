# GH06 — One Contract Suite Every Adapter Passes

**Status:** Idea; blocked by `porelo` [B253](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b253).

**What must be true.** This repository builds the module `porelo-build-contract`, a suite that drives an adapter
over `org.fuin.porelo.Build2` from recorded answers and holds every case the build contract names, and the `github`
adapter passes it as `porelo-adapter-github`, packaged in place of `porelo-build-github` at
`/usr/libexec/porelo/builds/github`; an adapter changed to answer one case wrongly fails the suite.

## Why

From porelo-project [PJ62](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj62): every forge
adapter lives in one repository beside one suite, so a second adapter is held to what the first answers. The suite
is published, so an adapter outside the project can run it.

## Acceptance

- The suite's cases, all from recorded answers, so CI calls no forge: a branch's head and an absent branch; a commit
  with no build, queued, running, passed, failed and cancelled; the logs of `none`, `failure` and `all`, with the
  bounds of 64 KiB a log, 50 jobs and 2 MiB; each refusal (`RateLimited`, `CredentialRefused`, `NoSuchRepository`,
  `Unreachable`).
- The `github` adapter passes it; each case is seen to fail once with the adapter broken for it.
- After `porelo-adapter-github` is installed in place of `porelo-build-github` (Debian `Replaces` and `Conflicts`,
  RPM `Obsoletes`), an `online` task's verdicts arrive as before.

## To be checked

- That driving an adapter through `porelo-build-api`'s `InstalledBuildReader` over its socket is all the suite needs,
  so `porelo` adds no public in-process seam.
- That the `github` adapter takes a loopback `http://` address for the recorded answers.
