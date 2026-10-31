# Issues

The open tasks of `porelo-adapters`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `GH`. A group with nothing in it is
left out.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="gh05"></a>[GH05](GH05-Publish-Without-A-Downloaded-Client.md) | S | - | `Publish` speaks to Artifactory with inline `curl`, the token on standard input, downloading no client beside the token |

## Analyzed

| # | Size | Blocked by | What it covers | Open questions |
|---|---|---|---|---|
| <a id="gh02"></a>[GH02](GH02-Review-Comments-Read-And-Written.md) | L | [GH09](README.md#gh09) | GitHub serves `Review1` beside `Build2` as SARIF 2.1.0 through the `github` client, preserving thread and origin evidence and sending explicit authorized writes with per-call credentials | 0 |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="gh12"></a>[GH12](GH12-A-Stand-In-For-The-Daemons-Review-Calls.md) | - | - | A test-only stand-in serves the daemon's `Tasks1` review calls on a unix socket from a real GitHub pull request, through the forge clients and the GitHub-SARIF conversion, with the daemon's decisions as marked fixed values |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="gh11"></a>[GH11](GH11-The-Adapters-Agent-Works-In-A-Porelo-Task.md) | - | - | porelo-adapters' agent works in a Porelo task on the move's host as `Agent Adapters` and talks in the room; its build's publishing is first switched by a variable on both forges |
| <a id="gh09"></a>[GH09](GH09-Review-Cases-In-The-Contract-Suite.md) | - | - | Shared `Review1` cases in `porelo-build-contract` guard SARIF fidelity and native client calls, unsupported capabilities and authorized writes without duplicates; blocks [GH02](README.md#gh02) and [GH10](README.md#gh10) |
| <a id="gh10"></a>[GH10](GH10-The-Forgejo-Adapter-Reads-And-Writes-Reviews.md) | - | [GH09](README.md#gh09) | Forgejo serves reviews as SARIF beside `Build2` through the `forgejo` client, preserving origin and position evidence and holding unsupported writes |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="gh08"></a>GH08 | The Forgejo Adapter Trusts The Account's Authorities | `Http.overNetwork()` trusts the account's `~/.config/porelo/ca-certificates.pem` beside the built-in roots, so a reader reaches a forge signed by the host's own authority and still one signed by a public one |
| <a id="gh06"></a>GH06 | One Contract Suite Every Adapter Passes | `porelo-build-contract` holds one suite of questions, answer rules and generated boundary cases with evidence-checked recordings per forge, and the GitHub reader passes it over `Build2` as `porelo-adapter-github`, replacing `porelo-build-github`. |
| <a id="gh07"></a>GH07 | The Forgejo Adapter Answers As The GitHub One | `porelo-adapter-forgejo` reads Forgejo Actions over `Build2` and passes the same contract suite with recordings made from Forgejo 17 in podman. |
| <a id="gh03"></a>GH03 | The Code Read Against The Skills It Names | `reader` read against the five skills `AGENTS.md` names, each finding fixed with a test seen red or declined. |
| <a id="gh01"></a>GH01 | Release The Reader As A Package | A tag and `main` publish the reader as a `.deb` and an `.rpm` to the package repositories, where Porelo finds it. |
| <a id="gh04"></a>GH04 | Renamed Porelo Here | The reader is `org.fuin.porelo:porelo-build-github`, packaged as `porelo-build-github` at `/usr/libexec/porelo/builds/github`, where Porelo looks; a documents test refuses the old word. |
