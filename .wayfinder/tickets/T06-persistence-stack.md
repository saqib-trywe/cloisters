---
id: T06
title: Choose the persistence stack
type: grilling
status: closed
assignee: saqib
blocked-by: [T01]
---

## Question

Which storage and data-access libraries does cloisters use?

Tikka's engine-level choices are inherited (SQLite readable without cloisters, one serialized writer, invariants in
the transaction). Re-open the engine only if the survey makes Datalevin a strong case under the learning goal.
Otherwise decide: next.jdbc with hand-written SQL or HoneySQL; the migration approach; full-text search over issues
and pages; how revisions are stored.

## Inherited from [Place cloisters' files in the XDG base directories](T03-xdg-file-locations.md)

The store is `$XDG_DATA_HOME/cloisters/cloisters.db` (or `$CLOISTERS_HOME/data/`), with snapshots in `backups/` and
`daemon.lock` beside it.

## Inherited from [Define page revisions and edit conflicts](T05-page-revisions-and-conflicts.md)

Revisions are not stored separately: a revision is the page after an event that changed its title or body, and
events keep full before and after text. Page events use tikka's `event`, `change` and `event_related` shape; a
restore records its source revision on the event. Per-page revision numbers are derived, so decide whether to store
them for cheap lookup.

## Resolution

Grilled with saqib, 2026-10-25, in one round plus a detour; every recommendation accepted. The engine-level choices
of [Choose the persistence engine](../../docs/inherited/tickets/T03-persistence-engine.md) carry unchanged: schema
posture, one serialized writer, `synchronous=FULL`, decimal ranks as doubles, daemon-clock ISO-8601 UTC text, FTS5
trigram `text:`, cursor guarantees, tests against a real SQLite file. Only the Scala access layer is replaced.

### Engine: SQLite, not reopened

- **Datalevin rejected.** The most Clojure-native option (Datalog, built-in CAS and full-text search), but its LMDB
  store is readable only through Datalevin, breaking "readable without cloisters", and its 1.2 line shipped seven patch
  releases in ten days. SQLite through next.jdbc 1.4.72 and sqlite-jdbc 3.53.4.0 was probed by the survey: FTS5
  compiled in, recursive CTEs, the claim as one `UPDATE … WHERE assignee IS NULL`.
- **Markdown files as the store considered and dropped.** A daemon owning md files in the data directory, held in an
  atom, would keep tikka's concurrency answer (tikka's objection was to files in the repo merged by git), but would
  have to rebuild atomic multi-record writes, crash safety, search and snapshots by hand. saqib chose to stay with
  SQLite.

### Access

- **HoneySQL 2.8.85 throughout**, over next.jdbc. The query grammar composes clauses at runtime, which is what SQL as
  data is for; `WITH RECURSIVE` is supported, and raw fragments (FTS5 `MATCH`) stay rare and local. One style, so a
  query reads one way. Rejected plain SQL strings beside it and HugSQL (quiet since 2022).
- **Connections:** a HikariCP read pool through `next.jdbc.connection/->pool`, and the one write connection behind a
  lock, so writes queue and each returns its result or refusal synchronously. Rejected Clojure agents for the writer
  (asynchronous) and a shared pool with busy retries.

### Migrations

**Hand-rolled**: numbered `.sql` resources applied in order against `PRAGMA user_version`, after the `VACUUM INTO`
snapshot, in one transaction at startup, refusing a newer store by naming both versions: exactly the inherited
behaviour, in about a dozen lines. Rejected ragtime and migratus, which track their own table, carry down migrations
cloisters refuses, and would need wrapping to snapshot and refuse.

### Schema for the shared id space

- **An `item` table keyed by (project, number) with a `kind`** (`issue` | `page`), allocated once in the creating
  transaction. `issue` and `page` reference it, so "an id names exactly one thing" is a key constraint, not only code.
  Event subjects, `event_related` and mention targets refer to (project, number), so one event log serves both kinds and
  "CLO-7 is a page" is one lookup. "Item" is a schema name, not a glossary term.
- **Revision numbers are stored**: content-changing page events carry a `revision` column set in the write
  transaction. A derived value, but assigned once on an immutable event, so it cannot drift; the page's version is its
  latest revision and r3 is a plain `WHERE`.
