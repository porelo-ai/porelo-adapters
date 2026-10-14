# GH03 — The Code Read Against The Skills It Names

**Status:** Analyzed.
**Size:** S - one module, `reader` (three classes, ~390 lines, ~250 of tests), built as a native image, read against
five skills; one commit.

**What must be true.** The code of this repository has been read against the skills `AGENTS.md` names, and each
finding is either fixed with a test seen to fail first, or declined with the reason written here.

## Why

`AGENTS.md` has named five skills since the repository was made public, and none of its code was read against them.
The reader holds a forge token and reads answers from another host, which is where such a reading found the most in
the other repositories.

## Plan

**The skills, read against these versions** (from `https://fuinorg.jfrog.io/artifactory/agent-skills/`, each zip
checked against its SHA-256 before it is unpacked; a newer version taken while this is open is named in the commit):

    graal               2026.910.3  b24ec6272fb13490e32977c21d4543869a7e85ed474fdc0ff34022fbddd88bf6
    java-code-review    2026.906.3  802f86339442ed9fa9d5bca14b6d16e1f6420ae92a047b18bba11239e32c698b
    test-quality        2026.906.3  9c5d23ad7a6d9c6f5c3679f3cc44a7fcc2fb3cc1c0f0d0ba4a2c0f7812428bad
    security-audit      2026.906.3  a46fd1be07bd539b955291537dea0f24f65a2f8b15d40d50a6a9098bbd992747
    clean-code          2026.906.3  5cf777a24630298b4b801c934f599722978c21aeac53d61683243908fc041c7b

**What is read:** `reader/src/main` (`GitHubReader`, `Http`, `package-info`) and `reader/src/test`, and its
`pom.xml`; `security-audit` first - the token, where it is sent, what of an answer is believed - then `graal` for the
native build (metadata, class initialization, resources), then the rest. The findings are listed here: fixed (with
the test seen red) or declined (with why). A finding is a guess until a failing test reproduces it.

## Acceptance

- The skills read against, their versions and the commit are listed here.
- Every finding is fixed with a test seen to fail without the fix, or declined with the reason written here.
- The reactor, the native build and the shared checks are green on that commit.
