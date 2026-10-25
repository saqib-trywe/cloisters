---
id: T08
title: Choose the web UI approach
type: grilling
status: closed
assignee: saqib
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

## Resolution

Grilled with saqib, 2026-10-25, in two rounds; every recommendation accepted. Tikka's view decisions in
[Shape the Laminar frontend](../../docs/inherited/tickets/T05-web-ui-approach.md) carry: ready as a search, the issue
tree, issue detail with blockers expanding in place, `/projects`, no project picker, the query only in the URL and the
search box, lists that never reshuffle under the reader, the same inline issue edits, no node-and-edge diagrams. Its
build, Laminar, Waypoint and Laika choices do not. This also clears the map's fog patch "Mention and link detection
without Laika".

### Stack

- **Server-rendered: hiccup 2 with htmx 4.** Zero front-end toolchain: no ClojureScript compiler, no npm. One
  markdown parser and one diff, both on the daemon, so the UI can never link what the daemon didn't count. The editor
  preview is a localhost round trip. **Accepted cost: no ClojureScript** in this project; rejected a shadow-cljs SPA
  (re-frame or Replicant) for a second compile target, Node, and two markdown engines (commonmark-java and markdown-it)
  that agree only as far as two CommonMark implementations do. Rejected Datastar (SDK still RC, more than the live
  updates need).
- **The UI is a thin adapter over the core**, beside the HTTP API and MCP: its handlers call the core and return HTML
  fragments, never the JSON API. Writes record `web`.
- **Markdown: nextjournal/markdown 0.7.228**, data tree in, hiccup out. Mention detection walks the tree skipping
  `:monospace` and `:code`. **Our own renderer** escapes raw HTML as text and allows only `http`, `https`, `mailto` and
  site-relative link and image URLs (nextjournal passes `javascript:` through); the allow-list has a test. Rejected
  commonmark-java directly (Java visitor API, HTML strings rather than hiccup).
- **Diff: java-diff-utils 4.18** on the daemon, the one implementation behind every surface: unified text for MCP and
  the CLI, side-by-side rows with word highlights for the UI.
- **Live updates use the API's one SSE event stream**: about 20 lines of plain JS turn relevant events into htmx
  triggers that re-fetch fragments by ordinary GET. Rejected a second, HTML-pushing stream.
- **Assets:** hand-written CSS, htmx vendored as one file in `resources/`, served from the classpath. The dev loop is
  REPL namespace reloads plus a browser refresh.

### Pages in the UI

- **Routes:** `/tree/CLO/pages` (page tree; `/tree/CLO` stays the issue tree), `/CLO-57` (page view: rendered body,
  children, backlinks from issues and pages), `/CLO-57/history` (revisions with edit summaries), `/CLO-57/r3`,
  `/CLO-57/diff?from=2&to=5` (defaulting to the previous revision), `/CLO-57/edit`. `/projects` gains page counts.
  `/CLO-57` routes by what the id names.
- **Actions, fuller than for issues** because the editor exception exists for them: create (child or root), edit,
  move (new parent by id, or none), reorder up and down, archive and unarchive (edit summary required), restore a
  revision. MCP must offer each one.
- **Bare page ids render as issue mentions do**: the id as a link, title on hover; archived pages muted and marked
  "archived". Writers wanting prose use `[text](/CLO-57)`.
- **Editing:** the editor never touches the human's text. When an event changes the page being edited, a banner
  appears ("Changed since you opened it, by mcp:claude-code") linking the diff, so the conflict is known before saving;
  the stale-save refusal remains the guard and keeps the text for a hand merge. A page being read refreshes in place.
