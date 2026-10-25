# GH08 — The Forgejo Adapter Trusts The Account's Authorities

**Status:** Implementing; built, unit and native contract suite green on the host.
**Size:** S - one module, `adapters/http`, the transport both readers share, and its tests; no contract changes.

**What must be true.** A reader asking a forge whose TLS is signed by the host's own authority gets its answer when
that authority is in the account's `~/.config/porelo/ca-certificates.pem`, as Porelo's own clients do, and still
reaches a forge signed by a public authority.

## Plan

- **Where:** `Http.overNetwork()` (`adapters/http`) builds its `HttpClient` with the default `SSLContext`, so the
  local forge's certificate is refused before any question is asked.
- **The trust:** with the file `${XDG_CONFIG_HOME:-~/.config}/porelo/ca-certificates.pem` present, a context trusting
  the built-in roots and the file's certificates; without it, the default context, as today. A file that holds no
  certificate, or cannot be read, fails the call naming the file, never falls back silently.
- **Red first:** a GET through `Http` against a local HTTPS server signed by a test authority generated in the test:
  refused today.
- **Docs:** `doc/index.md`, *Forgejo Actions*.

## Acceptance

- The red-first test fails on today's code and passes after; without the file, and with another authority's
  certificate in it, the same server is refused.
- A file with no certificate fails naming the file.
- The native reader passes the contract suite under `-Pnative,dist verify`.
