# Stories-git

The `stories-git` planning adapter reads workflows, stories, issues and listed sub-agent briefs from its own Git clones, using the `issues` capability.
Settings are a JSON object with `repository` (the planning repository's HTTP or HTTPS clone URL), `storiesDirectory` (default `stories`), `issueRepositoryPattern` (a clone URL containing `{repository}`), and `linkPattern` (a web URL containing `{repository}` and `{path}`); `credentialKind` defaults to `token` and also accepts `github-app`.
It runs Git with an isolated home and per-call credentials, refuses symbolic links, and installs as `/usr/libexec/porelo/planning/stories-git` through the `native,dist` profiles.
