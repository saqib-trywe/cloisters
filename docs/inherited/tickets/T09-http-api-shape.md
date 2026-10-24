---
id: T09
title: Design the HTTP API shape
type: grilling
status: closed
assignee: saqib
blocked-by: [T07]
---

## Question

The HTTP API serves the web UI and the CLI, and the MCP server sits on the same core.
The question is what relationship those bear to each other.

- **Is HTTP the base layer that MCP adapts, or are both thin adapters over an internal
  core API?** The second is cleaner and keeps MCP from inheriting REST's shape, but it
  means three things to keep in step instead of two. Decide deliberately — this is the
  "one core, four surfaces" claim being cashed in or quietly abandoned.
- Does the HTTP API **mirror the MCP tool contract** (an endpoint per tool, including
  intent operations like claim and close), or is it resource-shaped REST with the intent
  operations expressed as sub-resources?
- **Content negotiation**: the web UI wants HTML, the CLI wants data. One set of routes
  serving both, or separate route trees?
- **Local-only consequences.** No auth was settled, so what stops a browser page on
  another origin from talking to the daemon? CORS and origin checks are the one piece of
  security that still matters on localhost, and it is easy to skip by accident.
- **Versioning**: does a local-only API need any, or is that ceremony to refuse?

Depends on [Design the MCP tool contract](T07-mcp-tool-contract.md) — the MCP contract is
primary, so HTTP is designed against it rather than the reverse.

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
listing takes one `?q=` parameter in the shared syntax and returns an opaque next cursor.
No per-filter query parameters.

**Inherited from [Design the MCP tool contract](T07-mcp-tool-contract.md):**
the MCP contract is the product's API; the HTTP API should mirror its nine operations, argument
names, row/detail split and error codes rather than invent a parallel vocabulary. Open here is
only the HTTP projection: routes and verbs, status codes per error code, and how the bound
project reaches the daemon from the CLI.

**Inherited from [Define the event log schema](T14-event-log-schema.md):**
the HTTP API exposes a cross-issue event feed ordered by a global sequence ("events after N"),
which MCP does not have; decide whether it is a paged endpoint, an SSE stream, or both, and how
a client resumes after a disconnect. Full issue history (beyond MCP's latest 50) and full
before/after text for prose changes are served here.

**Inherited from [Choose the MCP server implementation and transport](T04-mcp-implementation.md):**
localhost hardening applies to the API too: `127.0.0.1` only, a foreign `Origin` rejected, `Host`
validated against DNS rebinding. MCP lives at `/mcp` on the same port, so pick API paths that don't
collide (`/api/…`). Argument and result types are shared with MCP and schemas are derived from them,
so the API's request and response bodies should be those same types.

## Resolution

Decided 2026-09-15.

### Layering

**MCP and HTTP are both thin adapters over one internal core.** The core is a Scala service
whose operations are the tool contract's nine plus what only HTTP serves (event feed, full
history). Each adapter decodes the request, calls the core and renders the result, sharing the
core's argument and result types. Rejected HTTP-as-base-layer, which would give MCP REST's shape,
backwards for an agent-first product. This is the "one core, four surfaces" claim, cashed in.

### Routes

REST for plain CRUD, POST actions for intent operations. Bodies are the contract's argument types
without `id`.

| Operation | Route |
|---|---|
| `search_issues` | `GET /api/issues?q=&cursor=&limit=` |
| `get_issue` | `GET /api/issues/{id}?include=events` |
| `create_issue` | `POST /api/issues` |
| `update_issue` | `PATCH /api/issues/{id}` |
| `claim_issue` / `release_issue` / `reassign_issue` | `POST /api/issues/{id}/claim` · `/release` · `/reassign` |
| `close_issue` / `reopen_issue` | `POST /api/issues/{id}/close` · `/reopen` |
| issue history | `GET /api/issues/{id}/events?cursor=`: full history, full before/after text |
| event feed | `GET /api/events?after=N&limit=` |
| live events | `GET /api/events/stream?after=N`: SSE, each message's `id:` is the event sequence |
| daemon info | `GET /api/meta`: daemon version |

Rejected pure RPC (`POST /api/call/claim_issue`), which gives up linkable reads, and pure REST, which would
make claiming a `PATCH` to the assignee and bring back the generic assignee update the invariants
refused.

### Definitions

**Endpoints are defined once with tapir in the shared cross-compiled module.** They are interpreted to
http4s routes on the daemon and to typed sttp clients for the Laminar UI (Scala.js) and the CLI
(Scala Native). **tapir's `Schema` derivation also produces the MCP `inputSchema`/`outputSchema`**,
settling the derivation library that
[Choose the MCP server implementation and transport](T04-mcp-implementation.md) left open. OpenAPI
comes for free. Rejected the plain http4s DSL with hand-written clients: three consumers of one API is where
drift bites. Accepted cost: tapir as a second vocabulary over http4s.

### Behaviour

- **JSON only.** The Laminar SPA renders all HTML. The daemon serves the SPA's `index.html` for any
  path outside `/api/…`, `/mcp` and static assets, so deep links like `/i/TIK-42` open correctly. No
  content negotiation.
- **Errors:** the contract's `{error, message, …facts}` body verbatim, with three statuses: **404**
  (`not_found`, `unknown_project`), **400** (`invalid_query`, `invalid_argument`, `edit_mismatch`), **409**
  (every invariant or concurrency rejection, including `stale_version`). Clients switch on `error`,
  never on status. Rejected fine-grained statuses (412/422/423), which invite branching that drifts from the
  codes.
- **Paging:** the same envelope as MCP, `{effective_query, issues, next_cursor, has_more}`.
- **Bound project:** a `Tikka-Project: TIK` request header, set by the CLI from the repo binding. It applies
  to unscoped searches and creates; an explicit project always wins; `effective_query` shows the scoping that was applied.
  The web UI sends none; how it handles multiple projects stays unmapped.
- **Versioning:** none in URLs. `GET /api/meta` reports the daemon version, and the CLI warns on a mismatch
  (the only real skew: a new CLI against a still-running old daemon).
- **Browser safety:** no CORS headers at all, since the UI is same-origin. The localhost hardening from the MCP
  implementation (`127.0.0.1` only, a foreign `Origin` rejected, `Host` validated) is the whole story.

> **Amended by [Walking skeleton — surfaces over one core](T11-walking-skeleton.md):** tapir's default
> decode-failure handler answers with plain text, breaking the error contract. Install a custom handler returning the
> `invalid_argument` error body from the first commit. Unknown `/assets/…` paths must return 404, not the SPA fallback.
