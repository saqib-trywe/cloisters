---
id: T07
title: Choose the MCP implementation
type: grilling
status: open
assignee: saqib
blocked-by: [T01]
---

## Question

How does cloisters serve MCP from Clojure: a Clojure MCP library mounted as a Ring handler, the official Java SDK via
interop, or hand-rolled JSON-RPC (tikka's choice, see its
[ADR 0002](../../docs/inherited/adr/0002-hand-rolled-mcp.md))? The protocol-level requirements are inherited:
Streamable HTTP at `/mcp`, current and previous spec revisions, conformance suite in CI.
