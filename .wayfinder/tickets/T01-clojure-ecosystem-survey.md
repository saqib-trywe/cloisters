---
id: T01
title: Survey the Clojure ecosystem for cloisters
type: research
status: open
assignee: claude/clj-survey
blocked-by: []
---

## Question

What does the Clojure ecosystem offer today, October 2026, for each part of cloisters, and what is the maturity
signal for each option (versions, release dates, commit activity, notable users)?

Tikka's [Clojure survey](../../docs/inherited/tickets/T01-clojure-ecosystem-survey.md) from 2026-09-14 is the
starting point: re-verify it rather than trust it, and extend it.

1. **MCP**: pure-Clojure servers (`roklenarcic/mcp-server` and anything newer), the official Java SDK via interop,
   hand-rolled JSON-RPC on Ring. Which MCP spec revisions each speaks, and conformance-suite results.
2. **HTTP**: Ring adapters (Jetty, http-kit, others), routers (reitit and alternatives), SSE support.
3. **Persistence**: SQLite through next.jdbc (with or without HoneySQL), migration libraries, FTS5 availability
   in sqlite-jdbc; Datalevin as the Clojure-native alternative (durability, CAS, full-text search, inspectability).
4. **Web UI**: server-rendered (hiccup, HTMX, Datastar, and similar) versus ClojureScript SPAs (shadow-cljs with
   Replicant, UIx, re-frame, and similar), weighing build-toolchain cost.
5. **CLI**: babashka for a thin HTTP client (startup, bundled HTTP/JSON libs, distribution, `bbin`), and GraalVM
   native-image as the alternative.
6. **Markdown**: parsers usable from Clojure and ClojureScript (nextjournal/markdown, commonmark-java,
   flexmark, and others) that can tell code spans and blocks apart from prose and render safely with raw HTML off.
7. **Text diff** for page revisions, on the JVM and in the browser.
8. **Shared code**: what `.cljc` plus schema libraries (malli, spec) can share between daemon, babashka CLI and
   browser, and whether babashka can load them.

Landscape only: name the trade-offs, make no product decisions. Remember the map's note that learning the
idiomatic Clojure stack is part of the point, so flag which options are mainstream.

## Context

Findings are captured on the throwaway branch `research/clojure-ecosystem-survey`, in
`docs/research/clojure-ecosystem-survey.md`.
