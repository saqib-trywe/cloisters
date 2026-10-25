---
id: T07
title: Choose the MCP implementation
type: grilling
status: closed
assignee: saqib
blocked-by: [T01]
---

## Question

How does cloisters serve MCP from Clojure: a Clojure MCP library mounted as a Ring handler, the official Java SDK via
interop, or hand-rolled JSON-RPC (tikka's choice, see its
[ADR 0002](../../docs/inherited/adr/0002-hand-rolled-mcp.md))? The protocol-level requirements are inherited:
Streamable HTTP at `/mcp`, current and previous spec revisions, conformance suite in CI.

## Resolution

Grilled with saqib, 2026-10-25, in one round. **Hand-rolled JSON-RPC as a Ring handler at `/mcp`**, recorded in
[ADR 0001](../../docs/adr/0001-hand-rolled-mcp.md) with its revisit condition.

- **Why not a library.** PluMCP 0.3.0 and roklenarcic/mcp-server 0.3.61 are Ring handlers but stop at 2025-11-25 and
  publish no conformance results; Claude Code negotiates 2026-07-28. The Java SDK's 2.2.0-M1 milestone is the one JVM
  implementation of 2026-07-28, but it is unreleased and a servlet plus Reactor, mounting beside Ring rather than in it,
  with a Reactor bridge on the primary interface.
- **Shape.** A Ring handler from a JSON-RPC request map to a response map, dispatching on the method name, with the
  binding (`?project=`) and client name read straight from the request. Tikka's adapter was about 200 lines.
- **Protocol surface: inherited unchanged** from [Choose the MCP server implementation and
  transport](../../docs/inherited/tickets/T04-mcp-implementation.md), including the skeleton's amendment: 2026-07-28 and
  2025-11-25 chosen per request, `server/discover` mandatory on 2026-07-28, JSON only, no server push, sessions only on the
  2025-11-25 lifecycle holding the client name, nulls dropped from `structuredContent`, localhost hardening on every
  route, stdio only through the CLI's `cloisters mcp` proxy.
- **Conformance in CI** at both revisions, pinned to `@modelcontextprotocol/conformance` `0.2.0-alpha.12` (the latest
  prerelease carrying 2026-07-28 scenarios) and bumped deliberately.
- **Handed on** to [Define the shared contract and HTTP adapter](T10-shared-contract-and-http.md): the HTTP server
  and JSON library under the handler, and deriving `inputSchema` and `outputSchema` from the shared contract.
