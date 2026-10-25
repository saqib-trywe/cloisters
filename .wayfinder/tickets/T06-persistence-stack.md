---
id: T06
title: Choose the persistence stack
type: grilling
status: open
assignee: null
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
