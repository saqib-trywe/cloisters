---
id: T10
title: Define the shared contract and HTTP adapter
type: grilling
status: open
assignee: null
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
