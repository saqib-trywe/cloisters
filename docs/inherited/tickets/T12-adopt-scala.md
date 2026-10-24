---
id: T12
title: Adopt Scala with the Typelevel stack
type: grilling
status: closed
assignee: saqib
blocked-by: []
---

## Question

Tikka was charted as a Clojure project. Should it be Scala instead, and if so, which of
Scala's two coherent stacks?

## Resolution

**Scala 3 with the Typelevel stack** — cats-effect, http4s, fs2, circe — and **Laminar**
for the web UI. Settled 2026-09-14, before any product code existed, so the cost was
limited to planning artifacts.

The substantive part of the decision was not Clojure-versus-Scala but which Scala. This
environment has the `direct-style-scala` skill installed and enabled; it auto-loads for
any Scala task and mandates Ox plus Tapir's `.handle` family, stating that `.serverLogic`
and monadic wrappers MUST NOT be used. Choosing Typelevel is therefore a deliberate
override of a standing machine convention, not just a library preference.

Three project-specific facts argued for direct-style and were weighed and rejected: MCP
stdio needs blocking stream reads that virtual threads handle natively; the official Java
MCP SDK is Reactor-based and reaching cats-effect from it needs a bridge; and sub-100ms
CLI startup is easier without a cats-effect runtime to initialise. Typelevel was chosen
anyway for ecosystem maturity and familiarity, with those costs accepted explicitly.

Full reasoning, accepted costs, and revisit conditions:
[ADR 0001](../../docs/adr/0001-scala-with-typelevel-stack.md). The override is made
operational in [CLAUDE.md](../../CLAUDE.md) so sessions do not re-open it.

### What this invalidated

[Survey the Clojure ecosystem for tikka's four surfaces](T01-clojure-ecosystem-survey.md)
and the four technology tickets behind it, which were rewritten for Scala.

### What survived untouched

Everything language-independent, which is most of the map:
[Scope and shape settled at charting](T00-scope-and-shape.md), the `CONTEXT.md` glossary,
[Define tikka's core invariants](T02-core-invariants.md),
[Define the shared query and filter grammar](T08-query-grammar.md),
[Design the MCP tool contract](T07-mcp-tool-contract.md), and
[Design the HTTP API shape](T09-http-api-shape.md).

### One consequence worth naming

Laminar is a Scala.js SPA, which sits in tension with the earlier decision that the web
UI is a read-first reviewing surface — the strongest case for server-rendered HTML with
no build step. It earns its place differently: cross-compiling the domain model between
JVM and browser makes "one core, many surfaces" literal rather than aspirational. The
toolchain cost is accepted knowingly.
