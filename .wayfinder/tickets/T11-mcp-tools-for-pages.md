---
id: T11
title: Extend the MCP tool contract for pages
type: grilling
status: closed
assignee: saqib
blocked-by: [T04, T05]
---

## Question

Which MCP tools do pages get, alongside tikka's nine issue tools? Read, create, edit, move, history and diff, search?
Holding tikka's rule that an intent tool earns its place by an invariant or a race, never by convenience, and keeping
the total tool count small. How is a page body returned (lists show compact rows, never bodies)?

## Inherited from [Amend scope and shape for the wiki](T02-amend-scope-for-the-wiki.md)

MCP parity: every page operation the web UI offers must exist here, including archive and unarchive and sibling
reordering. Bodies go in and come out as verbatim markdown, never rendered HTML. Pages take no labels and no
comments.

## Inherited from [Define page identity and linking](T04-page-identity-and-linking.md)

Pages share the issues' id space; a tool given the wrong kind is refused naming what the id is. Moves set a parent
(or none) and may set a rank; archiving refuses while live children exist and names them. Mention lists say when a
page is archived.

## Inherited from [Define page revisions and edit conflicts](T05-page-revisions-and-conflicts.md)

Revisions are numbered per page (r1, r2, …) and are a view over the page's events. Writes take `expected_version`
(optional) and `body_edits`, as issues do, plus an edit summary, required to archive or unarchive. Restore takes a
revision and makes a new one. Diff compares any two revisions of one page. Decide which of history, diff and restore
earn a tool under the invariant-or-race rule.

## Inherited from [Choose the web UI approach](T08-web-ui-approach.md)

The web UI can create a page (child or root), edit, move, reorder, archive and unarchive with a summary, and
restore a revision; MCP parity requires each of these.

## Inherited from [Define the shared contract and HTTP adapter](T10-shared-contract-and-http.md)

The core's page operations are get (optionally a revision), create, update (title, `body` or `body_edits`, parent,
rank, summary, expected version), archive, unarchive, restore, revisions and diff; decide which earn a tool. Schemas
are malli, feeding `inputSchema` and `outputSchema`, and the core validates. New error codes: `wrong_kind` (the id
and what it is) and `live_children`; `cycle`, `cross_project_edge`, `not_found`, `invalid_argument` and
`stale_version` are reused.

## Resolution

Grilled with saqib, 2026-10-25, in one round; every recommendation accepted. Tikka's
[Design the MCP tool contract](../../docs/inherited/tickets/T07-mcp-tool-contract.md) rule holds: a tool is basic CRUD or
enforces an invariant or race, different meanings get separate tools, never mode flags. The glossary's Archived entry
gains an invariant ([CONTEXT.md](../../CONTEXT.md)).

### Seven page tools

| Tool | Arguments | Returns | Why |
|---|---|---|---|
| `get_page` | `id`, `revision?`, `include?: ["revisions"]` | all fields, full body verbatim, version, parent, children as rows, archived, updated, mentions, backlinks; revisions on request | CRUD read |
| `create_page` | `title`, `project?`, `body?`, `parent?`, `rank?` \| `rank_before?` \| `rank_after?`, `summary?` | row + `version` | CRUD |
| `update_page` | `id`, `expected_version?`, `title?`, `body?` \| `body_edits?: [{old, new}]`, `parent?` (`null` makes a root), `rank?` \| `rank_before?` \| `rank_after?`, `summary?` | row + `version` | CRUD: edit, move and reorder, as `update_issue` |
| `archive_page` | `id`, `summary` (required) | row | refuses while live children exist; mirrors `close_issue` |
| `unarchive_page` | `id`, `summary` (required) | row | refuses under an archived parent; mirrors `reopen_issue` |
| `restore_page` | `id`, `revision`, `expected_version?`, `summary?` | row + `version` | records "restored from rN" as event data, which `update_page` cannot; spares re-sending an old body |
| `diff_page` | `id`, `from?`, `to?` (default previous against latest) | unified line diff of body, title before and after | the UI's diff; otherwise an agent pulls two bodies to compare |

**Sixteen tools** with the nine issue tools, before any search change from
[Extend the query grammar and search to pages](T12-query-grammar-for-pages.md). Each has one meaning; the inherited
"about 10" alarm was set for one entity. Rejected folding diff into `get_page` (one tool, two return shapes). Every UI
page action has a tool, so MCP parity holds.

### A new invariant: a live page never has an archived parent

[Define page identity and linking](T04-page-identity-and-linking.md) refused archiving with live children; three other
writes could still produce the state. **Unarchiving under an archived parent, moving a live page beneath one, and
creating a page beneath one are refused with `archived_parent`** (409), carrying the parent as id and title, as
`reopen_blocked` names a closed parent.

### Shapes

- **`get_page` always returns the full body**, verbatim: reading a page is reading its body. `revision` returns that
  revision's title and body. `include: ["revisions"]` adds number, time, who and summary for the newest 50, with a
  total, as `get_issue` returns events.
- **Page row:** id, title, parent, archived, rank, updated, revision; about 30–50 tokens, never a body.
- **Mixed kinds in mention lists.** `get_issue`'s and `get_page`'s mentions and backlinks carry each entry's kind: an
  issue shows its status, a page `page` or `archived`. Text renders `CLO-57  page  Glossary`. Amends `get_issue`'s
  edge lists.
- **Tools on the wrong kind** refuse with `wrong_kind`, naming what the id is.

### Retries

Archiving an archived page and unarchiving a live one succeed with no change; restoring content identical to the
current is a no-op. `create_page` is not idempotent, and its description says to search before retrying.

> **Amended by [Extend the query grammar and search to pages](T12-query-grammar-for-pages.md):** `search_issues`
> becomes `search`, over issues and pages, with mixed rows carrying their kind. Sixteen tools.
