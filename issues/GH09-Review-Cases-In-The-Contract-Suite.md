# GH09 — Review Cases In The Contract Suite

```json
{
  "$schema": "issue-1.json",
  "id": "GH09",
  "title": "Review Cases In The Contract Suite",
  "state": "Accepted",
  "size": "-",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": ["GH33"],
  "covers": "Shared `Review1` cases in `porelo-build-contract` guard SARIF fidelity and native client calls, unsupported capabilities and authorized writes without duplicates; blocks [GH02](README.md#gh02)"
}
```

**What must be true.** Every GitHub and Forgejo review adapter passes one published `Review1` contract suite for
Porelo's SARIF 2.1.0 profile, so conversion cannot lose anchors, misattribute a comment, leak a credential or send an
unauthorized or duplicate write. Unsupported operations and incomplete reads are visible outcomes, never successful
approximations or a clean review.

## Proposed

- **Blocked by:** porelo's review contract (`Review1`, and comments on lines sent back to the agent), its forge
  review synchronization, its written SARIF profile, its hardened SARIF reader and its source levels and risk
  classes; by each adapter listing the services it offers, each by contract and version; and
  by `porelo-forge-clients`, which knows nothing of Porelo.
- **Blocked by** [GH33](README.md#gh33) too, the second adapter that makes one suite earn its keep; until then the safety cases are
  the GitHub adapter's own tests.
- **Blocks:** [GH02](README.md#gh02).
- Extend `porelo-build-contract` in `contract` beside the build-only `ContractCase` and `CaseExpectation`. Exercise
  SARIF input/output and native client calls with a `porelo-forge-clients` double where simpler, keeping protocol
  coverage through `AdapterContract` and `JavaExecutable`'s installed-executable approach. Native transport recording
  and loopback-server tests belong to the client repository.
- Cover SARIF round trips/defaults, general/file/line and old/new anchors, pinned revisions, single-region
  suggestions, distinct replies, authenticated author/resolver IDs, draft/outdated state, edits/deletes and client
  reports of nested cut-offs. Assert supported effects or explicit unsupported refusals with no mutation, never
  silent skips.
- Check per-call credential selection, client security-failure outcomes, hostile URIs, forged authority/IDs, copied
  retry markers, unsafe Markdown, mentions, bidi/zero-width characters and parser exhaustion. Excluded evidence
  cannot escape normalization; fixes
  cannot reach another file/region. Suppressions, disappearance, partial reads and observed resolution confer no
  approval. Preserve resolver evidence for the daemon's I1 policy.
- Observe exact client mutation arguments, destinations, commits and release binding, returned backoff and ambiguous
  write outcomes, and reconciliation without a second create call. Snapshots never request mutations. Use shared
  profile/parser fixtures, evidence-backed native records and adversarial cases; see every guard fail first.

## To be checked

- Confirm the published review client, server and discovery contract, capability representation, release/retry
  envelope and profile fixtures. No review client or review suite exists in the current contract module.
- Confirm a client-double injection seam compatible with installed-executable coverage. Obtain native result evidence
  from both clients, including identities, resolver visibility, permission failures, security refusals and cut-off
  reporting. Determine capability-specific expectations without weakening shared rules.
- Measure resource limits and verify compatible Jackson overrides, pre-binding limits and serialized output against
  the shared profile. Define client fault injection that proves an ambiguous successful write reconciles without
  a second create call.
