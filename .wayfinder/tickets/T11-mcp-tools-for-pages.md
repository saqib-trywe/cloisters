---
id: T11
title: Extend the MCP tool contract for pages
type: grilling
status: open
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
