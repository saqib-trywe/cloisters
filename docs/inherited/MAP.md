---
title: "Tikka: an agent-first local work management system"
label: wayfinder:map
---

# Tikka: an agent-first local work management system

## Destination

A spec tight enough that a build session can execute tikka without re-deciding
anything — domain model, persistence, the four surfaces, and the MCP tool contract —
together with a walking skeleton proving the surfaces share one core: web UI, HTTP API
and MCP in a single daemon process, with the CLI as a separate thin client against it.

Done when wayfinder itself could be hosted on tikka: that is the acceptance test.

## Notes

**Domain.** Tikka is a JIRA-like work management system, deliberately simpler, for
local single-user use. Four surfaces over one core: web UI, CLI, HTTP API, MCP server.
Vocabulary lives in [CONTEXT.md](../CONTEXT.md) — read it before using any domain term.

**Stack.** Scala 3, Typelevel (cats-effect, http4s, fs2, circe), Laminar for the web UI.
See [ADR 0001](../docs/adr/0001-scala-with-typelevel-stack.md).

**Skills.** Every session calls `grilling` and `domain-modeling` unless the ticket
says otherwise. Research tickets call `research`. The walking skeleton calls
`prototype`. The `direct-style-scala` skill auto-loads for Scala work but is
**overridden** here — follow [CLAUDE.md](../CLAUDE.md), not its Ox/Tapir mandate.

**Standing preferences.**

- Plan, don't do. The walking-skeleton ticket is the *single* execution exception on
  this map; nothing else builds product code.
- The scope floor is a refusal list, not a wish list. Defend it: every "while we're
  here" is how you get JIRA.
- Agent-first. When a human surface and the MCP surface disagree, the MCP surface
  wins; the web UI is a window onto what agents did, not an authoring tool.
- Tikka never learns what wayfinder is. Wayfinder's concepts ride on generic labels
  and generic edges, or they don't ride at all.

## Decisions so far

- [Scope and shape settled at charting](tickets/T00-scope-and-shape.md): tikka is an
  agent-first, permanently single-user, local-only tracker with two entities, three
  edge kinds, two statuses, and a daemon that owns the store.
- [Survey the Clojure ecosystem for tikka's four surfaces](tickets/T01-clojure-ecosystem-survey.md):
  *superseded by the move to Scala.* Two conclusions outlive it: the official Java MCP
  SDK is the conformance-tested JVM path, and JVM startup rules the CLI out of the daemon
  process.
- [Adopt Scala with the Typelevel stack](tickets/T12-adopt-scala.md): Scala 3 with
  cats-effect, http4s, fs2 and circe, plus Laminar — deliberately overriding the
  installed direct-style skill, with the MCP-bridge and CLI-startup costs accepted.
- [Define tikka's core invariants](tickets/T02-core-invariants.md): invariants hold at all
  times and any write that would break one is rejected with the conflict named; no
  deletion; the assignee changes only by compare-and-set claim, release or reassign, and
  claims never expire; closing any blocker releases its dependents and reports them.
- [Define the shared query and filter grammar](tickets/T08-query-grammar.md): one
  GitHub-style text syntax on every surface — AND across filters, OR within, negation, no
  parentheses; `ready` names open-unblocked-unclaimed work; full-text search; cursor paging;
  strict rejection of anything unparseable.
- [Survey the Scala ecosystem for tikka's four surfaces](tickets/T13-scala-ecosystem-survey.md):
  SQLite through doobie meets every persistence requirement; three cats-effect MCP libraries mount
  as http4s routes but all trail the 2026-07-28 spec; a tapir endpoint shared across JVM and
  Scala.js gives Laminar a typed client; cats-effect starts in single-digit milliseconds on Scala
  Native, so the CLI can stay on the Typelevel stack.
- [Choose the persistence engine](tickets/T03-persistence-engine.md): SQLite through doobie with
  hand-written SQL; readable in `sqlite3` without tikka; one serialized writer with every commit
  synced; graph invariants checked in code inside the transaction; derived state never stored
  except mentions; substring text search; decimal ranks.
- [Design the MCP tool contract](tickets/T07-mcp-tool-contract.md): nine tools, with no
  ready, comment, project, batch or delete tools; comments ride on writes; rejections are
  `isError` results naming the conflict with the facts to act on; compact rows, never bodies, in
  lists; a connection's project binding lives in its MCP config.
- [Define the event log schema](tickets/T14-event-log-schema.md): one write is one event holding
  field changes and its comment; events also land on edge counterparts and unblocked dependents
  but never on mentioned issues; a global sequence orders them and feeds live updates over HTTP.
- [Choose the MCP server implementation and transport](tickets/T04-mcp-implementation.md): hand-rolled
  JSON-RPC over http4s at `/mcp`, JSON-only, speaking the current and previous spec revisions with
  the conformance suite in CI; stdio only via a CLI proxy; localhost hardened against foreign
  origins. See [ADR 0002](../docs/adr/0002-hand-rolled-mcp.md).
- [Design the HTTP API shape](tickets/T09-http-api-shape.md): MCP and HTTP are thin adapters over
  one core; REST for CRUD with POST intent actions; tapir endpoints shared across daemon, UI and
  CLI also derive the MCP schemas; JSON only, three statuses, a resumable SSE event stream.
- [Choose the CLI approach](tickets/T06-cli-approach.md): Scala Native with cats-effect and the
  shared tapir client, gated in CI at a 100 ms median; raw queries to the daemon; MCP's text
  renderer for output; bulk close and project creation as CLI-only human acts; `tikka mcp` as a
  framing-only stdio proxy.
- [Daemon lifecycle and repo-to-project binding](tickets/T10-daemon-lifecycle.md): a per-user OS
  service that nothing starts implicitly; fixed configurable port; one daemon per `TIKKA_HOME` by
  lock; forward-only migrations with a snapshot first; a committed `.tikka` binding written by
  `tikka init`, which never creates projects.
- [Shape the Laminar frontend](tickets/T05-web-ui-approach.md): sbt 2, no npm or Vite; a shared
  module holding only the contract; ready, tree and detail views with no diagrams; project scope
  lives in the query, not a picker; live updates that never reshuffle lists; reviewer-only edits;
  markdown rendered by Laika with raw HTML off.
- [Walking skeleton — surfaces over one core](tickets/T11-walking-skeleton.md): the architecture holds:
  one core under HTTP and MCP, one shared contract unchanged on JVM/JS/Native, MCP served to a real
  Claude Code client on 2026-07-28, CLI search in 11 ms. It corrected the CLI's HTTP backend (curl, not
  Ember) and the MCP method list.
- [Settle body and mention conventions](tickets/T15-body-and-mention-conventions.md): markdown-aware
  detection via Laika shared by daemon and UI; code never counts; titles, bodies, comments and
  `/i/KEY-N` links do; only existing project keys, with forward references stored; prose returned
  verbatim to agents.
- [Confirm wayfinder fits tikka's contract](tickets/T16-wayfinder-fits-tikka.md): every wayfinder
  operation maps onto the contract; the one gap, same-developer parallel sessions, is closed by
  session-named assignees plus `assignee:` prefix matching.
- [Decide export and durability](tickets/T17-export-and-durability.md): offline JSON Lines export from
  the daemon jar; no import; daily self-snapshots with 7 kept; restore that never deletes;
  `quick_check` at startup and on every snapshot.

## Not yet specified

Nothing currently: every in-scope question is a ticket or a decision.

## Out of scope

- Multi-user in any form: authentication, permissions, visibility rules, user entities.
  Ruled out at charting — see [Scope and shape settled at charting](tickets/T00-scope-and-shape.md).
  Assignee stays free text. If tikka ever goes multi-user it is a different product with
  a different data model, and a fresh map.
- Hosted or remote deployment, and anything that implies a network boundary beyond
  localhost.
- The refused feature set, in full: configurable workflows, custom fields, sprints and
  boards, estimates, time tracking, notifications, dashboards, plugins, attachments,
  epics/versions/components, and a query language (JQL or otherwise).
- Seeding the acceptance test (actually moving this map onto a running tikka): that happens after the product is built,
  past this map's destination of a spec plus a skeleton. Before hand-off, [Confirm wayfinder fits tikka's
  contract](tickets/T16-wayfinder-fits-tikka.md) checks the same thing on paper.
- Curated issue-link types — "relates to", "duplicates", "clones", "causes". Derived
  mentions cover the need; curated links go stale.
