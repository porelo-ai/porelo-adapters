# [GH12](README.md#gh12) — A Stand-In For The Daemon's Review Calls

**Status:** Accepted.

**What must be true.** An IDE's review display can be tested against a real GitHub pull request before Porelo's daemon
serves its review calls: a test-only tool, never shipped, serves on a unix socket the daemon's `Tasks1` calls that the
review display uses - the waiting pushes and a push's review with their level, class and per-push counts, a run's
comments as Porelo's SARIF review profile, a new comment or reply, and resolving or reopening a thread - reading and
writing that pull request through the forge clients, converting GitHub and SARIF in both directions. What only the
daemon decides - source level, risk class, completeness, holding and release - it answers with fixed values, each
plainly marked as a stand-in's. It is deleted once the daemon serves those calls.

## Why

The review display's calls are specified before the daemon implements them; testing the display against a real
forge then finds what the contract and the conversion miss early, on the GitHub side that the GitHub adapter's review
work needs anyway.
