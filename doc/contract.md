# Build adapter contract

`porelo-build-contract` is a published JUnit 5 test library. Add it with test scope and extend
`org.fuin.porelo.build.contract.AdapterContract`; the subclass constructor calls
`super(MyAdapter.class, "forge-name", Path.of("src/test/resources/recordings"))`.
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
The expected answers are fixed in the suite; recordings contain only forge exchanges.

The branch is `main` (or `no-such-branch-for-the-suite` for absence). The recorded failed commit is
`e95e5cc23d1b979120b3ca160c40a32514f1bd8b`, the passed commit is
`3b29f5c2cb4f99760a92de863391ed1964e7f179`, and the no-build commit is forty zeroes.
Generated cases use `0123456789abcdef0123456789abcdef01234567`.
Job names, results, log bytes, verdicts, and refusals are compared against expectations fixed in the suite.
The recorded Windows failure's 64 KiB log tail is checked by length and a fixed SHA-256 digest;
the expectations are independent of the exchange files.
Failure logs include a redirect to a distinct host name. The suite checks the bearer token on every API request
and rejects any Authorization header on the blob host, without printing the token.
`logs-none` lists jobs and allows no log request; `logs-failure` downloads only failed jobs;
`logs-all` downloads all nine successful jobs once the verdict is final.
`log-tail` preserves the last 64 KiB; `many-jobs` keeps a failed job beyond the first 50;
`shared-logs` hands over 50 logs with equal shares of the 2 MiB budget.
The two rate-limit cases expect retry delays of 30 and 60 seconds, without depending on wall-clock time.

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
