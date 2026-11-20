# GH36 — The Task Image Takes The Current GraalVM Pin

```json
{
  "$schema": "issue-1.json",
  "id": "GH36",
  "title": "The Task Image Takes The Current GraalVM Pin",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 3,
  "blockedBy": [],
  "covers": "The task image in `porelo.yml` installs GraalVM 25.5.5.0.3, the pin `porelo-machines` holds, instead of the stale 25.4.4.1.1"
}
```

**Why this size.** One file, `porelo.yml`: a tag, a file name and a digest; no contract.

**What must be true.** A Porelo task of this repository builds with the GraalVM that the workflows install, so a
native image built in a task and one built in CI come from the same compiler.

## Plan

- `porelo.yml` installs GraalVM by hand at `graal-25.4.4.1.1` with that archive's SHA-256, while the workflows take
  the pin from `porelo-machines` (`machines.graalvm.url` and `.sha256`), which now names 25.5.5.0.3.
- Set the tag, the archive name and the SHA-256 to what `porelo-machines`' `machines/pom.xml` names for 25.5.5.0.3,
  copied from there, never retyped from a release page.
- Red first: the image build with a deliberately wrong digest fails at `sha256sum -c`; with the copied digest it passes.

## Acceptance

- The task image built from `porelo.yml` reports `java -version` 25.0.5.0.3, GraalVM CE 25.5.5.0.3.
- `porelo.yml` names no other GraalVM version.
