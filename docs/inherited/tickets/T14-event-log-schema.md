---
id: T14
title: Define the event log schema
type: grilling
status: closed
assignee: saqib
blocked-by: [T03]
---

## Question

What does an event record, and how does a write become events?

The glossary fixes the concept: who, when, which field, from what to what; append-only;
separate from current state. The persistence engine fixes the mechanics: SQLite, events written
in the same transaction as the mutation, timestamps from the daemon clock, and an issue's
**updated** time defined as the time of its latest event. What is still open:

- **Granularity.** One event per field changed, or one event per write carrying every field it
  changed? An agent's single `update_issue` touching title and labels: one timeline entry or two?
- **Which issues an event lands on.** `TIK-3 blocks TIK-7` changes both issues' state. One event
  on each, or one event referenced by both? This decides whether adding a blocker moves both
  issues' updated time.
- **Who.** Assignee is free text and there is no auth. What goes in the actor field: the
  agent's self-declared name, the surface (`mcp`, `cli`, `web`), both? Can it be absent?
- **Payload shape.** Typed columns per event kind, or a kind plus a JSON payload? The store must
  stay readable in `sqlite3` without tikka, which the persistence engine settled.
- **Prose changes.** Does editing a body record the full before and after text, a diff, or only
  that it changed? Mentions are rewritten in the same transaction: do added and removed mentions
  appear as events of their own?
- **Derived changes.** Closing a blocker unblocks its dependents, and blocked is never stored.
  Does the dependent's timeline show "unblocked"? If so, that event is derived, which fits
  awkwardly with a log of writes.
- **Reading it back.** What the web UI timeline and an agent's history query need from it,
  and whether events are ever queried across issues ("what changed in the last hour").

Read [Choose the persistence engine](T03-persistence-engine.md) and
[Define tikka's core invariants](T02-core-invariants.md) first.

**Inherited from [Design the MCP tool contract](T07-mcp-tool-contract.md):**
no write carries an actor argument; the actor comes from the connection (surface plus client
name), and parallel sessions often share a client name. If events need more, argue it here and
reopen that decision. Claim, release and reassign are distinct operations and should read as such.
Every write can carry a comment appended in the same transaction; close and reopen always do.
`get_issue` returns events only on request.

## Resolution

Decided 2026-09-15.

### What an event is

- **One write, one event**, holding a list of field changes. A comment given with the write belongs
  to that event, and a comment on its own is an event with no changes. Rejected one event per field,
  which splits one intention into fragments and moves `updated` several times for one action.
- **A comment is an event.** `get_issue`'s comment list is the events that carry a comment, so
  there is no separate comments store to drift from the log.
- **Who** is the surface plus the client name: `mcp:claude-code`, `cli`, `web`. Claim, release and reassign
  carry the assignee names as change data, which is where telling sessions apart matters. The contract's
  "no actor argument" stands. Rejected a self-declared session name (unreliable, and it would add an argument to every schema).
- **Prose changes** (title, body) record the full before and after text, so every event can be read on its own.
  Rejected stored diffs, which must be replayed in sequence and break if one link is damaged. Size is negligible.
- **Creates** are the first write, not a special kind: `set` for each initial field, `add` for each
  blocker.
- **Writes that change nothing** record no event and do not move `updated`, unless they carry a comment.
- **Rejected writes leave no trace.** The log records changes, not attempts.

### Which timelines an event appears on

One subject (the issue written to) plus related issues. An event appears on every related
timeline and moves their `updated`. An issue is related when the write:

- changed its parent or blocking edges (a new child, a new or removed blocked issue), including at
  creation; or
- unblocked it or blocked it again: a close names every dependent it unblocked (*"unblocked:
  TIK-3 closed done"*), and a reopen names every open dependent it blocks again.

**Mentions never relate.** A backlink appears in `get_issue`, but the mentioned issue's timeline stays
quiet, so that `updated-after` keeps meaning "someone did something to this". A child closing does not relate its
parent, because "has open children" is not a named state.

### Storage

- `event`: global sequence, subject issue, time (daemon clock, ISO-8601 UTC), actor, comment.
- `change`: event, field, op (`set` | `add` | `remove`), old, new, all as text. Status, resolution,
  labels, edges, assignee, rank, title and body all use this one shape.
- `event_related`: event, issue.

Readable in `sqlite3` without parsing JSON. "Every title change in TIK" is a plain `WHERE`, and a
new field needs no schema change. Rejected kind plus JSON payload, and a table per event kind.
Written in the same transaction as the mutation, per the persistence engine. **Kept forever**, with no
compaction.

### Ordering and reading

- A **global, monotonically increasing sequence** orders timelines, not timestamps, which can repeat or step
  backwards.
- **HTTP** exposes a cross-issue feed of events after sequence N, consumed by live UI updates.
  **MCP has no feed**: agents have `updated-after` and `get_issue include: events`, and a feed tool
  would fail the contract's rule.
- **`get_issue include: ["events"]`** returns the latest 50, oldest first within those, with
  `events_total` and `events_truncated`. Full history is over HTTP. Each event renders as a header line
  (`#1042 2026-09-15 10:02 mcp:claude-code`) plus its changes and comment. Bodies render as a unified diff,
  titles as before and after, and other fields as `field: old → new` or `+label`/`-label`, in both text and
  `structuredContent`. The full stored text serves the UI's diff view. *Refined by [Shape the Laminar frontend](T05-web-ui-approach.md): one
  line-diff implementation in the shared module serves both MCP rendering and the UI.*
