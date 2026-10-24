---
id: T02
title: Define tikka's core invariants
type: grilling
status: closed
assignee: saqib
blocked-by: []
---

## Question

The domain model is settled — two entities, three edge kinds, two statuses. What are
the *rules* that model must enforce? These are the invariants every surface inherits,
so getting them wrong is expensive and getting them late means four surfaces disagree.

Specifically:

- **Closure and rollup.** Does closing a parent close its children? Can a child outlive
  a closed parent? Does closing a parent with open children require a force flag, warn,
  or silently allow it?
- **Blocking versus closure.** Can an issue be closed while it still has open blockers?
  Does a *closed* issue continue to block its dependents, or does closing it release
  them? (The second is almost certainly right, but it needs stating, because the
  frontier query depends entirely on it.)
- **Cycle rejection.** Blocking is a DAG, so writes that would create a cycle are
  rejected. At what depth is this checked, and what does the caller see? Does `parent`
  need the same protection — can hierarchy cycle?
- **Claim semantics.** `claim_issue` is compare-and-set: it fails if already assigned.
  Can a claim be stolen or force-reassigned? Does closing release the claim? Do claims
  expire, given an agent session can die holding one — and if they don't, what unwedges
  a ticket claimed by a session that never came back?
- **Deletion.** Is deletion possible at all, or is `dropped` the only exit? If an issue
  can be deleted, what happens to edges pointing at it and to mentions of its id?

Call `grilling` and `domain-modeling`. Record resolved terms in `CONTEXT.md`.

## Resolution

Settled across three grilling rounds with saqib, 2026-09-15.

### The governing principle

**Invariants are state invariants, not transition checks.** Any write that would leave
the data violating an invariant is rejected, whatever kind of write it is, and the
rejection names the conflict. This single rule replaces a per-operation list of checks,
and it answers questions nobody has asked yet — moving issues, editing edges on closed
issues, reopening. Rejected the alternative of checking only at close time, which lets
other writes (re-parenting, adding edges, reopening) leave the data in exactly the states
the rules exist to prevent. Cost: some writes need a graph check inside the transaction,
trivial at this scale.

### The invariants

1. **A closed issue has no open children.** Closing a parent with open children is
   rejected for either resolution; the error lists the open children. No cascade — one
   call must not silently mutate many issues. Dropping a large effort is a bulk-close
   job for the CLI, not a model special case.
2. **An issue closed `done` has no open blockers.** `done` with an open blocker would
   claim the work finished before its prerequisites. `dropped` is exempt: abandoning
   work never needs its prerequisites. Deliberately asymmetric with invariant 1 —
   hierarchy is containment, blocking is sequence.
3. **Hierarchy is acyclic**, as blocking already was. No depth limit. Every cycle
   rejection returns the offending path (`TIK-4 → TIK-9 → TIK-4`).
4. **`parent` and `blocks` edges stay within one project.** Mentions may cross projects;
   they carry no invariants.
5. **Issues are never deleted**, on any surface. `dropped` is the only exit, including for
   mistakes. Ids never reused, mentions never dangle, the event log stays whole.
6. **A closed issue's `resolution` is immutable** while it stays closed. Title, body,
   labels and comments stay editable after close, all evented; edges stay editable within
   the invariants.
7. **Projects are never removed and their key never changes.** The key is baked into
   every id, URL and prose mention. The display name may change freely.

### Closing releases dependents

Closing a blocker — `done` *or* `dropped` — unblocks its dependents. The frontier
definition stays one uniform rule, and tikka does not judge whether a dropped blocker
still matters. `close_issue` returns the issues it newly unblocked, so the closer sees
the consequence and can act on it.

### Reopening

Allowed, subject to the state invariants: rejected while the parent is closed, or while
any issue this one blocks is closed `done`; the error names the obstacle. Reopening
clears `resolution` (the event log retains it) and keeps the assignee.

### The assignee

Removed from generic update entirely. It changes only through three compare-and-set
operations:

- **claim** — succeeds only if unassigned;
- **release** — only by the current holder;
- **reassign** — the caller must name the expected current holder. Reassigning to nobody
  is how a human clears a claim held by a dead session.

Closing keeps the assignee as the record of who resolved it.

**Stale claims do not expire.** Leases need heartbeats, and agents do not heartbeat —
they work in bursts with long gaps between tool calls, so any timeout either steals from
a live agent or is too long to help. Instead the claim time is recorded and a filter
surfaces claims older than a given age; clearing one is a deliberate `reassign` to
nobody, visible in the event log.

### Concurrent edits

Every issue carries a version. Writes to unguarded fields (title, body, labels) accept an
**optional** expected version: stated and stale, the write is rejected; omitted, the write
wins. Simple agents stay simple and careful agents can protect themselves. Comments are
append-only and never conflict. Rejected mandatory versioning, which forces a read before
every one-line label change.

### Order

Rank is not unique. Ties break by issue number, so order is always total and reordering
never touches another issue.
