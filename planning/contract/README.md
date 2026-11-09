# Planning adapter contract

This published [Apache-2.0](../../LICENSE-Apache-2.0) JUnit 5 library supplies `PlanningContract`: extend it with an adapter main class and a `Fixture`, or map a `Board` to files in `planning.git`, `one.git`, and `two.git` with `GitHttpFixture` (override its settings mapping when needed).
The suite exercises Planning1 through `InstalledPlanning`, with independent wire connections for simultaneous writes, isolated home and temporary directories, credential scans, readable containment canaries, bounded calls, and named JUnit skips for unavailable fixture abilities; Git fixtures require Git on `/usr/bin:/bin`, POSIX paths, and a JDK with `jdk.httpserver`.
It is a behavioral contract rather than an adapter or a sandbox: its file-backed reference adapter and five deliberate fault proofs remain in test sources, and process inspection covers only children observable while the suite samples them.
