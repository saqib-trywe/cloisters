---
id: T08
title: Choose the web UI approach
type: grilling
status: open
assignee: null
blocked-by: [T01, T02]
---

## Question

How is the web UI built: server-rendered hiccup with HTMX (or similar), or a ClojureScript SPA, and with which
libraries and build tooling? It must cover tikka's ready, tree and detail views with live updates, plus the page
tree, page view with backlinks, revision history and diff, and the markdown page editor. Markdown must render the
same way, with the same mention and link detection, as the daemon.

## Inherited from [Amend scope and shape for the wiki](T02-amend-scope-for-the-wiki.md)

The page editor is a text area plus preview, pages only; no WYSIWYG, no upload control, no diagram rendering
(source shows as a code block). Images with absolute URLs render as markdown does. Needs a recent-changes listing
(pages by updated time) and hides archived pages by default. Anything the UI can do to a page, MCP can.

## Inherited from [Define page identity and linking](T04-page-identity-and-linking.md)

Every issue and page lives at `/CLO-57`; the UI routes by what the id names. Decide how a bare page id renders
(id with a title on hover as issues get, or the title inline). Links to archived pages render muted and marked
"archived".

## Inherited from [Define page revisions and edit conflicts](T05-page-revisions-and-conflicts.md)

The page editor always sends the version it loaded. On a stale save it keeps the human's text, shows a diff of what
changed underneath, and lets the human merge by hand and resave. History lists revisions (r1, r2, …) with edit
summaries; the diff view compares any two, defaulting to the previous, using the shared line-diff implementation.
Archiving and unarchiving ask for an edit summary.
