---
id: T12
title: Extend the query grammar and search to pages
type: grilling
status: open
assignee: null
blocked-by: [T04]
---

## Question

How does tikka's [query grammar](../../docs/inherited/tickets/T08-query-grammar.md) reach pages? One search across
issues and pages, or separate searches? Which filters apply to pages (project, parent or subtree, linked-from,
mentions, updated)? How are mixed results ranked and paged?

## Inherited from [Amend scope and shape for the wiki](T02-amend-scope-for-the-wiki.md)

Archived pages are excluded from search unless asked for. Recent changes is a listing of pages by updated time,
derived with no state of its own; decide whether it mixes issues and pages. Pages carry no labels, so no label
filters apply to them.
