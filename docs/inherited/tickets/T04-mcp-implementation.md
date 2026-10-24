---
id: T04
title: Choose the MCP server implementation and transport
type: grilling
status: closed
assignee: saqib
blocked-by: [T13]
---

## Question

How does tikka speak MCP, and over what transport?

Since MCP is the *primary* interface rather than a bolt-on, this choice carries more
weight than a typical library pick — and on the Typelevel stack it is also where the
costs accepted in [ADR 0001](../../docs/adr/0001-scala-with-typelevel-stack.md) land.

- **Implementation.** A Scala-native MCP library if one credibly exists, interop with
  the official Java SDK, or hand-rolled JSON-RPC over http4s. The Java SDK is
  conformance-tested but Reactor-based, so reaching cats-effect means an
  `fs2-reactive-streams` bridge sitting on the primary interface. Hand-rolled JSON-RPC
  with circe avoids the bridge but makes tikka own protocol conformance. Read
  [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md).
- **Transport.** Streamable HTTP mounted as http4s routes in the daemon, or stdio. The
  store is owned by a long-running daemon, so stdio means a thin proxy process forwarding
  to it. Claude Code connects to a local HTTP endpoint directly
  (`claude mcp add --transport http`), which may make stdio unnecessary for the first
  customer — decide whether any required client forces it.
- **If stdio is kept:** blocking stdin reads inside a cats-effect runtime must be
  confined to the blocking pool or they starve compute threads. And what happens when
  the daemon is not running — does the proxy start it, or fail with a message?
- **Resources and prompts**, or tools alone? Agent-first argues for at least considering
  resources for issue bodies.
- **Spec revision targeting.** The current MCP revision is 2026-07-28 while the JVM
  field lags at 2025-11-25, so spec drift is a standing condition. Does tikka pin a
  revision and accept going stale, or commit to following — and who absorbs the drift
  under each implementation choice?

**Inherited from [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md):**
three cats-effect MCP libraries expose http4s routes: ingarabr/scala-mcp-sdk (2025-11-25, dormant since
May), linkyard/scala-effect-mcp (2025-06-18) and andimiller/scala-mcp (JVM/JS/Native). All are
single-maintainer projects. The Java SDK 2.0.1 is conformance-tested at 2025-11-25, but its server
transports are servlet-based, so http4s needs a hand-written transport. chimp's streaming path does not
cover cats-effect. The only 2026-07-28 implementation, fast-mcp-scala, is ZIO. Streamable HTTP lets
Claude Code connect to the daemon directly; stdio-only clients need a proxy, which the native CLI can
provide.

**Inherited from [Design the MCP tool contract](T07-mcp-tool-contract.md):**
nine tools, tools only (no resources or prompts). The implementation must support `outputSchema` and
`structuredContent` next to a text block, and domain errors as `isError` results. It must read a
project binding from the endpoint URL (`…/mcp?project=TIK`) and derive the actor from the
connection. Weigh candidate libraries against those requirements.

## Resolution

Decided 2026-09-15. **Hand-rolled JSON-RPC over circe, mounted in the daemon's http4s server at
`/mcp`.** See [ADR 0002](../../docs/adr/0002-hand-rolled-mcp.md) for the candidate-by-candidate gaps and
the condition for revisiting.

### Protocol surface

- Methods: `initialize` (2025-11-25 lifecycle only), `ping`, `tools/list`, `tools/call`. Nothing else.
- **JSON responses only.** A POST is answered with `application/json`; GET `/mcp` returns 405. There are no
  server-initiated messages (no progress, sampling or `list_changed`), so connection lifetimes don't exist.
- **Revisions: the current dated revision and the one before it**, currently 2026-07-28 (stateless, `_meta`
  per request) and 2025-11-25 (initialize handshake), chosen by what the client sends. When a new revision
  lands, add it and drop the oldest. Rejected pinning either one alone.
- **Conformance suite in CI** at both revisions. This is what makes owning the protocol safe.
- **Sessions only on the 2025-11-25 lifecycle**, holding only the client name from `initialize`
  so the event actor is right on both revisions. They live in memory; after a daemon restart the client gets a 404 and
  re-initializes, per spec. Nothing else is keyed on a session.

### Tools and errors

- **Schemas are derived from the shared Scala argument and result types**, with field descriptions next to
  the fields. One definition feeds `inputSchema`/`outputSchema`, circe codecs and the HTTP API. The derivation
  library is picked in the walking skeleton. Rejected hand-written JSON Schema, which drifts from the decoders.
- **Argument validation failures are `isError` results** (`invalid_argument`), so the model can correct itself.
  JSON-RPC errors are reserved for unknown tools and malformed JSON-RPC.
- **Resources and prompts: none**, per the tool contract.

### Binding and exposure

- **An unknown project in the binding** (`/mcp?project=TKA`) is refused at the HTTP level: 404 with a body
  naming the valid keys. The human who wrote the config sees a connection failure, and creating the project
  fixes it without a restart. Rejected accepting and failing every tool call, since the model cannot fix its own config.
- **Localhost hardening, on every route (MCP, API, UI):** bind `127.0.0.1` only; reject any `Origin`
  other than the daemon's own; reject a `Host` that isn't `localhost`/`127.0.0.1` on the daemon port
  (DNS rebinding). "No auth" assumed only the user can reach the daemon, and this makes that true.

### Transport

- **Streamable HTTP in the daemon is the only MCP endpoint.** Claude Code and Codex connect directly.
- **stdio exists only as `tikka mcp`**, a thin proxy subcommand of the native CLI for stdio-only
  clients (Claude Desktop's local config). It is built with the CLI, not the skeleton. It reads the repo's
  binding and forwards to the daemon, so no JVM process handles stdio.

> **Amended by [Walking skeleton — surfaces over one core](T11-walking-skeleton.md):** the method list above is
> incomplete for 2026-07-28. **`server/discover` is mandatory**; `ping` and `initialize` are 2025-11-25-only (a modern
> request for them returns 404/`-32601`); modern results carry `resultType: "complete"` and `_meta` serverInfo;
> `tools/list` and `server/discover` **require `ttlMs` and `cacheScope`**; requests mirror `MCP-Protocol-Version`,
> `Mcp-Method` and `Mcp-Name` into headers, rejected with 400/`-32020` on mismatch; any `_meta`, or a modern version header,
> selects the modern era, and missing required `_meta` fields is 400/`-32602`. **Drop nulls from `structuredContent`**:
> Claude Code validates it against `outputSchema`. The released conformance suite (npm 0.1.16) predates 2026-07-28, so CI
> runs it from GitHub `main` until a release ships. The hand-rolled adapter came to about 200 lines and passed every applicable
> scenario at both revisions.

> **Refined by the build's scaffold milestone ([build plan](../../docs/build-plan.md), M0):** npm now publishes the
> suite's 2026-07-28 scenarios as the prerelease `0.2.0-alpha.11` (2026-08-07). CI pins that version instead of running
> GitHub `main`, so runs are reproducible, and bumps it deliberately.
