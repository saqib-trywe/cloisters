---
id: T03
title: Choose the persistence engine
type: grilling
status: closed
assignee: saqib
blocked-by: [T13]
---

## Question

Which store does the daemon own, and which Scala library reaches it?

The requirements are specific, and should decide this rather than taste:

- Runs **in-process** with zero external services. This alone disqualifies most of the
  Typelevel ecosystem's default answers, which assume Postgres.
- Expresses **recursive queries**: hierarchy walks, and transitive closure over blocking
  edges for the frontier query (open + unblocked + unclaimed).
- Supports an **atomic compare-and-set** for `claim_issue`. Load-bearing — preventing
  that exact race is why the daemon exists rather than plain files. In SQL this is
  `UPDATE … WHERE assignee IS NULL` and checking the affected-row count.
- Appends to the **event log in the same transaction** as the mutation it records.
- Survives restart, and ideally is inspectable when the daemon will not start.

Read [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md)
first.

SQLite is the obvious embedded answer; the real question is the layer above it. doobie is
the Typelevel-native choice and composes with cats-effect transactions directly. Magnum
is the house skill's preference but is direct-style, so it fits tikka's stack poorly —
note this is a case where the overridden skill's advice genuinely does not transfer.
Weigh also: does anything here need a typed query DSL at all, or are hand-written SQL and
recursive CTEs the honest answer for a schema this small?

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
full-text search over title, body and comments is now a hard requirement; `under:` needs
descendant queries at any depth; and cursor pagination must stay stable while other writers
commit, which argues for keyset (rank, issue number) cursors the store can index.

**Inherited from [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md):**
SQLite through doobie meets every requirement above. sqlite-jdbc 3.53.4.0 bundles FTS5,
`Update0.run` returns the row count for the claim compare-and-set, and one `ConnectionIO` carries the
mutation plus its event. skunk is Postgres-only and out; Quill has stalled. What's left is
mostly tuning: WAL and busy timeout, the single-writer transactor shape, and whether FTS5 is a
contentless or external-content table kept in sync by triggers.

## Resolution

Decided 2026-09-15.

**SQLite, reached through doobie with hand-written SQL.** One file under `~/.tikka/`.
Every requirement above maps onto a built-in SQLite feature, and it is the only candidate
whose store stays readable with no tikka code at all. Rejected H2 (weak full-text search,
file format unreadable outside the JVM), DuckDB (built for analytics, not many small writes)
and plain files (no atomic claim, which is the reason the daemon exists).

One correction to the question above: `ready` does **not** need transitive closure.
An issue is blocked while any *direct* blocker is open, so `ready` is an `EXISTS` check.
Recursion is needed only for `under:` and for rejecting cycles.

### Access

doobie handles transactions and row mapping and nothing else. The query grammar's ADT compiles to
composable `Fragment`s. A claim is `UPDATE … WHERE assignee IS NULL` with the affected-row count
checked, and a mutation plus its event is one `ConnectionIO`, so one transaction. Rejected a
typed DSL (none mature for doobie on Scala 3, and not worth it for about 8 tables), raw JDBC
(hand-rolled transactions and resource safety) and magnum/Quill (direct-style, or stalled).
Accepted cost: doobie is still at 1.0.0-RC13.

### Values the store must encode

- **Rank** is any decimal number, stored as a double. Placing between 3 and 4 means 3.5 and
  touches no other issue. Halving between the same two neighbours runs out of precision after
  about 50 steps; if that ever happens, the write is refused with the conflict named. Rejected integers
  (placement would need renumbering) and opaque fractional keys (rank would stop being a readable
  number).
- **Issue numbers** increase and are never reused, allocated inside the creating transaction.
  They are not promised gapless.
- **Updated** is the time of the issue's latest event, so comments and claims count.
  Which issues an edge's event lands on is up to the event log schema.
- **Version** moves only when title, body or labels change. Status and assignee have their
  own guards, so bumping the version for them would only create false conflicts.
- **Time** comes from the daemon's clock only; no write carries its own timestamp. If seeding
  the acceptance test needs backdating, it argues for a dedicated import path there.

### Schema posture

- **Readable in `sqlite3` without tikka.** Statuses, resolutions and labels are stored as words
  and constrained by `CHECK`. Timestamps are ISO-8601 UTC text with milliseconds, which sorts
  correctly as text. Issue ids can be rebuilt from plain columns, and there are no opaque blobs.
- **Invariants in two layers.** The database enforces single-row and referential rules
  (`CHECK`, foreign keys, `UNIQUE`, `NOT NULL`). Tikka's code checks graph invariants (cycles,
  open children, open blockers on `done`) inside the write transaction, because only code can
  name the conflict and return a cycle's path. The constraints are the backstop against bugs and
  hand edits.
- **Derived state is never stored.** In progress, blocked, unblocked and ready are computed at query time,
  for the same drift reason the glossary gives for refusing a stored in-progress flag.
  **Mentions are the exception**: stored as edges, rewritten in the same transaction as the body
  or comment that produced them, so they still cannot disagree with their text. Detection rules
  stay with the body and mention conventions.

### Concurrency and durability

- **One dedicated write connection.** Write transactions run one at a time and open with
  `BEGIN IMMEDIATE`. Reads use a small connection pool alongside it under WAL. "Check invariants,
  then write" never interleaves with another writer, and `SQLITE_BUSY` retries never arise.
  Rejected a shared pool with busy retries, and a single connection that lets a slow search hold up
  claims.
- **`synchronous=FULL`.** A committed claim survives power loss. It costs about a millisecond
  per write, which is invisible at tikka's write rate.

### Search and paging

- **`text:` is a case-insensitive substring match** (FTS5 trigram tokenizer), the way grep matches:
  `text:claim` matches "reclaimed" and `text:frontier.sh` works as typed. Rejected
  porter stemming and whole-word matching, whose tokenizer rules surprise agents. The index is
  larger, which doesn't matter at this size.
- **Cursor guarantee, stated precisely:** issues that did not change while you paged
  appear exactly once. An issue whose sort key moves mid-paging (`sort:updated`, a rerank) may
  appear twice or not at all. This refines the grammar ticket's "stable while other writers
  commit". Rejected snapshot paging (reconstructs state from the event log) and invalidating
  cursors on change. Callers that need a fixed set sort by `created`, which never moves.

### Testing

SQLite is the only store implementation. Tests run against a real SQLite file in a temp
directory with the daemon's schema and code. Rejected an in-memory fake, which would
reimplement exactly the SQL worth testing.

### Handed on

- **Event log schema** is now its own ticket,
  [Define the event log schema](T14-event-log-schema.md), and it blocks the walking skeleton.
- **Schema upgrades** stay with
  [Daemon lifecycle and repo-to-project binding](T10-daemon-lifecycle.md). SQLite's
  `user_version` pragma makes either answer cheap.
- **Export** stays unmapped. `VACUUM INTO`, an atomic snapshot copy of the live store, is likely
  most of that answer.
- No ADR: SQLite is the unsurprising choice for a local tool, and this ticket carries the
  reasoning.

> **Refined by [Settle body and mention conventions](T15-body-and-mention-conventions.md):** mention edges may point
> at issues that don't exist yet (forward references), so the mention table references a project key and number, not an
> issue row. Titles are a mention source alongside bodies and comments, and creating a project rescans all prose for its
> key in the same transaction.
