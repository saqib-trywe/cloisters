---
id: T11
title: Extend the MCP tool contract for pages
type: grilling
status: open
assignee: null
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
