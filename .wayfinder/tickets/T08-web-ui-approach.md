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
