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
