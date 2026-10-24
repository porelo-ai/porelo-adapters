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

A reader in `~/.local/share/porelo/builds/github` is found first, for one account only; the package needs none.

## What it answers

- **The head of a branch**: the commit the task last pushed, as GitHub has it.
- **A commit's build**: queued, running, success, failure, cancelled or unknown, across every workflow run of that
  commit, with the failing job's name and the last 64 KiB of its log.
- **Why it cannot answer**, as distinct cases: a rate limit, a credential GitHub refused, a repository it cannot see.

## The token it reads GitHub with

A fine-grained token, with access to the repositories the tasks push to, and two repository permissions:

- **Actions: Read**, for each commit's runs, their jobs and the logs of the jobs.
- **Contents: Read**, for where a branch points. A token given Actions alone is refused here with 403. The head is
  asked of the branch rather than taken from the newest run, because a push GitHub starts no run for would never be
  seen otherwise, and its "no build" never said.

A refused token is reported with GitHub's own message and what was asked, such as
`GitHub refused this credential /repos/<owner>/<name>/git/ref/heads/<branch> (403): Resource not accessible by
personal access token`. The token is stored in Porelo's vault under the name the project file's `builds.credential`
gives.

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

Forgejo has no public default API address. For a Forgejo on this machine, Porelo's next release also accepts
`api: http://127.0.0.1:<port>/api/v1` on the host's loopback interface. The token is held in Porelo's vault and
passed to the adapter in each call, as `Authorization: token <token>` on API requests. Redirects are refused;
credentials never travel to a redirect destination. Public repositories can allow anonymous reads, although
a configured credential still accompanies the adapter's requests.

The adapter reads each workflow's single status: failure takes precedence over outstanding runs, then
cancellation, then success. `waiting` and `blocked` are queued, `running` is running, and `success` and `skipped`
are successful. Unrecognized statuses produce an unknown verdict naming the value. Jobs are named
`<workflow_id> / <job name>` in Forgejo's order. A job that never executed has no log; this does not make the
repository absent. Log tails, job counts, and the total log budget follow the build API's limits.

See the [contract documentation](contract.md) for recordings and tests.
