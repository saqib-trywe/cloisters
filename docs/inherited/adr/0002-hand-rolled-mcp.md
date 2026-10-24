# 2. Hand-roll MCP over http4s instead of adopting a library

Date: 2026-09-15

## Status

Accepted.

## Context

MCP is tikka's primary interface. [The tool contract](../../.wayfinder/tickets/T07-mcp-tool-contract.md)
fixes what the implementation must do: nine tools, tools only; every result carries a text block
and `structuredContent` against a declared `outputSchema`; domain rejections are `isError` results
that *also* carry `structuredContent`; a connection's project binding is read from the endpoint URL
(`/mcp?project=TIK`); and the event actor comes from the client's identity.

Two protocol revisions matter. 2025-11-25 uses an `initialize` handshake and optional sessions;
2026-07-28 is stateless: no handshake, no `Mcp-Session-Id`, with version and client info in
`_meta` on every request. They are not wire-compatible. Claude Code, tikka's first client,
negotiates 2026-07-28 with HTTP servers that support it.

Surveyed 2026-09-15, every candidate fell short:

| Candidate | Gap |
|---|---|
| andimiller/scala-mcp (cats-effect, http4s) | 2025-11-25 and session-based; tool handlers see neither the request URL nor `clientInfo` (only an authenticate hook at `initialize`) |
| linkyard/scala-effect-mcp (cats-effect, http4s) | 2025-06-18; its error result cannot carry `structuredContent` |
| ingarabr/scala-mcp-sdk (cats-effect, http4s) | 2025-11-25; no commits since 2026-05-01 |
| Official Java SDK 2.0.1 | 2025-11-25; servlet transports, so an http4s transport must be written anyway; Reactor and Jackson on the primary interface |
| softwaremill/chimp | streaming paths cover Ox, ZIO and Pekko, not cats-effect; 2025-03-26 HTTP transport |
| TJC-LP/fast-mcp-scala | the only 2026-07-28 implementation, but built on ZIO |

## Decision

Tikka implements MCP itself: JSON-RPC over circe, mounted as http4s routes at `/mcp` in the
daemon. The surface is deliberately small: `initialize` (2025-11-25 lifecycle only), `ping`,
`tools/list`, `tools/call`, answered with `application/json` only, never SSE and never
server-initiated. It speaks the current dated revision and the one before it, chosen per request.
The official conformance suite (`@modelcontextprotocol/conformance`) runs against the daemon at
both revisions in CI.

## Consequences

- Tikka owns spec drift. Each new revision is tikka's work to add, and the conformance suite
  says when it is behind. That cost is bounded by the contract refusing everything hard in MCP:
  sampling, elicitation, tasks, resources, prompts, subscriptions, auth.
- No Reactor bridge and no Jackson; the MCP layer shares circe codecs and derived schemas with the
  rest of tikka.
- The binding and actor come straight from the http4s request, with no hooks to work around.
- If tikka ever wants what the contract refuses (resources, sampling, tasks), hand-rolling stops
  being cheap and this decision should be revisited.

**Revisit when** a Typelevel-native MCP library supports both current revisions, exposes request
context to tool handlers, and passes the conformance suite, or when the contract grows beyond
tools.
