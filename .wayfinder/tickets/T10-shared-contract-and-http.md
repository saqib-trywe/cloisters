---
id: T10
title: Define the shared contract and HTTP adapter
type: grilling
status: open
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
