---
id: T12
title: Extend the query grammar and search to pages
type: grilling
status: closed
assignee: saqib
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

## Inherited from [Choose the web UI approach](T08-web-ui-approach.md)

The UI's views are queries in the URL (`/?q=…`); decide the query that gives recent changes, so it is a link
like any other view.

## Inherited from [Define the shared contract and HTTP adapter](T10-shared-contract-and-http.md)

Page routes exist for get, create, update, archive, unarchive, restore, revisions and diff under `/api/pages`;
search and tree routes are yours, in the same REST shape and paging envelope.

## Inherited from [Extend the MCP tool contract for pages](T11-mcp-tools-for-pages.md)

Sixteen tools so far: nine issue tools plus `get_page`, `create_page`, `update_page`, `archive_page`,
`unarchive_page`, `restore_page`, `diff_page`. Searching pages is yours: extend `search_issues`, rename it, or add
`search_pages`. Page rows are id, title, parent, archived, rank, updated, revision; mention lists carry each entry's
kind.

## Resolution

Grilled with saqib, 2026-10-25, in two rounds; every recommendation accepted. Tikka's
[Define the shared query and filter grammar](../../docs/inherited/tickets/T08-query-grammar.md) carries: one text syntax on
every surface, AND across filters, OR within one, `-` negation, no parentheses, strict rejection naming the token with
suggestions, cursor paging (default 50, max 200), total order with ties by number, relative and ISO times, `ready`
exactly `status:open unblocked assignee:none`, `assignee:` prefix matching.

### One search

- **`search_issues` becomes `search`**, over issues and pages; the route is **`GET /api/search?q=`**, replacing
  `GET /api/issues?q=`. The tool count stays at sixteen. The description still leads with `ready`, "start here to find
  work". Rejected a separate `search_pages`: every cross-kind question would take two calls, and shared ids already make
  issues and pages one namespace.
- **Mixed rows, each carrying its kind**; text renders `CLO-57  page  Glossary`. The paging envelope is
  `{effective_query, results, next_cursor, has_more}` (`issues` renamed).

### Filters by kind

| Filter | Issues | Pages |
|---|---|---|
| `kind` (new: `issue`, `page`) | ✓ | ✓ |
| `project`, `id`, `parent`, `under`, `mentions`, `mentioned-by`, `text`, `created-`/`updated-` + `before`/`after` | ✓ | ✓ |
| `archived` (new: `no` default, `yes`, `any`) | | ✓ |
| `status`, `resolution`, `assignee`, `label`, `blocked`, `unblocked`, `blocks`, `blocked-by`, `ready`, `closed-`/`claimed-` + `before`/`after` | ✓ | |

- **An issue-only filter implies `kind:issue`; `archived:` implies `kind:page`.** `effective_query` shows the implied
  kind, so `ready parent:CLO-1` means exactly what wayfinder expects.
- **Contradictions are rejected** by the inherited strictness, naming the clash: `kind:page label:bug` gives "`label:`
  applies only to issues".
- `parent:` and `under:` follow the named id's kind; `mentions:CLO-57` matches text of either kind.
- **Archived pages are excluded by default** (`archived:no`). An explicit `id:` list matches them anyway: naming a page
  is asking for it. Mention and backlink lists always include archived pages, marked.

### Text, sort and views

- **`text:`** stays a case-insensitive substring match over an issue's title, body and comments and a page's title and
  body, in **one FTS5 trigram table over both kinds**, keyed by item. No relevance ranking.
- **Sort:** `rank` stays the default; for pages it orders siblings, so a flat page list by rank is deterministic but
  means little outside a tree. `sort:closed` implies `kind:issue`.
- **Recent changes** is `kind:page sort:updated-desc`, linked from the UI's navigation; `sort:updated-desc` alone is
  recent changes across both kinds.
- **No tree route.** A tree is `kind:page project:CLO` or `under:CLO-57` sorted by rank, assembled by the UI and CLI
  from each row's parent and rank, as tikka's issue tree was. A large tree pages like any query.
