# Planning contract fixtures

This published Apache-2.0 test library provides `GitHttpServer`, `GitRepositories`, `Canary`, and `SecretScan` for planning adapter tests.
It requires Git on the path and a JDK with `jdk.httpserver`; fixture owners close servers and repositories before removing their temporary directories.
It has no dependency on the planning API; the [Apache license](../../LICENSE-Apache-2.0) covers this module.
