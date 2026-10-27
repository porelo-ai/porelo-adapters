# Build adapter contract

`porelo-build-contract` is a published JUnit 5 test library. Add it with test scope and extend
`org.fuin.porelo.build.contract.AdapterContract`; the subclass constructor calls
`super(MyAdapter.class, "forge-name", Path.of("src/test/resources/recordings"), "token")`.
`JavaExecutable.write(directory, main)` is also available for tests that need an executable launcher.
It starts the main on the current test class path; the main must accept `serve <socket>`.

The suite starts a fresh adapter with `InstalledBuildReader` for each case and speaks
`org.fuin.porelo.Build2` over an owner-only Unix socket. A loopback HTTP server supplies the forge answers.
The target upstream is `git@forge.example:porelo-ai/porelo-frontend.git`, with a fresh token and the loopback API URL.
The no-repository case names `porelo-ai/no-such-repository-for-the-suite` instead.
All scratch files are under `target/` and are removed after each case.

## Cases and expectations

Each case has its own directory: `head`, `absent-branch`, `no-build`, `queued`, `running`, `passed`,
`failed`, `cancelled`, `logs-none`, `logs-failure`, `logs-all`, `log-tail`, `many-jobs`, `shared-logs`,
`rate-limited`, `primary-rate-limited`, `credential-refused`, `no-repository`, and `unreachable`.
The questions, verdicts, refusals, and answer rules are fixed in the suite. Each recording directory also has
`expect.json` for values evidenced by that forge’s answers; it cannot choose the rules.

The branch is `main` (or `no-such-branch-for-the-suite` for absence). The recorded failed commit is
`e95e5cc23d1b979120b3ca160c40a32514f1bd8b`, the passed commit is
`3b29f5c2cb4f99760a92de863391ed1964e7f179`, and the no-build commit is forty zeroes.
Generated cases use `0123456789abcdef0123456789abcdef01234567`.
The suite compares head and job values with `expect.json` after validating their evidence. It requires the case's
verdict or `BuildRefused.Reason`, and the recorded retry delay for `RATE_LIMITED`. Queued, running, and unknown
answers have no jobs. Final jobs retain forge order, with failed jobs retained ahead of other jobs when the list
exceeds `Build.MOST_JOBS`; the returned list still follows the original order.

The subclass declares its exact authorization scheme: GitHub uses `"Bearer"`, Forgejo uses `"token"`. Every API
request must carry exactly `Authorization: <scheme> <token>`. Every request to another origin must omit
Authorization, including an unrecorded request. The replay uses `127.0.0.1` for the API and `localhost` for the
other origin; scheme, host, and port identify the API origin. Diagnostics never print the token.

`logs-none` returns jobs without any logs and makes no log request; an attempted download is unrecorded and fails.
`logs-failure` carries exactly failed jobs' logs. `logs-all` carries every finished job's log. Each returned log
is the exact recorded suffix, limited to `Build.TAIL`. When the retained tails exceed `Build.MOST_LOG_BYTES`,
each logged job gets at most an equal share of that budget. The suite calculates these limits independently
of `Build`'s constructor. Its boundary cases require a source log over `Build.TAIL`, a failed job beyond
`Build.MOST_JOBS`, and retained tails totaling more than `Build.MOST_LOG_BYTES`, respectively.

## Pull requests

An adapter that opens pull requests overrides `opensPullRequests()` to return `true`. It then has three more
recordings, made on the repository `core/probe` with the branch `porelo/t` and no title given:
`pull-request-opened` (none open, the branch's newest commit read for the title, the pull request opened),
`pull-request-open` (the one open from the branch found, nothing opened) and `pull-request-refused` (a token that may
only read, refused as `CREDENTIAL_REFUSED`). Any other adapter must answer that it cannot, without asking its forge.

## Expected recording values

Every case directory requires `expect.json`. Only the following fields are accepted; a verdict, refusal,
log policy, size limit, or alternative question cannot be supplied here.

- `head`: `head` is the SHA and `headFrom` is its exchange and JSON pointer, for example
  `{"head":"068ad307633718a7b9fdaf5381f9b8d53c3b87ec","headFrom":"01.json#/object/sha"}`.
  The pointed-to body value must equal the full SHA.
- Build cases: `jobs` lists **all** recorded jobs, including those beyond the return limit, in forge order.
  Queued, running, and unknown cases use `{"jobs":[]}`. Each job has `name`, `result`, `nameFrom`, `resultFrom`,
  and, when the question requests that job's log, `log`.
- Rate-limit refusals: `retryAfter` is a nonnegative integer equal to the recorded `Retry-After` header.
  When that header is absent, the suite checks the 60-second fallback. GitHub's primary-limit recording
  deliberately exercises this fallback; Forgejo records an explicit 60-second header.
- Absent branches and other refusals use `{}` because they supply no additional answer values.
- Pull requests: `url` is the pull request's address and `urlFrom` its exchange and JSON pointer, for example
  `{"url":"http://localhost:3000/core/probe/pulls/1","urlFrom":"04.json#/html_url"}`; whether it was opened is
  the case's, not the recording's.

A job example:

```json
{
  "jobs": [{
    "name": "Build / Windows",
    "result": "failure",
    "nameFrom": ["01.json#/workflow_runs/0/name", "02.json#/jobs/2/name"],
    "resultFrom": "02.json#/jobs/2/conclusion",
    "log": "04.json"
  }]
}
```

`nameFrom` holds body JSON pointers to strings joined with ` / `. Its last pointer must name a `name` field
of an object in a recorded job array. `resultFrom` must point to a field in that same job object, whose value
must equal `result`. Array positions and exchange order must agree with `jobs`, and every entry of each
referenced job list must be included exactly once. Thus a missing, invented, duplicated, or reordered job
cannot quietly redefine an expected answer. Forgejo's root job arrays use pointers such as `02.json#/0/name`.
JSON pointer components support `~0` for `~` and `~1` for `/`.

`log` names the exchange whose response body is that job's log, including a blob response after a redirect.
For a repeated exchange file, use `03.json#1`, where the suffix is the zero-based position among its expanded
exchanges. Ordinary files use just their filename. Only loaded exchanges can be referenced: paths cannot
escape the directory or substitute an unrelated local file. Body files and generated bodies are resolved
by the replay before evidence is checked; no digest, generated expected bytes, or job counts are fixed in
the suite. The complete log suffix and its length are compared, so truncation cannot pass by retaining only
a small end marker.

## Exchange files

Files are named `01.json`, `02.json`, and so on (use equal-width numbers) and read in filename order.
Each is one JSON object:

```json
{
  "recorded": false,
  "method": "GET",
  "why": "This example shows a generated redirect.",
  "path": "/repos/porelo-ai/porelo-frontend/actions/jobs/71/logs",
  "status": 302,
  "headers": {"Location": "{{blob}}/log?sig=x"},
  "body": ""
}
```

`method`, `path` (including the exact query order), `status`, and `headers` are required.
`host` defaults to `api`; use `"host": "blob"` for a redirect destination.
Response header values may contain `{{api}}` or `{{blob}}`, replaced by the ephemeral server URL using
`127.0.0.1` or `localhost`, respectively. The server binds only to `127.0.0.1`.
Status `0` closes the connection without an HTTP response; retries of that final exchange are allowed.

Exactly one body form is required: `body` is UTF-8 text (API JSON is an escaped string),
`bodyFile` reads bytes from a file inside the case directory (including after resolving symlinks), or
`bodyBytes` generates 4 through 16,777,216 bytes, ASCII `x` ending in `END\n`, or
`bodyGenerated` builds a list from a template:

```json
{
  "kind": "github-jobs",
  "count": 60,
  "template": {"id": 1, "name": "leg {{id}}", "status": "completed", "conclusion": "success"},
  "lastConclusion": "failure"
}
```

The generated body has `total_count` and `jobs`. It copies the reduced real job template, increments its
positive integer `id`, substitutes `{{id}}` in its name, and optionally changes the last conclusion.
Counts are integers from 1 through 256. Unknown kinds and invalid counts fail before the server starts.

A file may instead contain `repeat` and a nonempty `exchanges` array. The server expands the exchanges
in order for each integer from 1 through `repeat`, replacing `{{id}}` in their string values.
The same 1-through-256 bound applies, and nested repeats are refused. Every expanded request is mandatory;
unused exchanges and token-boundary failures remain errors. `shared-logs` uses one repeated redirect/blob
pair instead of fifty per-job files, alongside its run and generated job list.

Generated bodies avoid committing large logs and make their retained end observable.
An unmatched request gets HTTP 404 and fails the case with its method and path; unused exchanges also fail.

## Recorded and generated cases

Recorded exchanges carry `"recorded": true` and `"source": "github.com, 2026-10-24"`.
They preserve the captured status, `content-type`, rate-limit headers, and log redirect, whose signed
location is replaced by the double's blob URL. JSON bodies retain only fields the adapter reads:
`object.sha`, list `total_count`, run/job `id`, `name`, `status`, `conclusion`, and refusal `message`.
The repository probe's body becomes `{}` because only its status is read. Timestamps, commit metadata,
steps, and URLs are omitted because the adapter does not read them; retained field values are unchanged.

| Cases | Provenance and reason |
|---|---|
| `head`, `absent-branch`, `no-build`, `passed`, `failed`, `logs-failure`, `logs-none`, `credential-refused` | Recorded API responses; failure logs also use the recorded 302 and blob response. |
| `logs-all` | Recorded green run and all nine jobs; the green jobs' logs were not captured, so their redirects and log bodies are generated and marked as such. |
| `no-repository` | Recorded ref 404; the subsequent repository confirmation was not captured and is explicitly generated using the same reduced refusal shape. |
| `queued`, `running`, `cancelled` | These states could not be captured on demand; generated run/job objects use exactly the reduced real objects' fields. |
| `rate-limited`, `primary-rate-limited` | Neither limit could be triggered on demand; generated refusal messages and headers exercise the two retry policies. The primary case omits reset to exercise the deterministic 60-second fallback. |
| `unreachable` | A closed transport has no HTTP response to record. |
| `many-jobs` | The real builds have nine jobs; a generated sixty-job matrix puts the failure beyond the first fifty. |
| `shared-logs` | Fifty generated logs exceed the 2 MiB total budget; the captures do not supply this boundary case. |
| `log-tail` | A deterministic long log exercises multiple read windows and exposes its retained end. |

Every generated file carries `"recorded": false` and a one-line `why`, including files whose API
objects or header shapes came from a real response. Repeated exchanges inherit the enclosing file's provenance.
Generated log downloads keep the real 302 API/200 blob shape and content types.

The failed-job log files contain the final 69,632 bytes of the 383,260-byte public CI log: the expected
64 KiB tail plus a 4 KiB prefix. Each referencing exchange gives byte counts and digests in `reduction`.
The complete capture and retained bytes were checked for `ghp_`, `gho_`, `github_pat_`, and `Bearer `;
all four counts were zero. No credential or signed URL is retained.

## Recording Forgejo

The [Forgejo recorder](https://github.com/porelo-ai/porelo-adapters/blob/main/adapters/forgejo/src/test/record/record-forgejo.sh)
drives podman with Forgejo and forgejo-runner in one pod and a runner on the `host` label. To record a new version,
update the image versions in that script and run it with an empty output directory under `target/`:

```sh
bash adapters/forgejo/src/test/record/record-forgejo.sh target/forgejo-capture
```

The capture contains `index.txt` and each exchange's `.status`, `.headers`, and `.body` files. Review those files
before reducing them to the contract exchange format. Keep the measured response status, content type, and every
field the adapter reads: branch `commit.id`, runs `total_count`, `id`, `workflow_id`, `status`, and job `id`, `name`,
`status`. The jobs endpoint returns a JSON array, and logs return timestamp-prefixed plain text directly.
Only the `message` field distinguishes an unexecuted job's log 404 from other 404s. Repository confirmation
reads only the status, so its body is `{}`. Request repository names and commit selectors are mapped to the
suite's target; response field values and log bytes are retained unchanged.

Forgejo exchange files use `"recorded": true` and
`"source": "Forgejo 17.0.0 in podman, 2026-10-24"`. Generated limits, large matrices, long logs, and transport
failures carry `"recorded": false` and a `why`; generated job lists remain arrays. The fifty-log case exceeds
the 2 MiB total log budget and the long-log case exceeds 2 MiB. The failed job's measured log keeps its last
69,632 bytes, with original and retained byte counts and SHA-256 digests in `reduction`.

For another Forgejo release, set `source` to the version and capture day actually recorded, review the response
shapes and statuses again, and keep uncaptured cases explicitly generated.

Every workflow file present at a commit runs on push. A later push cancels older pending runs, so the capture
named `runs-ok` contains cancelled runs. The recorded all-success case comes from `runs-wait`, which completed
after a runner became available. The long commit includes failure alongside waiting and running workflows;
its measured aggregate verdict is failure. Generated outstanding-only cases isolate queued and running verdicts.

Forgejo uses its recorded head, workflow identifiers, ordered job lists, and direct log bodies in `expect.json`.
It passes the same questions and answer rules as GitHub with its declared `token` authorization scheme.
