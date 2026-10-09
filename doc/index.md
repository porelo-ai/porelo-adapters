# Build reader for GitHub

A task in an `online` project pushes its branch to GitHub, and GitHub Actions builds it somewhere the task cannot
see. This reader is what lets Sokar tell the task how that build went. Sokar finds it installed on the host, asks it
about the commits the task pushes, and hands the answer to the task; the reader never runs inside a task, and the
token it reads GitHub with never enters one.

## Installing it

From the same package repositories as Sokar, on any system Sokar supports (Ubuntu 26.04, Debian 13, Fedora 43
and 44):

```
sudo apt install sokar-build-github      # Debian and Ubuntu
sudo dnf install sokar-build-github      # Fedora
```

The package depends on `sokar` and puts the reader at `/usr/libexec/sokar/builds/github`, where Sokar looks for the
reader a project names with `builds: { forge: github }`; nothing is registered or copied into an account. Whether it
is there and answers:

```
/usr/libexec/sokar/builds/github describe     # {"protocolVersion":1,"forge":"github"}
```

A reader in `~/.local/share/sokar/builds/github` is found first, for one account only; the package needs none.

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
personal access token`. The token is stored in Sokar's vault under the name the project file's `builds.credential`
gives.
