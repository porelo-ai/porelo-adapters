# Build contract

This published test library provides `AdapterContract`, a JUnit 5 suite that starts an adapter's main through `JavaExecutable` and `InstalledBuildReader`, with expectations owned by the suite.
An adapter's test extends it and supplies its main class, forge name, and recordings directory, whose case subdirectories contain ordered `NN.json` exchanges or repeated exchange groups.
See the [recording format and cases](../doc/contract.md) for host names, generated bodies, and connection failures.
