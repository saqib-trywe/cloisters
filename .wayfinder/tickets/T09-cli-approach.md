---
id: T09
title: Choose the CLI approach
type: grilling
status: open
assignee: null
blocked-by: [T01]
---

## Question

How is the CLI built and distributed: babashka script, babashka uberscript or `bbin` install, or GraalVM
native-image? What startup gate does CI enforce (tikka's was a 100 ms median)? Which page commands does it gain
(including the `$EDITOR` flow for pages)? The `cloisters mcp` stdio proxy is inherited and must fit the same
approach.

## Inherited from [Place cloisters' files in the XDG base directories](T03-xdg-file-locations.md)

Config is `config.edn` and the repo binding a committed `.cloisters` file (`{:project "CLO"}`), both EDN.
`CLOISTERS_HOME` relocates everything; otherwise the XDG variables apply on macOS and Linux. `daemon install` writes
the resolved locations into the service unit, and the CLI warns when `/api/meta` reports a data directory other than
its own. Default port 7227.

## Inherited from [Choose the web UI approach](T08-web-ui-approach.md)

Markdown is parsed and diffs are produced only on the daemon (nextjournal/markdown, java-diff-utils), so the CLI
never parses prose or diffs text: it prints what the daemon returns.
