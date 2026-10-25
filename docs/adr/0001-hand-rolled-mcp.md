# Hand-roll MCP as a Ring handler instead of adopting a library

Status: accepted, 2026-10-25.

MCP is cloisters' primary interface, and its contract needs both the stateless 2026-07-28 revision (which Claude Code
negotiates) and 2025-11-25, tool handlers that see the request URL and client name, and `isError` results carrying
`structuredContent`. As of October 2026 no Clojure library speaks 2026-07-28 (PluMCP 0.3.0 lists it as TODO;
roklenarcic/mcp-server 0.3.61 is alpha and quiet), and the only JVM implementation that does, the Java SDK's 2.2.0-M1
milestone, is a servlet plus Reactor that mounts beside a Ring stack, not inside it. So cloisters implements MCP itself:
a Ring handler at `/mcp` from JSON-RPC request maps to response maps, JSON responses only, tools only, with the official
conformance suite run in CI at both revisions as the safety net. Tikka's equivalent was about 200 lines.

## Consequences

Cloisters owns spec drift: each new revision is its own work, and the conformance suite says when it falls behind.
That stays cheap only while the contract refuses everything hard in MCP (resources, prompts, sampling, elicitation,
tasks, subscriptions, auth).

**Revisit when** a Clojure MCP library supports both current revisions, gives tool handlers the request and client
info, and publishes conformance results (PluMCP is the likeliest), or when the contract grows beyond tools.
