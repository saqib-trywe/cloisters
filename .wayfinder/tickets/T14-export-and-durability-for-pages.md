---
id: T14
title: Extend export and durability to pages
type: grilling
status: open
assignee: null
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
