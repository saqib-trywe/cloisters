---
id: T14
title: Extend export and durability to pages
type: grilling
status: closed
assignee: saqib
blocked-by: [T10]
---

## Question

Tikka's [Decide export and durability](../../docs/inherited/tickets/T17-export-and-durability.md) covers issues only.
Snapshots, restore and `quick_check` act on the whole store, so they cover pages for free; confirm nothing about pages
changes that (restore puts back pages and issues together, which is what it should do?).

The open part is export: `daemon export` writes JSON Lines in the contract's own types. How do pages appear: one
record per page with its current state, plus its events (from which every revision can be read, per
[Define page revisions and edit conflicts](T05-page-revisions-and-conflicts.md)), or each revision written out in full
as well? In what order do issue and page records interleave, given they share one id sequence? Blocked on
[Define the shared contract and HTTP adapter](T10-shared-contract-and-http.md), which fixes the page types.

## Inherited from [Define the shared contract and HTTP adapter](T10-shared-contract-and-http.md)

The contract's types are malli schemas over unqualified maps, written as snake_case JSON at the edge; page
records are the `GET /api/pages/{id}` shape.

## Resolution

Grilled with saqib, 2026-10-25, in one round; every recommendation accepted. Tikka's
[Decide export and durability](../../docs/inherited/tickets/T17-export-and-durability.md) carries unchanged, at the
locations from [Place cloisters' files in the XDG base directories](T03-xdg-file-locations.md): offline
`cloisters daemon export`, no import, daily `VACUUM INTO` snapshots with 7 kept plus migration snapshots, `daemon
restore` with the daemon stopped, `quick_check` at startup and on each snapshot.

- **Snapshots, restore and `quick_check` cover pages with no change**: pages live in the same SQLite file. A restore
  puts issues and pages back together, as one store at one moment. Bringing back one page's earlier content is
  `restore_page`, not a store restore; there is no restore by kind.
- **Page records:** one `page` record per page with its current state, in the `get_page` shape (title, body, parent,
  rank, archived, version). Page events are exported like issue events, content-changing ones carrying their
  `revision`; events hold full before and after text, so every revision is readable from the export. Rejected writing
  each revision out again in full.
- **Record types:** every line has a `type`: `project`, `issue`, `page`, `event`. Comments stay events.
- **Order:** projects; then issues and pages interleaved by (project, number), which is creation order since they share
  one sequence; then all events by global sequence, so the log reads as the log. Deterministic: two exports of the same
  store are byte-identical.
