# Issues

The open tasks of `sokar-build-github`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `GH`. A group with nothing in it is
left out.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="gh01"></a>[GH01](GH01-Release-The-Reader-As-A-Package.md) | S | - | a tag and `main` publish the reader as a `.deb` and an `.rpm` to the indexes, where `sokar` finds it |

## Analyzed

| # | Size | Blocked by | What it covers | Open questions |
|---|---|---|---|---|
| <a id="gh02"></a>[GH02](GH02-Review-Comments-Read-And-Written.md) | M | `sokar` [B171](https://github.com/sokar-ai/sokar/blob/main/issues/base/README.md#b171) | `Review1` beside `Build1`: a pull request's review comments, threads and suggestions read into `sokar`'s comment model, and comments and replies written with a write token of their own | - |
