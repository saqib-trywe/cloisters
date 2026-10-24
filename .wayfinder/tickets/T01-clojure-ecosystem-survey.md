---
id: T01
title: Survey the Clojure ecosystem for cloisters
type: research
status: closed
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

## Resolution

Surveyed 2026-10-24 by a background research agent. The full landscape, sources and "(was: …)" corrections to
tikka's survey are in `docs/research/clojure-ecosystem-survey.md` on branch `research/clojure-ecosystem-survey`
(`f596fcc`). Landscape only; the decisions belong to the tickets this one unblocks. The facts they hinge on:

1. **MCP.** PluMCP 0.3.0 (new, Ring-mountable, `.cljc`, 2025-11-25 spec) and `roklenarcic/mcp-server` 0.3.61
   (alpha, unchanged since September) are the Clojure options; neither publishes conformance results. The Java SDK's
   2.2.0-M1 milestone passes 2026-07-28 conformance, but its stable line targets 2025-11-25 and its HTTP transport is
   a Reactor servlet. Hand-rolling on Ring stays viable (tikka's adapter was about 200 lines).
2. **HTTP.** Ring 1.15.6 on Jetty 12 plus reitit 0.11.0 (now stable) is mainstream. Ring has no SSE helper; Pedestal
   0.8.2 (now stable) and the Datastar SDK do.
3. **Persistence.** next.jdbc 1.4.72 plus sqlite-jdbc 3.53.4.0 with FTS5 and recursive CTEs, verified locally; HoneySQL
   2.8.85; ragtime or migratus. Datalevin 1.2.7 has CAS, full-text search and Jepsen tests but is churning and stores
   LMDB binary, which conflicts with the inherited "readable without cloisters" decision.
4. **Web UI.** hiccup 2 with htmx 4 or Datastar 1 needs no front-end toolchain and keeps markdown parsing on the
   daemon alone. The SPA path is shadow-cljs 3.5.7 with re-frame on Reagent 2 (React 19, mainstream), or UIx or
   Replicant. Electric is still alpha with a commercial licence.
5. **CLI.** babashka v1.13.227 measured at 12.6 ms bare and 14.6 ms for an HTTP GET plus JSON parse; it bundles an HTTP
   client, cheshire, babashka.cli and nextjournal.markdown. `bbin` installs into `~/.local/bin`. GraalVM native-image
   is the heavier alternative.
6. **Markdown.** nextjournal/markdown 0.7.228 is the only parser giving one data-shaped tree on the JVM, in the browser
   and in babashka; code spans and blocks are distinct nodes, raw HTML is not rendered, but `javascript:` links pass
   through, so a safe renderer needs its own URL allow-list. flexmark is stalled; markdown-clj has no tree.
7. **Text diff.** java-diff-utils 4.18 on the JVM; editscript 0.8.0 is the maintained option on all three runtimes;
   jsdiff 9 in JS.
8. **Shared code.** `.cljc` works on the JVM, ClojureScript and babashka. malli 0.20.3 loads in babashka and emits JSON
   Schema, so one schema can feed reitit, MCP tool schemas and the CLI (regex patterns need stringifying). spec has no
   JSON Schema output.
