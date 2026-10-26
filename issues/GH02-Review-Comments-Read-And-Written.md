# [GH02](README.md#gh02) — Review Comments Read And Written

**Status:** Analyzed; blocked by porelo [B166](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b166),
[B171](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b171),
[B291](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b291),
[B292](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b292),
[B293](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b293),
porelo-project [PJ66](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj66),
[PJ74](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj74), and
[GH09](README.md#gh09).
**Size:** L - review serving, SARIF conversion, delegation of existing build calls to a separate client's contract,
and shared contract tests cross repository boundaries and require several days.

**What must be true.** The GitHub adapter, `porelo-adapter-github`, serves `Review1` beside `Build2`, reading pull
request reviews, general and anchored comments, threads, resolution and suggestions as Porelo's SARIF 2.1.0 profile.
It converts explicit authorized write actions into calls to `porelo-forge-clients`' `github` module with a write
token supplied for that call; every forge interaction, including builds, goes through that client.

## The shape

- Review data uses `com.contrastsecurity:java-sarif` 2.0 and the shared profile and hardened reader. The adapter maps
  native client records to SARIF; `porelo-forge-clients` knows nothing of Porelo or SARIF and owns every forge API
  call. Root `version` is `2.1.0`, `properties["porelo/profileVersion"]` is `1` as a
  string, and `runs[].conversion.tool.driver` contains the adapter's name and its actual build version.
- Runs carry the configured repository and exact reviewed revision in `versionControlProvenance`, the base revision,
  observed review verdict and completeness in the profile's closed `porelo/*` properties. Comments use
  `kind: "review"`, `level: "none"`, canonical text and optional safe Markdown, observed timestamps and verified
  forge links. A review summary is general review data, without an invented line anchor or duplicate delivery.
- Line anchors preserve path, range, commit and `porelo/diffSide` (`old` or `new`); file anchors have no region and
  general comments have no location. Native comment, review and thread IDs, reply membership, `porelo/threadState`,
  `porelo/outdated` and `porelo/draft` follow the shared profile. Replies remain distinct results; thread membership
  does not collapse them into one `correlationGuid`.
- The client supplies reviews, comments and threads with native IDs, resolved/outdated state and resolver identity.
  Its result records distinguish complete data from cut-off, failed or unknown reads, including comments inside
  threads. The adapter checks those outcomes and emits `partial` or `failed` when evidence is incomplete, never clean.
- Require the client to confine credentials to the configured origin, strip them from cross-origin redirects, bound
  response sizes and pagination, and report cut-offs, permission failures and retry delays explicitly. The adapter
  checks returned scope, revision, capability and completeness information; transport tests belong to the client.
- Authenticated API evidence identifies the forge instance, repository, pull request author, each commenter and
  resolver by stable account ID; GitHub numeric IDs and node IDs are retained where applicable. Account type,
  association, fork origin and membership visibility are evidence, not source levels. The daemon assigns E0, E1,
  I1 or A per item; Git author strings, a login, a bot label or SARIF content confer no authority. It computes C0-C3
  by rules. Observed resolution clears a gate finding only through the daemon's I1 resolver policy.
- Reading uses a review read credential; writing uses a separate credential passed from the vault by Porelo.
  Build-read permissions do not establish review-read permissions. The adapter retains neither token. Replies
  require release by a person unless the daemon permits the project's I1 exception and machine policy allows it.
  Agents submit comments, never approvals; observed verdicts are not write requests.

## Plan

- Consume the API and discovery contract supplied by the dependencies. `GitHubReader` in `adapters/github` currently
  implements `BuildReader` and enters through `BuildMain.run`; wire review serving and capability discovery there
  using the published contract, rather than assuming a review interface already exists. Keep build behavior covered
  by `GitHubContractTest`.
- Consume `porelo-forge-clients`' `github` module. Delegate the existing build calls in `GitHubReader`: read a branch
  head, identify a repository, list build runs for a commit, list their jobs and read a job log. Remove the adapter's
  direct transport dependency; network calls, native decoding and transport configuration belong to the client.
- Require client calls to read a pull request and its revisions, list its reviews, general comments and review
  threads with their comments, and obtain account, resolver and configured membership evidence. Map native records
  and incremental change information in `adapters/github` into fresh validated SARIF objects. Check every returned
  target, anchor and completeness result before delivery.
- Require client calls to submit a batch review, create line/file/general comments, reply, edit/delete a comment,
  and resolve or reopen a thread. Bind target, operation, native IDs, commit and final content to daemon authorization;
  consume its durable retry identity and reconcile ambiguous writes before reposting. A validation refusal is
  reported; do not silently retry an approval as a comment or infer a write from snapshot differences.
- Red first in `GitHubReaderTest` and [GH09](README.md#gh09)'s shared cases: a client double returns old/new anchors,
  distinct replies, a suggestion and thread/resolver identity; assert the resulting SARIF at the adapter boundary.
  Check exact native client arguments and no mutation call for forged IDs or unapproved actions. Extend
  `GitHubContractTest` to exercise discovery and serving, retaining installed-executable protocol coverage.
- Extend `doc/index.md` and `doc/contract.md` with review capabilities, conversion losses, recording evidence and
  measured read/write scopes; validate the native executable under `-Pnative,dist verify` as well as JVM tests.

## Acceptance

- Native client results preserve summaries, general/file/line anchors, old/new ranges, distinct replies,
  suggestions, review verdicts, draft/outdated flags, resolution and stable author/resolver IDs. False, missing and
  default values survive a profile round trip without inventing state. Every guard is seen to fail before the fix.
- Suggestions admit one textual replacement in one existing file, exactly inside its anchored region at the pinned
  revision on the new side. Old-side, outdated, ambiguous or cross-file fixes are held; no suggestion is applied
  automatically and no unsupported anchor is silently moved.
- Shared parser limits apply before allocation and binding, including byte and nesting caps, duplicate-key refusal,
  strict types and no polymorphism. Jackson 2.11 dependencies are overridden by a maintained compatible Jackson
  set. `$schema`, external references, `uriBaseId`, source snippets, artifact contents, invocation details, reactions
  and unlisted property bags do not survive normalization. No report URI triggers a file read or network fetch.
- Canonical prose uses one safe Markdown representation; bidi and zero-width characters are refused. Outgoing user
  and team mentions cannot notify through prose; suggestion code is preserved exactly or held. The native content
  passed to the client remains bound to release and disclosure checks; the client must preserve that content.
- Copied IDs, hidden markers, producer names, suppressions, `baselineState`, missing results or partial pagination
  cannot approve, clear findings, select another account's object or trigger a write. Client-double retry cases
  reconcile an ambiguous successful write without a second create call; scope, rate-limit and permission failures
  are explicit, bounded and contain no credential or body.
- The review contract and existing build contract pass on the JVM and native executable. Adapters make no HTTP
  calls; all build and review operations use the client. Client security or completeness failures remain explicit
  adapter outcomes. Discovery names only review operations actually served.

## To be checked

- Confirm the published review API's method names, transport envelope, authenticated-evidence fields, retry and
  release binding, summary representation and capability discovery. The current `BuildMain` describes only a forge
  and protocol version; `Review1` serving requires the upstream contract work.
- Confirm the client's published native call signatures, record types and capability/failure outcomes for summaries,
  both anchor sides, file comments, nested completeness, edits/deletes, thread state and identity/membership evidence.
  No client class or method name is assumed to exist. Confirm its origin, byte-limit and pagination guarantees and
  documented minimum read/write scopes; do not infer them from build credentials.
- Confirm suggestion fence escaping, mention behavior and serialization defaults against the selected client and
  library versions. Decide exact helper placement and reuse after the shared profile and parser are published.
