# Build adapters

A task in an `online` project pushes its branch to GitHub, and GitHub Actions builds it somewhere the task cannot
see. This reader is what lets Porelo tell the task how that build went. Porelo finds it installed on the host, asks it
about the commits the task pushes, and hands the answer to the task; the reader never runs inside a task, and the
token it reads GitHub with never enters one.

## Installing it

From the same package repositories as Porelo, on any system Porelo supports (Ubuntu 26.04, Debian 13, Fedora 43
and 44):

```
sudo apt install porelo-adapter-github      # Debian and Ubuntu
sudo dnf install porelo-adapter-github      # Fedora
```

The package depends on `porelo` and puts the reader at `/usr/libexec/porelo/builds/github`, where Porelo looks for the
reader a project names with `builds: { forge: github }`; nothing is registered or copied into an account. Whether it
is there and answers:

```
/usr/libexec/porelo/builds/github describe     # {"protocolVersion":2,"forge":"github"}
```

A reader in `porelo/builds/github` under the account's own data directory is found first, for one account only; the
package needs none.

## What it answers

- **The head of a branch**: the commit the task last pushed, as GitHub has it.
- **A commit's build**: queued, running, success, failure, cancelled or unknown, across every workflow run of that
  commit, with the failing job's name and the last 64 KiB of its log.
- **Each run of that commit**: the workflow's name and its conclusion, or its state while it has none, the first fifty
  in GitHub's order, also while the build is queued or running.
- **Why it cannot answer**, as distinct cases: a rate limit, a credential GitHub refused, a repository it cannot see.

## The token it reads GitHub with

A fine-grained token, with access to the repositories the tasks push to, and two repository permissions:

- **Actions: Read**, for each commit's runs, their jobs and the logs of the jobs.
- **Contents: Read**, for where a branch points. A token given Actions alone is refused here with 403. The head is
  asked of the branch rather than taken from the newest run, because a push GitHub starts no run for would never be
  seen otherwise, and its "no build" never said.

A refused token is reported with GitHub's own message and what was asked, such as
`GitHub refused this credential /repos/<owner>/<name>/git/ref/heads/<branch> (403): Resource not accessible by
personal access token. A build reader needs Actions: Read and Contents: Read on <owner>/<name>`. The token is stored in Porelo's vault under the name the project file's `builds.credential`
gives.

## A pull request's review

Beside the build, the GitHub adapter serves one person's review of a pull request in Porelo's review profile 2:

- **A pull request's facts**: its repository, head, base, merge base, state, and whether GitHub counts it approved at
  its head: every reviewer's last verdict on that commit is an approval. Once merged, also its merge commit and the
  branch it went onto; once merged or closed, when.
- **The open pull request of a branch**, if there is one; it never opens one.
- **Its conversation, page by page**: each line comment and each reply as one result, written by its GitHub account,
  at most 100 results and 1 MiB a page. A thread is named by its first comment's REST ID.
- **One released comment or reply**, written with exactly one call and never repeated: when GitHub's answer is
  lost, the answer is `WriteOutcomeUnknown`, and Porelo decides.
- **One thread resolved**, when Porelo asks: the thread named by its first comment's REST ID reads resolved on
  GitHub afterwards, under the account the token stands for, whoever opened it. A thread already resolved is done
  without a write, a thread the pull request does not hold is `NoSuchThread`, and a lost answer is
  `WriteOutcomeUnknown`.

Resolving is the only state of a thread it changes: it never approves, requests changes, reopens a thread or
merges. What profile 2 cannot carry is left out and the
page says `partial`: a comment on a file or an outdated line, a span over 100 lines, a text over 8192 bytes or with a
character the profile refuses. A submission asking for what GitHub cannot write, such as a general comment, is
refused with `NotInProfile` before any call, and so is one that names threads to resolve or carries a judgment:
a thread is resolved only when Porelo asks for that thread, and a judgment never reaches GitHub.

The token is lent for each call and kept by neither side. It needs **Pull requests: Read and write** for the
conversation, the comments and resolving a thread, and **Contents: Read** for the merge base.

## Forgejo Actions

Install `porelo-adapter-forgejo` from the same package repository as Porelo. It provides
`/usr/libexec/porelo/builds/forgejo`; `describe` answers
`{"protocolVersion":2,"forge":"forgejo"}`. The package has no replacement relationship with the GitHub adapter.

A project's configuration names the forge and its API explicitly:

```yaml
builds:
  forge: forgejo
  api: https://<host>/api/v1
  credential: forgejo
```

Forgejo has no public default API address. For a Forgejo on this machine, such as porelo-host's
[local forge](https://github.com/porelo-ai/porelo-host/blob/main/doc/local-forge.md), Porelo also accepts
`api: http://127.0.0.1:<port>/api/v1` on the host's loopback interface; Porelo's
[ways of working](https://github.com/porelo-ai/porelo/blob/main/doc/way-of-working.md) say when to use one. The token
is held in Porelo's vault and passed to the adapter in each call, as `Authorization: token <token>` on API requests.
Redirects are refused; credentials never travel to a redirect destination. Public repositories can allow anonymous
reads, although a configured credential still accompanies the adapter's requests.

Like Porelo, the adapter trusts `porelo/ca-certificates.pem` in the account's configuration directory in addition to the
public certificate roots. An absolute `XDG_CONFIG_HOME` selects that directory instead of the default in the home directory.

The adapter reads each workflow's single status: failure takes precedence over outstanding runs, then
cancellation, then success. `waiting` and `blocked` are queued, `running` is running, and `success` and `skipped`
are successful. Unrecognized statuses produce an unknown verdict naming the value. Jobs are named
`<workflow_id> / <job name>` in Forgejo's order. Every answer but an unknown one names each run by its
`workflow_id` with its status, in Forgejo's order. A job that never executed has no log; this does not make the
repository absent. Log tails, job counts, and the total log budget follow the build API's limits.

### A pull request's review on Forgejo

Beside the build, the Forgejo adapter serves the same review contract, with what Forgejo's API holds:

- **A pull request's facts**, as on GitHub. It counts as approved at its head when every reviewer's last submitted
  verdict is an approval of that commit, neither dismissed nor stale; a review list longer than one call reads
  counts as not approved.
- **The open pull request of a branch** of the same repository, never one from a fork; it never opens one.
- **Its conversation, page by page**: each line comment of a submitted review as one result, a comment on the same
  file, side and line as the thread's first one counted as its reply. A thread is named by its first comment's ID
  and reads resolved when Forgejo shows who resolved it. A pending review is the reading account's own and is left
  out; a comment that names both sides, or neither, is left out and the page says `partial`.
- **One released single-line comment**, written as one review with exactly one call and never repeated: when
  Forgejo's answer is lost or cannot be read, the answer is `WriteOutcomeUnknown`, and Porelo decides.

Forgejo's API cannot answer a comment in its thread, comment on several lines at once, propose a change or resolve
a conversation. A reply, a region over more than one line or a `suggestion` is refused with `NotInProfile` before
any call, and `Resolve` answers `ResolveUnsupported` without a request.

The review contract names a repository by https only, so the API must be `https://<host>[:<port>][/<prefix>]/api/v1`
and the pull request's address must lie on the same host and prefix; anything else is refused before a request,
and a Forgejo on plain http serves builds but no review. The token needs **read:repository** and **read:user** to
read, since the facts name the account the token stands for, and **write:repository** to write; a token missing a
scope is answered `CredentialRefused`, any other refusal `Failed` with Forgejo's own words.

See the [contract documentation](contract.md) for recordings and tests.
