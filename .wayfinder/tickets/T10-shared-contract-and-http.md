---
id: T10
title: Define the shared contract and HTTP adapter
type: grilling
status: closed
assignee: saqib
blocked-by: [T07, T08, T09]
---

## Question

Tikka shared one tapir contract across daemon, UI and CLI, and derived the MCP schemas from it. What plays that role
in Clojure? `.cljc` namespaces with malli (or spec) schemas shared by daemon, CLI and UI? How are request validation,
JSON coercion, OpenAPI (if any) and MCP tool input schemas derived from one source? The REST shape is inherited from
[Design the HTTP API shape](../../docs/inherited/tickets/T09-http-api-shape.md); this ticket also adds the page
endpoints.

## Inherited from [Place cloisters' files in the XDG base directories](T03-xdg-file-locations.md)

`/api/meta` also reports the daemon's data directory, so the CLI can warn when it and the daemon resolve different
stores.

## Inherited from [Define page identity and linking](T04-page-identity-and-linking.md)

Issues and pages share one id space (`CLO-57`), and the web UI serves both at `/CLO-57`. A request for a page
as an issue, or the reverse, is refused naming what the id is: decide its error code and status. New refusals to
map: archiving a page with live children (naming them), a move forming a cycle, a move between projects.

## Inherited from [Define page revisions and edit conflicts](T05-page-revisions-and-conflicts.md)

Page writes take an optional expected version (the latest revision number) and an edit summary, required to
archive or unarchive. A stale page save returns the inherited `stale_version` details. Restore takes a revision
number and an expected version.

## Inherited from [Choose the persistence stack](T06-persistence-stack.md)

next.jdbc with HoneySQL over SQLite; rows come back as maps. Decide the core's map shape (namespaced keys such as
`:issue/title`, or unqualified) and where it converts to the contract's JSON names. Writes are serialized behind one
lock and return synchronously.

## Inherited from [Choose the MCP implementation](T07-mcp-implementation.md)

MCP is a hand-rolled Ring handler at `/mcp` ([ADR 0001](../../docs/adr/0001-hand-rolled-mcp.md)), so the HTTP server must
host a plain Ring handler beside the API and UI routes. Choose the JSON library it shares with the HTTP API, and derive
tool `inputSchema` and `outputSchema` from the shared contract (malli emits JSON Schema, per the survey). Drop nulls
from `structuredContent`.

## Inherited from [Choose the web UI approach](T08-web-ui-approach.md)

The web UI is server-rendered (hiccup 2, htmx 4) and is a third thin adapter calling the core directly, returning
HTML fragments, beside the HTTP API and MCP. It listens to the API's SSE event stream, so the HTTP server must host a
long-lived, resumable SSE response alongside plain Ring handlers. Static assets (CSS, vendored htmx) come from the
classpath.

## Inherited from [Choose the CLI approach](T09-cli-approach.md)

The CLI is babashka with bundled libraries only: no shared `.cljc`, no malli. The shared contract therefore spans
the daemon alone (core, HTTP, MCP, UI). The HTTP API must offer the **compact text rendering** MCP returns (a query
parameter or `Accept: text/plain`, to decide) beside JSON, since the CLI prints the daemon's text rather than
rendering its own. The CLI also sends the binding as a `Cloisters-Project` header (tikka's `Tikka-Project`).

## Resolution

Grilled with saqib, 2026-10-25, in two rounds; every recommendation accepted. Tikka's
[Design the HTTP API shape](../../docs/inherited/tickets/T09-http-api-shape.md) carries: MCP and HTTP (and now the UI)
as thin adapters over one core, REST for CRUD plus POST intent actions, the issue routes, the event feed and resumable
SSE stream, the `{error, message, …facts}` body with three statuses (404, 400, 409) and clients switching on `error`,
the paging envelope, no URL versioning, no CORS, localhost hardening. Its tapir definitions, SPA fallback and "JSON
only" do not.

### One contract, daemon only

- **malli 0.20.3 schemas in a daemon-only contract namespace** (plain `.clj`; the CLI shares no code), one per
  operation's arguments and result. They feed MCP `inputSchema` and `outputSchema` through `malli.json-schema` (regex
  patterns stringified) and the core's own argument validation. Rejected clojure.spec (alpha, no JSON Schema).
- **The core validates its arguments**, so MCP, HTTP, the UI and the REPL all get the same `invalid_argument` error.
  Adapters only turn JSON into maps and back. Rejected per-adapter coercion (reitit's malli coercion beside MCP's), two
  error formats, the trap tapir's decode-failure handler set for tikka. Malformed JSON still answers with the
  `invalid_argument` body, never a plain-text error.
- **Unqualified kebab-case maps in the core** (`:title`, `:expected-version`, kind as a `:kind` value), via next.jdbc's
  `as-unqualified-kebab-maps`, converted to the contract's snake_case once at the JSON edge through jsonista's key
  functions. Rejected namespaced keys, which split fields issues and pages share.

### HTTP stack

- **Ring 1.15.6 on Jetty 12** (`ring-jetty-adapter`), **reitit 0.11.0 for routing only**, **jsonista** for JSON in
  the API and MCP. Rejected http-kit (beta line) and Pedestal (interceptors rather than plain Ring).
- **SSE** is an async Ring handler streaming the event feed, resumable by `Last-Event-ID`, with a periodic comment
  line as keep-alive. The walking skeleton proves it.
- **No OpenAPI**: nothing consumes it.
- **Routing order:** `/api/…`, `/mcp`, `/assets/…` (unknown assets 404), then the UI's server-rendered routes
  including `/CLO-57`.
- **Text by content negotiation:** `Accept: text/plain` returns the compact text rendering MCP's text block carries;
  errors come back as text led by the code (`claim_conflict: …`) with the same status. JSON stays the default. Amends
  tikka's "JSON only".
- `/api/meta` reports the daemon version and data directory; the CLI sends `Cloisters-Project`.

### Page routes

| Operation | Route |
|---|---|
| get, optionally an old revision | `GET /api/pages/{id}?revision=3` |
| create | `POST /api/pages` |
| update: title, `body` or `body_edits`, parent, rank, summary, expected version | `PATCH /api/pages/{id}` |
| archive / unarchive (summary required) | `POST /api/pages/{id}/archive` · `/unarchive` |
| restore | `POST /api/pages/{id}/restore` `{revision, expected_version?, summary?}` |
| revisions | `GET /api/pages/{id}/revisions` |
| diff | `GET /api/pages/{id}/diff?from=&to=` |

Moving is a `PATCH` of parent, as for issues. Page search and tree routes belong to
[Extend the query grammar and search to pages](T12-query-grammar-for-pages.md).

**No kind-agnostic route.** The CLI's `show` and `edit` try the issue route and follow `wrong_kind`, which names the
kind: about 2 ms on a wrong guess, and the `item` table never leaks into the API.

### Errors for pages

Reuse where the meaning is the same; two new codes.

| Case | Code | Status | Carries |
|---|---|---|---|
| id of the other kind | **`wrong_kind`** | 404 | the id and what it is |
| archiving with live children | **`live_children`** | 409 | children as id and title |
| moving beneath itself | `cycle` | 409 | the path |
| parent in another project | `cross_project_edge` | 409 | both ids and projects |
| unknown revision | `not_found` | 404 | id and revision |
| missing archive summary | `invalid_argument` | 400 | |
| stale save | `stale_version` | 409 | current version, title and body as touched |

> **Amended by [Extend the MCP tool contract for pages](T11-mcp-tools-for-pages.md):** a new code, **`archived_parent`**
> (409, the parent as id and title), refuses unarchiving, moving or creating a live page beneath an archived parent.
