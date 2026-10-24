---
id: T05
title: Shape the Laminar frontend
type: grilling
status: closed
assignee: saqib
blocked-by: [T13]
---

## Question

Laminar is decided — see [Adopt Scala with the Typelevel stack](T12-adopt-scala.md). The
open question is how the frontend is structured around it.

The UI's job is already fixed: a **reading and reviewing** surface — lists, filters,
issue detail, a ready/graph view, the event timeline — plus light inline edits.
Explicitly not a form-heavy authoring tool. Every structural choice below should be
judged against that narrow job, not against what a general-purpose SPA would want.

- **Shared code.** Which modules cross-compile between JVM and Scala.js — domain types
  only, or also the query/filter grammar and validation? This is the reason Laminar was
  chosen, so it should be decided deliberately rather than grown.
- **API client.** Hand-written fetch calls against the HTTP API, or endpoint definitions
  shared with the server (e.g. via tapir) generating a typed client? The latter makes
  server/client drift a compile error, at the cost of pulling tapir into an http4s stack.
- **JSON codec** that cross-compiles, and its bundle-size cost on Scala.js.
- **Build toolchain.** sbt, scala-cli or mill, plus Vite for dev serving — and how the
  daemon serves the built assets in production. Count every build step you would still
  have to keep working a year from now.
- **Live updates.** Does the UI update as agents mutate state underneath the viewer? If
  yes, that means a push channel (SSE over http4s via fs2), which changes the API design.
- **Graph view.** Does the ready/graph view need a JS diagramming library via
  Scala.js facades, or does a Laminar-rendered ordered list carry it?
- **Routing** within the SPA, and whether issue URLs (`/i/TIK-42`) are served by the
  daemon directly so links from agents and CLI output open correctly.

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
the UI is a search box over the shared syntax, with controls editing the string and the URL
carrying it; the query parser must cross-compile to Scala.js so the UI validates locally;
paging is "load more" over cursors.

**Inherited from [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md):**
tapir-sttp-client4 publishes for Scala.js, so shared endpoint definitions give a typed Laminar client.
Laminar stable is 17.2.1 (2025-03) and the active line is 18.0.0-M5, with Waypoint 10.0.0-M7; pick
one deliberately. vite-plugin-scalajs has had no release since 2025-08-04. circe and jsoniter-scala
both cross-compile, but bundle sizes are unmeasured; measure in the skeleton. http4s-dom has stalled.

**Inherited from [Define the event log schema](T14-event-log-schema.md):**
live updates have a natural source, a global event sequence the client can resume from. The
timeline has full before/after text for titles and bodies, so the diff view is rendered client-side.
An event can appear on several timelines (subject, edge counterparts, unblocked dependents),
so the UI should show which issue a related event was actually written to.

**Inherited from [Design the HTTP API shape](T09-http-api-shape.md):**
the API client question is settled: tapir endpoints in the shared module give the UI a typed
sttp client. The daemon serves `index.html` for any path outside `/api`, `/mcp` and static assets,
so SPA routes like `/i/TIK-42` work as deep links. The API is JSON only. An SSE stream
(`/api/events/stream`, resumable via `Last-Event-ID`) exists; whether the UI goes live is still
yours. The UI sends no `Tikka-Project` header, and multi-project UX stays unmapped.

**Inherited from [Daemon lifecycle and repo-to-project binding](T10-daemon-lifecycle.md):**
the web UI never creates projects (CLI only); it lists them. The UI is served by the daemon on its
fixed, configurable port at `127.0.0.1`.

## Resolution

Decided 2026-09-15. This also settles two unmapped items from the map: **the ready/graph view** and **multi-project UX**.

### Build

- **sbt 2.0** with cross-projects for the JVM, JS and Native, plus `sbt-assembly`. Every plugin needed (`sbt-scalajs` 1.22.0,
  `sbt-scala-native` 0.5.12, both cross-project plugins 1.4.0, `sbt-assembly` 2.5.0) publishes for sbt 2.
  Rejected Mill (fast, but most relevant libraries document sbt) and scala-cli (no multi-module cross builds).
- **No npm, no Vite.** Dev: `sbt ~ui/fastLinkJS` while a dev-mode daemon serves the linker output from disk;
  refresh the browser. Production: `fullLinkJS` output embedded in the assembly jar, served from the classpath.
  **Plain hand-written CSS.** Revisit only if a JS library becomes necessary. Rejected Vite: a stalled plugin
  (last release 2025-08-04) plus a Node toolchain, to buy hot reload for a reading-and-reviewing UI.
- **Laminar 18 and Waypoint 10**, starting on the current milestones (18.0.0-M5 / 10.0.0-M7) and moving to
  18.0.0 when it ships (release notes were drafted 2026-08-31). Starting on 17.2.1 would mean a
  guaranteed migration within weeks.
- **circe everywhere**, matching the MCP layer. The skeleton measures and records the `fullLinkJS` size,
  but there is no size budget: localhost makes bundle size a curiosity.

### Shared module

**The contract, and no business logic:** opaque domain types, argument and result types, error codes, tapir
endpoints and codecs, the query parser and its ADT, the compact text renderer, and **one hand-written line
diff** used by both MCP's renderer and the UI's timeline (no Scala diff library cross-builds to all three
platforms). Invariant checks, SQL and the core service stay JVM-only, since only the daemon sees the state
invariants depend on. The UI validates queries locally with the shared parser; that's safe because the UI ships
inside the daemon.

### Views

- **Ready:** the search view with `ready`. No separate page.
- **Tree:** hierarchy as an indented, collapsible tree, each row marked open, closed, claimed or blocked.
- **Issue detail:** fields, rendered body, edges as lists (parent, children, blocked by, blocks, mentions,
  backlinks), comments and timeline. **Each blocker expands in place to show its own blockers**, so a
  dependency chain can be walked without leaving the page.
- **`/projects`:** each project with open and ready counts, linking to `project:KEY ready` and to its tree.
- **No node-and-edge diagrams.** Lists answer "what's ready", "what's under this" and "why is this blocked"; diagrams
  over about 20 nodes turn to spaghetti; and a diagram library is the JS dependency that would bring back npm.

### Multi-project UX

**No project picker; scope is part of the query.** The landing view is your last query, or `ready`
across all projects on first visit. `project:TIK` scopes a view, and every row shows its id, so the project is
always visible. Rejected a project switcher: hidden state that makes "why can't I see TIK-3?" possible.

### Routes

Waypoint, all deep-linkable through the daemon's SPA fallback: `/?q=<query>`, `/i/TIK-42`, `/tree/TIK`
(project roots), `/tree/TIK-5` (subtree), `/projects`. **The query lives only in the URL and the search
box**, so any view you can see is a link you can paste to an agent.

### Live updates

**On, via the SSE event stream, but lists never reshuffle under the reader.** Issue detail refreshes in
place when an event names the issue (as subject or related issue) and its timeline grows. Lists show an
"N updates — refresh" bar instead of reordering. Reconnection resumes from the last seen sequence.
Rejected fully automatic lists (you click the wrong row) and no live updates (a window that doesn't move).

### Inline edits

- **Allowed:** comment; add or remove labels; close or reopen (comment required); move up or down in rank
  (`rank_before`/`rank_after`); reassign, including to nobody (clearing a stale claim).
- **Not in the UI:** creating issues, editing title or body (`tikka edit` has the version-guarded `$EDITOR`
  flow), editing edges, claiming (the UI has no identity to claim as), creating projects.
- UI writes record their actor as `web`.

### Prose

**Laika on Scala.js renders markdown in the browser**, with raw HTML off (its default), so an agent-written
`<script>` shows as text. The API stays pure JSON. Whether `TIK-42` in prose becomes a live link stays with the
map's **body and mention conventions**.

> **Refined by [Settle body and mention conventions](T15-body-and-mention-conventions.md):** Laika lives in a
> JVM+JS module (it has no Scala Native build) shared by the daemon's mention detection and the UI's renderer. Mentions
> render as status-styled links with title tooltips, ids that don't exist yet are muted, and code is never linked.
