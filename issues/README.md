# Issues

The open tasks of `porelo-adapters`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `GH`. A group with nothing in it is
left out.

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="gh09"></a>[GH09](GH09-Review-Cases-In-The-Contract-Suite.md) | - | [GH33](README.md#gh33) | Shared `Review1` cases in `porelo-build-contract` guard SARIF fidelity and native client calls, unsupported capabilities and authorized writes without duplicates; blocks [GH02](README.md#gh02) |
| <a id="gh13"></a>[GH13](GH13-Mutation-Testing-With-PIT.md) | - | - | A `pit` profile run by hand, with PIT and its JUnit 5 plugin pinned, and a report sorting the surviving mutants into real gaps and harmless ones |
| <a id="gh32"></a>[GH32](GH32-The-Forgejo-Adapter-Serves-The-Reviews-Minimum.md) | - | [GH10](README.md#gh10) | One person's review on Forgejo: `Review1` beside `Build2` with a pull request's facts, one page of comments and one released comment written, as on GitHub; waits for porelo's `Review1` description and a two-interface `VarlinkServer` |
| <a id="gh33"></a>[GH33](GH33-The-Forgejo-Adapter-Serves-Every-Review-Operation.md) | - | [GH32](README.md#gh32) | Forgejo serves every review operation beside `Build2` through the `forgejo` client - edits, deletes, reconciled retries, the hardened SARIF reader and the shared contract cases - and `ForgejoReader`'s own HTTP calls move onto the client |

## Analyzed

| # | Size | Blocked by | What it covers | Open questions |
|---|---|---|---|---|
| <a id="gh11"></a>[GH11](GH11-The-Adapters-Agent-Works-In-A-Porelo-Task.md) | S | - | porelo-adapters' agent works in a Porelo task on the move's host as `Agent Adapters`; first its workflows' publishing, `gh` and artifact upload held to `PORELO_PUBLISH` and the forge's registry read, with a workflow test | 0 |
| <a id="gh23"></a>[GH23](GH23-The-Tests-Follow-The-Pyramid.md) | L | - | The tests follow the pyramid: the readers' mapping, the recordings' rules, the log tail, the trust choice, the review writer and the stand-in proven by unit tests over fakes, one narrow integration test each, and the workflows' inline logic in a tested command | 0 |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="gh14"></a>[GH14](GH14-Stand-In-Says-A-Short-Thread-Is-Complete.md) | XS | - | The stand-in counts a run as complete although a review thread's comments were not read to their end |
| <a id="gh26"></a>[GH26](GH26-Contract-Cases-That-Cannot-Pass-Empty.md) | M | - | The published build contract suite refuses an adapter that drops a recorded run's jobs, or answers no trigger or failed test in the cases that hold them |
| <a id="gh17"></a>[GH17](GH17-The-GitHub-Adapter-Serves-The-Reviews-Minimum.md) | M | [GH14](README.md#gh14) | One person's review on GitHub: `Review1` beside `Build2` with a pull request's facts, one page of comments and one released comment written, a token lent per call and no way to a verdict; waits for porelo's `Review1` description and a two-interface `VarlinkServer` |
| <a id="gh15"></a>[GH15](GH15-An-Overview-Of-Forge-Functions.md) | S | - | One documentation page says per function and forge what the forge offers and what the adapter does today, each cell no more than its marked source |
| <a id="gh16"></a>[GH16](GH16-The-Old-GitHub-Package-Leaves-The-Channels.md) | S | - | `porelo-build-github` leaves both channels, gone from `snapshots` with `releases` left for after the first released adapter, so `apt install 'porelo*'` installs and updates every package, on a host with the old package too |
| <a id="gh10"></a>[GH10](GH10-Forgejo-Reviews-Converted-Into-The-Profile.md) | M | - | A Forgejo pull request's reviews and comments read into Porelo's SARIF profile, and one released result turned into the `forgejo` client's calls, over the client's records, sharing the forge-neutral review code with the GitHub adapter |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="gh02"></a>[GH02](GH02-Review-Comments-Read-And-Written.md) | L | [GH17](README.md#gh17), [GH09](README.md#gh09) | GitHub serves `Review1` beside `Build2` as SARIF 2.1.0 through the `github` client, preserving thread and origin evidence and sending explicit authorized writes with per-call credentials |

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="gh29"></a>[GH29](GH29-The-Planning-Modules-Leave.md) | S | - | The `planning/` module group - `stories-git`, the planning contract suite, their docs, tests and changelog entries - leaves the repository, and nothing left names it |
| <a id="gh30"></a>[GH30](GH30-The-Packages-Carry-The-Notices-Of-What-They-Compile-In.md) | S | - | Each adapter's .deb and .rpm carry the license and notice texts of every third-party library and of the Java runtime compiled into its native executable, generated by the build |
| <a id="gh31"></a>[GH31](GH31-The-Build-Takes-Its-Downloads-From-A-Cache.md) | S | - | The Build fetches nothing from outside that a cache could hold, `rpm` included, and no cache keeps a superseded entry past seven days |
| <a id="gh24"></a>[GH24](GH24-Each-Run-Named-By-Its-Trigger-With-Its-Jobs.md) | L | - | Each run of a commit named by its workflow and its trigger (push, pull request), queued, running or finished, every job under its run, and never no run while one is outstanding; needs two optional `Build2` fields |
| <a id="gh25"></a>[GH25](GH25-A-Failed-Job-Told-By-Its-Failed-Tests.md) | M | - | A failed job answered with its failed tests and a link to its log page instead of the log printed whole; needs two optional `Build2` fields |
| <a id="gh18"></a>[GH18](GH18-Every-Run-Of-A-Commit-In-The-Build-Answer.md) | M | - | Every `Build2.Build` answer of the GitHub and Forgejo readers carries `runs`, one name and result per run of the commit, queued and running too, at most 50 in the forge's order; the contract suite and its recordings hold it; waits for `porelo-build-api`'s `runs` |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="gh05"></a>GH05 | Publish Without A Downloaded Client | `Publish` speaks to Artifactory with inline `curl`, the token on standard input, each upload carrying its checksums, and no client downloaded beside the token. |
| <a id="gh12"></a>GH12 | A Stand-In For The Daemon's Review Calls | `standin/`, a test-only tool never shipped, serves the daemon's `Tasks1` review calls on a unix socket from real GitHub pull requests and eleven fixture pushes, through the forge clients and the GitHub-SARIF conversion in `adapters/github`, with the daemon's decisions as marked fixed values; an IDE's review display listed and opened every fixture push. |
| <a id="gh08"></a>GH08 | The Forgejo Adapter Trusts The Account's Authorities | `Http.overNetwork()` trusts `porelo/ca-certificates.pem` in the account's configuration directory beside the built-in roots, so a reader reaches a forge signed by the host's own authority and still one signed by a public one |
| <a id="gh06"></a>GH06 | One Contract Suite Every Adapter Passes | `porelo-build-contract` holds one suite of questions, answer rules and generated boundary cases with evidence-checked recordings per forge, and the GitHub reader passes it over `Build2` as `porelo-adapter-github`, replacing `porelo-build-github`. |
| <a id="gh07"></a>GH07 | The Forgejo Adapter Answers As The GitHub One | `porelo-adapter-forgejo` reads Forgejo Actions over `Build2` and passes the same contract suite with recordings made from Forgejo 17 in podman. |
| <a id="gh03"></a>GH03 | The Code Read Against The Skills It Names | `reader` read against the five skills `AGENTS.md` names, each finding fixed with a test seen red or declined. |
| <a id="gh01"></a>GH01 | Release The Reader As A Package | A tag and `main` publish the reader as a `.deb` and an `.rpm` to the package repositories, where Porelo finds it. |
| <a id="gh04"></a>GH04 | Renamed Porelo Here | The reader is `org.fuin.porelo:porelo-build-github`, packaged as `porelo-build-github` at `/usr/libexec/porelo/builds/github`, where Porelo looks; a documents test refuses the old word. |

## Rejected

| # | Title | Why |
|---|---|---|
| <a id="gh19"></a>GH19 | A Contract Suite For Planning Adapters | Planning is no adapter process and has no `Planning1` contract any more; it moves into the app `porelo-planner`, which takes the Git HTTP test server and the secret scan as its own test helpers. |
| <a id="gh20"></a>GH20 | The Stories-Git Adapter Reads The Planning | Reading the planning moves into the app `porelo-planner`, with `stories-git`'s code as its model. |
| <a id="gh21"></a>GH21 | The Stories-Git Adapter Sets States | Setting states moves into the app `porelo-planner`. |
| <a id="gh22"></a>GH22 | The Stories-Git Adapter Tells Changes | Telling changes made elsewhere moves into the app `porelo-planner`. |
