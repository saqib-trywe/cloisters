---
id: T13
title: Walking skeleton: surfaces over one core, issues and pages
type: prototype
status: open
assignee: null
blocked-by: [T03, T04, T06, T10]
---

## Question

Does the chosen Clojure stack hold together? Build a throwaway walking skeleton: one daemon serving HTTP, MCP and the
web UI over one core and store in the XDG locations, plus a separate thin CLI, round-tripping one issue and one page
(create, read, link, backlink) through every surface, and served to a real Claude Code MCP client.

Like tikka's [walking skeleton](../../docs/inherited/tickets/T11-walking-skeleton.md), record what it corrects in the
decisions it was built on.

## Inherited from [Place cloisters' files in the XDG base directories](T03-xdg-file-locations.md)

Run the skeleton under `CLOISTERS_HOME` (a temporary home, port 7228 or similar) so it never touches the real XDG
locations or the live tikka on 7117.

## Inherited from [Define page identity and linking](T04-page-identity-and-linking.md)

Round-trip a mention in each direction through the shared id space: a page mentioning an issue and an issue
mentioning a page, each showing the backlink, both at `/CLO-N`.

## Inherited from [Choose the persistence stack](T06-persistence-stack.md)

Exercise the persistence stack end to end: the hand-rolled `user_version` migration runner with its snapshot,
the `item` table allocating one number to an issue and the next to a page, HoneySQL over next.jdbc, the read pool
plus locked write connection, and a stored revision number.

## Inherited from [Choose the MCP implementation](T07-mcp-implementation.md)

Serve the hand-rolled MCP handler to a real Claude Code client at 2026-07-28 and run the conformance suite
(`0.2.0-alpha.12`) at both revisions against the skeleton.

## Inherited from [Choose the web UI approach](T08-web-ui-approach.md)

The UI is server-rendered hiccup with htmx: render one page through nextjournal/markdown with a mention of an
issue linked, show its backlink, and edit it through the version-guarded editor.

## Inherited from [Choose the CLI approach](T09-cli-approach.md)

The CLI is a babashka script installed with `bbin`, using bundled libraries only: round-trip an issue and a page
through it, printing the daemon's text rendering, and measure `cloisters search` with `hyperfine` against the 100 ms
gate.

## Inherited from [Define the shared contract and HTTP adapter](T10-shared-contract-and-http.md)

Ring on Jetty 12, reitit for routing only, jsonista, malli contract validated by the core. Prove the async SSE
handler resumes by `Last-Event-ID`, that `Accept: text/plain` returns MCP's text rendering, that malformed JSON gets
the `invalid_argument` body, and that `wrong_kind` lets the CLI's `show` find a page.

## Inherited from [Extend the MCP tool contract for pages](T11-mcp-tools-for-pages.md)

Call `create_page`, `get_page` and `update_page` from Claude Code over MCP, and see an issue's `get_issue`
list the page among its backlinks with kind `page`.
