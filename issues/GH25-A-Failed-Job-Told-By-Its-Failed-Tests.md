# GH25 — A Failed Job Told By Its Failed Tests

**Status:** Implementing.
**Size:** M - both readers and the contract suite, a log reading of its own, against two optional fields
`porelo-build-api` must add to `Build2` first.

**What must be true.** When a build fails, a person reading it sees for each failed job the tests that failed and a
link that opens the job's whole log on the forge, instead of hundreds of log lines printed into the answer.

## Plan

The contract today (`Build2`): a failed `Job (name, result, log)` carries the last 64 KiB of its log, and Porelo's
status prints it whole, once per run that built the job.

The contract this builds against, agreed with `porelo-build-api`'s owner and added there first, both fields optional
so a reader from before still answers; Porelo keeps an answer to these bounds, so the readers answer within them:
- `Job` gains `failedTests: ?[]string`, each `<class>.<method>` as the test runner names it, at most 50 entries, each
  one line of printable text of at most 200 characters, absent rather than empty; and `url: ?string`, the forge's web
  page of the job, an https URL with a host, no user part, no space or control character, at most 2048 characters.

Today's code: `GitHubReader` and `ForgejoReader` already download a failed job's log (`logs: "failure"`) and keep its
end; the recordings keep only the fields read today, so they hold no job page.

Taken from the plan's reading and from real failed logs of porelo-ai, each with a test seen red first:
- The reading takes each log line without the forge's leading ISO-8601 timestamp, a trailing `\r` and ANSI codes, and
  reads only a whole Surefire or Failsafe result block: `Results:`, then `Failures:` and `Errors:` groups, ended by
  `Tests run:`. A block cut off by the log's end, `Flakes:` entries and an entry's continuation lines are not read.
- An entry `Class.method:line->... » Exception ...` gives `Class.method`; an entry naming a display name with spaces
  and no `:line` gives no test, never a guess. A name is at most 200 printable characters, no whitespace or control
  character, deduplicated in order, at most 50.
- The reading is linear: a line over 4096 characters is skipped before it is looked at, and no backtracking pattern
  runs over a line.
- Only a failed job's log is read for tests, also under `logs: "all"`.
- `url` is the forge's page of the job: GitHub's `html_url`, Forgejo's page of the job built from the run's page,
  each kept only on the forge's own web host; absent when the forge gives none. `FailedTests` lives in the `http`
  module both readers already share, or a module of its own beside it.

Steps:
1. Red first: a recorded failed Maven job's log - a Surefire `Results:` block with `[ERROR]   Class.method:line`
   lines - is answered with exactly those tests and the job's page; a failed job whose log names no test answers no
   `failedTests` and keeps its log as today.
2. The `FailedTests` reading over the job's log end, shared by both readers.
3. Both readers fill `failedTests` and `url`; the contract suite holds both against recordings of both forges, GitHub's
   from a real failed porelo-ai job.

## Acceptance

- The new cases are seen red before the change and green after, for GitHub and Forgejo.
- A log from outside (another build tool, a cut-off log) never invents a test: its case answers none.
- `./mvnw -B -s settings.xml -Pnative,dist verify` green.

## To be checked

- Where each forge names a job's web page, or how it is built from the run and the job: measured while recording.
- That Porelo's status shows the summary and the link instead of the log once the fields come, asked of Agent Core.
