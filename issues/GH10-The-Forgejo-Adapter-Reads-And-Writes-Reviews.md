# [GH10](README.md#gh10) — The Forgejo Adapter Reads And Writes Reviews

**Status:** Idea.

**What must be true.** The Forgejo adapter, `porelo-adapter-forgejo`, serves `Review1` beside `Build2`, reading and
writing pull request reviews, line and general comments, threads and suggestions as Porelo's SARIF 2.1.0 profile.
It delegates every forge call, including builds, to `porelo-forge-clients`' `forgejo` module, preserves authenticated
origin and conversion limits, and holds unsupported writes without changing their anchor or meaning.

## Proposed

- **Blocked by:** porelo's review contract (`Review1`, and comments on lines sent back to the agent), its forge
  review synchronization, its written SARIF profile, its hardened SARIF reader and its source levels and risk
  classes; by each adapter listing the services it offers, each by contract and version; and
  by `porelo-forge-clients`, which knows nothing of Porelo; and by [GH09](README.md#gh09).
- **Blocks:** no separate downstream issue identified; supplies Forgejo review support.
- Extend `ForgejoReader`'s `BuildReader`/`BuildMain` entry with published review serving and discovery. Consume the
  `forgejo` client's native records and the shared hardened `com.contrastsecurity:java-sarif` 2.0 binding, profile
  version and conversion name/build version. The client knows nothing of Porelo or SARIF; adapters make no HTTP calls.
- Require client calls to read a pull request and revisions, list reviews, general comments and review threads with
  their comments, submit a batch review, create/reply/edit/delete comments and obtain account/resolver/membership
  evidence. Ask for resolve/reopen operations only when the client reports support. Preserve native old/new position
  semantics in SARIF; scope inferred grouping to instance, repository, pull request and revision without inventing
  parent IDs. Summaries are general data; preserve verdict observations, IDs, timestamps and completeness.
- Delegate `ForgejoReader`'s existing branch-head, repository, build-run, job and job-log calls to the client and
  remove its direct transport dependency. Require client guarantees for credential stripping across origins,
  bounded responses and explicit pagination cut-offs; check returned scope, revision, capability, completeness and
  failure outcomes before emitting SARIF. Client transport tests establish those guarantees.
- Return authenticated, instance-scoped stable PR/comment/resolver identities and membership/fork evidence;
  missing visibility stays unknown. The daemon assigns E0/E1/I1/A per item and rule-based C0-C3. Report fields,
  logins and Git author strings confer no authority; observed resolution is not suppression or gate acceptance.
- Declare thread resolution, file anchors and multi-line ranges unsupported unless the client confirms support for
  the configured Forgejo version. Hold unsupported writes without moving anchors. A suggestion is one replacement
  within its exact new-side anchored region of one existing file and is never applied automatically.
- Pass separate per-call read/write credentials to the client. Bind explicit operations, destination,
  native IDs, revision and final body to daemon release/retry authorization; reconcile ambiguous writes. Replies
  default to release by a person with the permitted I1 exception; agents never approve and copied markers confer
  no ownership. Apply the shared parser limits, closed fields, URI policy, safe Markdown, mention handling and
  bidi/zero-width refusal. Suppressions, missing results and partial reads never make a review clean.
- Extend `ForgejoReaderTest` and `ForgejoContractTest` with [GH09](README.md#gh09)'s adapter-boundary cases using native
  client doubles and evidence-backed records; see guards fail first. Reuse shared SARIF security rules without
  depending on GitHub's client or adapter implementation.

## To be checked

- Confirm the client's published calls, native records and evidence for reply/grouping rules, position asymmetry,
  completeness and supported operations on the configured Forgejo version, including resolver and outdated state.
  Do not assume client class or method names before that contract is published.
- Confirm stable PR/comment/resolver identities, membership and fork evidence, exact read/write scopes, unknown
  evidence representation and least privilege. Verify suggestion fences, mention behavior and native retry behavior.
- Confirm the upstream review discovery, authorization and retry contracts, client origin/size/cut-off guarantees and
  a client-double seam for the shared contract suite. Both forge adapters depend on their own client module, not on
  transport work in the other adapter.
