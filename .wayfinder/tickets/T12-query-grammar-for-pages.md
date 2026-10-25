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

## Inherited from [Define page identity and linking](T04-page-identity-and-linking.md)

Pages share the issues' ids, so `mentions:CLO-57` and backlinks may name either kind. Titles are not unique, so a
title search can return several pages.

## Inherited from [Define page revisions and edit conflicts](T05-page-revisions-and-conflicts.md)

A page's updated time is its latest event, which includes moves (on the old and new parent too) and archiving, not
only edits: recent changes follows from that.

## Inherited from [Choose the persistence stack](T06-persistence-stack.md)

The query grammar compiles to HoneySQL. FTS5 trigram (`text:` as substring match) is inherited; decide whether
pages share the issues' FTS table or have their own, which follows from one search or two.
