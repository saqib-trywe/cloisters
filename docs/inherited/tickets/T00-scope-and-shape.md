---
id: T00
title: Scope and shape settled at charting
type: grilling
status: closed
assignee: saqib
blocked-by: []
---

## Question

What is tikka, who is it for, and what does it refuse to do?

## Resolution

Settled across three grilling rounds with saqib at charting, 2026-09-14.

### Destination and stance

- **Planning, not building.** The map ends at a spec a build session can execute
  without re-deciding anything. The single exception is a walking-skeleton prototype,
  because "four surfaces, one core, one process" is a technical assumption that paper
  cannot validate.
- **Agent-first.** The MCP tool surface *is* the primary interface; the data model is
  designed for legibility to an LLM. The web UI is the human's window onto what agents
  did. Rejected human-first, which would compete with Linear and lose.
- **Wayfinder is the first customer and the acceptance test.** Its requirements — a map
  issue, child issues, labels, blocking, assignee-as-claim, a frontier query — are a
  real demanding spec, and beat guessing at "JIRA but simpler." Tikka nonetheless never
  learns what wayfinder is: those ride on generic labels and generic edges.
- **Local-only is permanent, not a phase.** No auth, no permissions, one implicit user.
  Assignee is free text, not a User entity. Multi-user is the largest single source of
  the complexity tikka exists to avoid; modelling it "just in case" pre-pays for a
  payoff that may never come.

### Scope floor

Keep exactly: title, markdown body, status, labels, assignee, parent, blocking edges,
comments, timestamps. Comments are load-bearing — wayfinder posts a resolution comment
on close.

Refuse: configurable workflows, custom fields, permissions, sprints and boards,
estimates, time tracking, notifications, dashboards, plugins, attachments (link to
assets instead), epics/versions/components (parent/child covers it), and a query
language — structured filters only.

### Architecture

**A single local daemon**, not issues-as-files-in-the-repo. One process serves web UI,
HTTP, and MCP; the store lives under `~/.tikka/`; the CLI talks HTTP to it.

Rejected files-in-repo despite real merits (issues version with code, readable when the
server is down). The decisive argument: wayfinder expects parallel sessions racing for
the same ticket, and file-per-issue plus git handles concurrent claims badly, generating
merge conflicts on exactly the hot files. The reason to build tikka at all is structured
state with enforced invariants — blocking DAGs, frontier computation, atomic claims. If
plain files sufficed, agents already have file tools and none of this is worth building.

### Domain model

- **Two entities.** `Project` (a namespace: id sequence plus a short key) and `Issue`.
  No map/epic/story types — labels carry all semantics.
- **Three edge kinds, no more.** `parent` (structural, single, hand-set); `blocks`
  (DAG, cycles rejected at write time, hand-set); `mentions` (derived automatically
  from `TIK-nn` occurrences in bodies and comments, never hand-managed, surfaced as
  backlinks). Derived edges cannot go stale; curated ones always do. This also means
  wayfinder's prose "Decisions so far" index becomes a queryable graph at zero
  authoring cost.
- **Two statuses.** `open | closed`. In-progress is *derived* from assignee, never
  stored, so the two cannot drift. Closing demands a `resolution` of `done | dropped` —
  exactly the distinction wayfinder needs between a ticket resolved on the route and one
  ruled out of scope.
- **Identity** is a per-project short key plus a monotonic integer: `TIK-42`. Speakable,
  CLI-friendly, stable. URLs are `http://localhost:PORT/i/TIK-42`.
- **Order** is one nullable `rank` field defaulting to the issue's sequence number,
  sorted ascending. Wayfinder's "take the first frontier ticket in order" makes a stable
  total order a hard requirement; rank keeps it reorderable without lexorank machinery.
- **History** is an append-only `events` log per issue (actor, timestamp, field,
  old→new), written in the same transaction as the mutation. Explicitly *not*
  event-sourced: current state is stored directly and the log is a record, not the
  source of truth. With agents mutating state unattended, this is the difference between
  trusting the tracker and re-checking it by hand. Candidate ADR.

### Surface scopes

- **MCP**: coarse CRUD plus a small set of intent tools that each earn their place by
  enforcing something generic update cannot — `claim_issue` (atomic compare-and-set,
  fails if already assigned), `close_issue` (demands resolution and comment), `frontier`.
  Target ~7–8 tools. The rule that holds the line: an intent tool is justified by an
  invariant or a race, never by convenience.
- **Web UI**: a reading and reviewing surface — lists, filters, issue detail, a
  frontier/graph view, the event timeline — plus light inline edits. Explicitly not a
  form-heavy authoring tool: authoring forms are the expensive part of a tracker UI, and
  agents no longer need them.
- **CLI**: full CRUD. Nearly free once the HTTP API exists, and it is where scripting
  happens.
