# Contributing

Changes to porelo-build-github follow the project's guide: how an issue is written, how a change is proposed and
reviewed, and what a commit carries. It is one page for every Porelo repository:
[Contributing to Porelo](https://porelo-ai.github.io/porelo-project/contributing/).

## In this repository

- **Building and testing:** JDK 25, the pinned GraalVM. `./mvnw -B verify` runs the tests, and `-Pnative` builds the
  executable, as [reader/README.md](reader/README.md) says.
- **The contract** it answers is porelo's build reader interface, so a change to what it answers is made with porelo.
- **Requirements** are in [issues/README.md](issues/README.md).
