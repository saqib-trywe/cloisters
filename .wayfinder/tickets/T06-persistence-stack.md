---
id: T06
title: Choose the persistence stack
type: grilling
status: open
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
