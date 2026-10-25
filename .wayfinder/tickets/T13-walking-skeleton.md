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
