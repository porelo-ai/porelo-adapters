# GH20 — The Stories-Git Adapter Reads The Planning

**Status:** Backlog; blocked by [GH19](README.md#gh19).
**Size:** M - a new native executable with its packages and the reading half of `Planning1`, over a file format
another repository writes.

**What must be true.** Porelo, with `stories-git` chosen in a project's file, shows every story with its state,
priority and issues, every issue with its state, size and blocking issues, and the workflow's states, order and phases
exactly as the project's planning repository holds them, each with the web link that opens it.

## Plan

Read in `adapters/github` (`GitHubReader`, its `native` and `dist` profiles), in Porelo's `BuildMain`
(`describe`, `serve <socket>`), in a story file of the planning repository (its `## Data` JSON head: `id`, `title`,
`state`, `gain`, `test`, `priority`, `issues` as `<repository>#<number>`, `tested`) and in a repository's
`issues/README.md` (one table per state group, each row opening with `<a id="<number>">`, then size and blocked by).

- `planning/stories-git`, artifact `porelo-planning-stories-git`, depending on Porelo's `planning/api` and `wire`
  only, with a `main` as `BuildMain` gives one: `describe` prints `protocolVersion`, `name` `stories-git`,
  `version`, `capabilities` `["write", "watch", "issues"]`, `credentials` `["github-app", "token"]`;
  `serve <socket> <directory>` answers `Planning1`, in porelod's environment.
- Settings, passed through unread by Porelo: the planning repository's clone URL, the stories directory, the link
  pattern (`https://github.com/porelo-ai/{repository}/blob/main/{path}`) and the owner the issue references resolve
  in.
- The store is the adapter's own clone of the planning repository and of each repository a story's issues name, under
  the directory `serve` names, its own per project; it writes nowhere else, never in a person's checkout.
  `revision` names every clone's head commit, so a write can tell when any of them moved.
- `GetWorkflow` reads `stories/workflow.yml` in the planning repository: the states in board order, each
  `{name, closed: true|false}`, and the phases `{name, states}`. Which states start work and which state ends it are
  Porelo's, not the file's. A story or issue whose state the file does not name is refused, saying which.
- `ListStories(states)`, `GetStory`, `ListStoryIssues`, `GetIssue` parse the heads and the indexes; an issue's state
  is its index group, its title the row's text, its `whatMustBeTrue` the issue file's paragraph of that name.
- `GetIssue` also gives `subAgents[]`: each entry of the issue's `## Sub-agents` list (name, kind `builder`, `tester`
  or `reader`, proposed agent and model) with the text of its brief, `issues/<ID>.sub/<name>.md` beside the issue
  (`issues/<group>/<ID>.sub/` where a repository groups its issues). A listed brief that is missing, or that is a
  symbolic link or lies outside `<ID>.sub/`, makes `GetIssue` fail naming it; a file in `<ID>.sub/` the list does not
  name is never delivered, since Porelo starts an agent with whatever brief it gets.
- `native` and `dist` profiles as the GitHub adapter's: `target/porelo-planning-stories-git`, a `.deb` and an `.rpm`
  installing it as `/usr/libexec/porelo/planning/stories-git`, checked for `x86-64` and its library floors.
- Its test extends [GH19](README.md#gh19)'s suite with a fixture of bare repositories made from a story and an index
  as the planning repository writes them. Red first: a story whose state the workflow file lacks is answered instead
  of refused.

## Acceptance

- The contract suite's read cases green against the native executable, and each seen red once with the parser broken
  on purpose.
- Against a clone of the planning repository at a fixed commit, `ListStories` names every story the stories index
  names, in the same state.
- `-Pnative,dist verify` builds both packages; `dpkg -c` shows `/usr/libexec/porelo/planning/stories-git`.

## To be checked

- That the stories index of `porelo-release` reads the same `stories/workflow.yml` instead of its built-in states,
  asked of Agent Machines.
- Whether reading a private repository's index needs the credential too, or the clones of public ones go without it.
