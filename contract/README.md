# Build contract

This published test library provides `AdapterContract`, a JUnit 5 suite that starts an adapter's main through `JavaExecutable` and `InstalledBuildReader`, with questions and answer rules owned by the suite.
An adapter's test extends it and supplies its main class, forge name, recordings directory, and authorization scheme; case subdirectories contain ordered `NN.json` exchanges or repeated exchange groups and an evidence-checked `expect.json`.
See the [recording format and cases](../doc/contract.md) for host names, generated bodies, and connection failures.
