# Issues

The open tasks of `porelo-build-github`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `GH`. A group with nothing in it is
left out.

## Analyzed

| # | Size | Blocked by | What it covers | Open questions |
|---|---|---|---|---|
| <a id="gh02"></a>[GH02](GH02-Review-Comments-Read-And-Written.md) | M | `porelo` [B171](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b171) | `Review1` beside `Build1`: a pull request's review comments, threads and suggestions read into `porelo`'s comment model, and comments and replies written with a write token of their own | - |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="gh03"></a>GH03 | The Code Read Against The Skills It Names | `reader` read against the five skills `AGENTS.md` names, each finding fixed with a test seen red or declined. |
| <a id="gh01"></a>GH01 | Release The Reader As A Package | A tag and `main` publish the reader as a `.deb` and an `.rpm` to the package repositories, where Porelo finds it. |
| <a id="gh04"></a>GH04 | Renamed Porelo Here | The reader is `org.fuin.porelo:porelo-build-github`, packaged as `porelo-build-github` at `/usr/libexec/porelo/builds/github`, where Porelo looks; a documents test refuses the old word. |
