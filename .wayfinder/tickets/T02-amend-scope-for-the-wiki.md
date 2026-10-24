---
id: T02
title: Amend scope and shape for the wiki
type: grilling
status: closed
assignee: saqib
blocked-by: []
---

## Question

Tikka's [Scope and shape](../../docs/inherited/tickets/T00-scope-and-shape.md) made the issue the only substantial
entity and refused attachments. Adding a wiki changes that. What is the wiki's scope floor and refusal list?

- Which page features are in: tree, markdown, revisions, page links and backlinks are settled at charting. What about
  labels on pages, comments on pages, templates, page ordering among siblings, a recent-changes view, watching?
- Attachments and images: still refused (link to assets), or in for pages only?
- Does every refusal in tikka's list still hold now that cloisters is also a wiki?
- What does "agent-first" mean for pages, given the UI editor exception recorded on the map?

## Resolution

Grilled with saqib, 2026-10-24, in one round; every recommendation accepted. The glossary changes are in
[CONTEXT.md](../../CONTEXT.md) (Page, Archived, Rank).

### Scope floor for pages

Already settled at charting: a page tree per project, markdown bodies, revisions, page links and backlinks. Added:

- **Sibling order by rank.** A page's rank orders it among its siblings, defaulting to creation order: the same
  decimal rank issues use. A handbook or build plan reads in a chosen order; alphabetical puts "Milestone 10" before
  "Milestone 2".
- **Archive.** No deletion holds for pages. A page leaves the way by being archived: a reversible flag that keeps its
  id, history, links and backlinks, and drops it from the default tree and search. The page-side equivalent of
  closing an issue as `dropped`. Rejected deletion (breaks the invariant and dangles links) and an "Archive" parent
  page by convention (stale pages keep surfacing in search, which is exactly what misleads an agent).
- **Recent changes, derived.** A listing of pages by updated time, with no state of its own.

### Refused for pages

- **Labels.** The tree organises pages; a parent page is the grouping.
- **Comments.** Discussion about a page goes in an issue that mentions it; the backlink joins them.
- **Templates** (a template is a page you copy) and **watching** (feeds only the refused notifications).
- **Attachments and images, still.** Cloisters never stores a binary. Markdown image syntax with an absolute URL
  renders as markdown does. Pages themselves absorb much of what attachments did: an asset can be a page.
- **Diagram rendering** (Mermaid and similar): the source shows as a code block.
- **Page permissions and restrictions.**
- **Drafts or a publish step**: saving is publishing.
- **Macros, embedded live queries, transclusion.**
- **Inline or anchored comments, likes and reactions.**
- **PDF or Word export.**
- **WYSIWYG editing.**
- **Structural edges between pages and issues**: no issue's parent is a page; prose links are the only bridge.
- **Page trees crossing projects** (already in the glossary).

Tikka's whole refusal list from [Scope and shape](../../docs/inherited/tickets/T00-scope-and-shape.md) holds
unchanged.

### Agent-first for pages

1. **MCP parity.** Anything the web UI can do to a page, MCP can.
2. **Markdown verbatim.** Pages go in and come out as the markdown written, never as rendered HTML.
3. **MCP wins** when a human surface and MCP disagree.

The UI's markdown editor (text area plus preview) is a deliberate human convenience, not a second authoring path MCP
lacks, and it covers pages only: issues keep tikka's light inline edits.

### Handed on

- [Define page identity and linking](T04-page-identity-and-linking.md): archive is decided; what happens to an
  archived page's children, and how moving interacts with sibling rank.
- [Define page revisions and edit conflicts](T05-page-revisions-and-conflicts.md): whether archiving, unarchiving
  and reordering make revisions; whether a revision carries a save message.
- [Choose the web UI approach](T08-web-ui-approach.md), [Extend the MCP tool contract for pages](T11-mcp-tools-for-pages.md),
  [Extend the query grammar and search to pages](T12-query-grammar-for-pages.md): recent changes, archived pages
  hidden by default, MCP parity.
