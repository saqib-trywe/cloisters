---
title: "Cloisters: a local issue tracker and wiki, in Clojure"
label: wayfinder:map
---

# Cloisters: a local issue tracker and wiki, in Clojure

## Destination

A spec plus a walking skeleton plus a milestone build plan: tight enough that build sessions can execute
cloisters, a simple Jira and a simple Confluence in one local daemon, without re-deciding anything. The skeleton
proves the Clojure stack: one core under HTTP, MCP and web UI, a separate thin CLI, and a round trip for both an
issue and a page.

Acceptance test: wayfinder could run on cloisters' issues, and cloisters' own `CONTEXT.md`, ADRs and build plan
could live as cloisters pages, with mentions working in both directions.

## Notes

**Domain.** Cloisters is a Clojure rewrite of **tikka** (an agent-first, single-user, local issue tracker
planned and built in Scala), plus a wiki. Issues and pages are one product over one core: a project owns both
its issues and one page tree, and prose in either links to the other. Vocabulary lives in
[CONTEXT.md](../CONTEXT.md). Read it before using any domain term.

**Inherited decisions.** Tikka's planning record is snapshotted in [docs/inherited/](../docs/inherited/README.md).
Every tikka decision carries over unless a cloisters ticket re-decides it. Read "tikka" as "cloisters" and `TIK`
as `CLO`. Stack-bound decisions (Scala, Typelevel, tapir, Laminar, Scala Native, Laika) do **not** carry over:
each has a ticket here. Never read outside this repository; the snapshot is the source.

**Why Clojure.** This rewrite exists to learn the Clojure stack. Where options are close, prefer the idiomatic,
mainstream Clojure choice and count learning value as a real benefit, not just "the simplest thing".

**Skills.** Every grilling session calls `grilling` and `domain-modeling`. Research tickets call `research`
and may run as background subagents in a worktree, committing to a `research/<name>` branch. The walking skeleton
calls `prototype`.

**Standing preferences.**

- Plan, don't do. The walking skeleton is the single execution exception on this map.
- The scope floor is a refusal list, not a wish list: every "while we're here" is how you get Jira and Confluence.
- Agent-first: when a human surface and MCP disagree, MCP wins. One deliberate exception: the web UI authors
  pages in a plain markdown editor (text area plus preview, no WYSIWYG).
- Cloisters never learns what wayfinder is.
- Files live in the standard XDG locations (`~/.config`, `~/.local`, `~/.cache`), not a single dot-home.
  Cloisters must be able to run beside a live tikka daemon (different port, different files).
- Local markdown tracker in `.wayfinder/`; conventions in [README.md](README.md), frontier via `frontier.sh`.

## Decisions so far

<!-- one line per closed ticket: [title](link): gist -->

Inherited from tikka unchanged (the ticket in the snapshot holds the decision):

- Inherited: [Define tikka's core invariants](../docs/inherited/tickets/T02-core-invariants.md): rejected writes name
  the conflict; no deletion; compare-and-set claims that never expire; closing a blocker reports what it frees.
- Inherited: [Define the shared query and filter grammar](../docs/inherited/tickets/T08-query-grammar.md): one
  GitHub-style text syntax on every surface, `ready`, full-text search, cursor paging. Issue side only; pages are
  extended by a cloisters ticket.
- Inherited: [Choose the persistence engine](../docs/inherited/tickets/T03-persistence-engine.md), engine-level
  choices only: SQLite readable without cloisters, one serialized writer, invariants checked in the transaction,
  decimal ranks. The Scala library choice (doobie) does not carry.
- Inherited: [Design the MCP tool contract](../docs/inherited/tickets/T07-mcp-tool-contract.md): nine issue tools,
  rejections as `isError` naming the conflict, compact rows. Page tools are added by a cloisters ticket.
- Inherited: [Define the event log schema](../docs/inherited/tickets/T14-event-log-schema.md): one write, one event,
  global sequence feeding live updates. Issue side only.
- Inherited: [Choose the MCP server implementation and transport](../docs/inherited/tickets/T04-mcp-implementation.md),
  protocol-level choices only: `/mcp` over Streamable HTTP, JSON-only, current and previous spec revisions with the
  conformance suite in CI, stdio only via a CLI proxy, localhost hardening. Hand-rolling is re-decided.
- Inherited: [Design the HTTP API shape](../docs/inherited/tickets/T09-http-api-shape.md), shape only: thin adapters
  over one core, REST plus POST intent actions, three statuses, resumable SSE stream. The tapir sharing is
  re-decided.
- Inherited: [Daemon lifecycle and repo-to-project binding](../docs/inherited/tickets/T10-daemon-lifecycle.md):
  per-user OS service, never started implicitly, fixed port, one daemon by lock, forward-only migrations, committed
  binding file. File locations are re-decided for XDG.
- Inherited: [Settle body and mention conventions](../docs/inherited/tickets/T15-body-and-mention-conventions.md),
  rules only: code never counts, existing project keys only, forward references, prose returned verbatim. Laika
  does not carry.
- Inherited: [Confirm wayfinder fits tikka's contract](../docs/inherited/tickets/T16-wayfinder-fits-tikka.md):
  session-named assignees plus `assignee:` prefix matching.
- Inherited: [Decide export and durability](../docs/inherited/tickets/T17-export-and-durability.md): JSON Lines
  export, no import, daily snapshots with 7 kept, `quick_check`. Issue side only.

Decided for cloisters:

- [Survey the Clojure ecosystem for cloisters](tickets/T01-clojure-ecosystem-survey.md): Ring + reitit, next.jdbc on
  SQLite with FTS5, babashka at ~13 ms, nextjournal/markdown on all three runtimes, malli shared via `.cljc`; MCP has
  two young Clojure libraries and a Java SDK whose 2026-07-28 support is still a milestone.

## Not yet specified

- **The event log for pages.** How page saves, moves and renames show up on timelines and in the global sequence,
  and how events relate to revisions. Waits on page identity and revisions.
- **Export, snapshots and restore covering pages.** Tikka's durability decision covers issues only.
- **Mention and link detection without Laika.** The rules are inherited; which Clojure markdown parser enforces
  them on both daemon and UI waits on the survey and the web UI choice.
- **The build plan.** Milestones ordering the build once every ticket is closed.

## Out of scope

- Importing tikka's data. Cloisters starts with an empty store; tikka's data stays in tikka (decided at charting).
- Multi-user in any form, and hosted or remote deployment: inherited from tikka's
  [Scope and shape](../docs/inherited/tickets/T00-scope-and-shape.md).
- Tikka's refused feature list (workflows, custom fields, sprints, boards, estimates, time tracking, notifications,
  dashboards, plugins, epics/versions/components, curated link types), unless Amend scope and shape for the wiki
  changes it.
