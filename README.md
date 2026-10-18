# porelo-build-github

<img src="doc/images/early-bird.svg" width="350" alt="Early bird - work in progress">

> **Early bird - work in progress.** Porelo is not stable yet: until release 1.0.0, its code, commands
> and file formats can change without notice.

Porelo's build reader for GitHub Actions: it tells a task in an `online` project what the build of its own push did -
the verdict, the failing job, and the tail of that job's log - without the forge token entering the task.

It is a build reader, not Porelo: it decides nothing about a task and keeps nothing between calls.

**Modules:** [reader](reader/README.md), the reader and its native executable.

**Documentation: [porelo-ai.github.io/build-github](https://porelo-ai.github.io/build-github/)**, one chapter of
[all of Porelo's documentation](https://porelo-ai.github.io).

[Contributing](CONTRIBUTING.md), and [reporting a vulnerability](SECURITY.md).
