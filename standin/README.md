# Review stand-in

A test-only tool: it serves the daemon's `org.fuin.porelo.Tasks1` review calls on a unix socket from real GitHub pull
requests and from fixture pushes, so an IDE's review display can be tried before the daemon serves those calls. It
is not Porelo's daemon and never ships: no package, no publication, no deployment, and it is deleted once the daemon
serves the calls.

- What only the daemon decides - source level, risk class, approvals, completeness of its own runs, holding and
  release - it answers with fixed values, each marked as the stand-in's. Nothing it answers clears a finding or
  approves anything; `Approve` and `Reject` send nothing anywhere.
- It reads and writes GitHub only through `porelo-forge-client-github`, converting with the GitHub adapter's review
  conversion. The token comes from the environment variable `PORELO_STANDIN_GITHUB_TOKEN` and reaches no answer, log
  line, file or command line.
- Run from the repository root:
  `./mvnw -B -s settings.xml -pl standin -am -q install -DskipTests` once, then
  `./mvnw -B -s settings.xml -pl standin exec:java -Dstandin.args="--socket <dir>/porelod.sock --repository <owner>/<repo> --pull <n> --state <dir>"`.
  The socket's directory must be the caller's own, mode 0700. `--fixtures` adds the fixture pushes.
