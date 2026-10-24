---
id: T01
title: Survey the Clojure ecosystem for tikka's four surfaces
type: research
status: closed
assignee: clj-survey-2
blocked-by: []
---

## Question

What does the Clojure ecosystem actually offer, today, for each of tikka's four
surfaces — and what is the maturity signal for each option?

Six areas, each needing concrete verifiable facts (names, versions, release dates,
commit activity, notable users) rather than hedged prose:

1. **MCP server implementations** in Clojure or on the JVM. Highest uncertainty on the
   map. Is there a maintained Clojure library? Is the realistic path interop with the
   official Java MCP SDK? Or hand-rolled JSON-RPC? How much of the protocol does each
   cover — tools, resources, prompts, transports?
2. **HTTP routing and server** for a small local API.
3. **Embedded persistence with real query capability.** Must run in-process with zero
   external services, express graph-ish queries (hierarchy, blocking closure), and
   survive restarts.
4. **Web UI approaches** for a local single-user app, weighing build-toolchain cost
   heavily.
5. **CLI**, specifically process startup time — a command-line client must feel instant.
6. **Cross-cutting**: can all four surfaces credibly run from one process or artifact,
   and how does an MCP stdio server coexist with a long-running HTTP server?

Landscape only. No product scope recommendations — those are decisions for the tickets
this one unblocks.

## Resolution

> **Superseded 2026-09-14.** Tikka moved from Clojure to Scala 3 before any product
> code existed — see [Adopt Scala with the Typelevel stack](T12-adopt-scala.md). The
> library findings below no longer apply. Two conclusions survive the language change
> and are still load-bearing: the official Java MCP SDK remains the conformance-tested
> JVM path (tikka is still on the JVM), and the measured JVM startup floor still rules
> the CLI out of the daemon process. Retained as the record of the route walked.

Surveyed 2026-09-14. Landscape below; the decisions it feeds remain open.

### 1. MCP on Clojure/JVM

Current MCP spec revision is **2026-07-28**; nothing in the Clojure or Java world
implements it yet — the field sits at **2025-11-25**. Spec-drift is therefore a live
concern, not a hypothetical.

- **`org.clojars.roklenarcic/mcp-server`** — the only credible pure-Clojure option.
  0.3.61, pushed 2026-09-04, 48 commits since June. Covers tools, prompts, resources
  and templates, completions, sampling, elicitation, progress, logging, pagination.
  Transports: stdio **and Streamable HTTP as a plain Ring handler**. Self-labelled
  alpha, one maintainer, 17 stars. Ships a babashka-compatible serde.
- **Official `modelcontextprotocol/java-sdk`** via interop — v2.0.1, 2026-08-19, 3.7k
  stars, validated against the MCP conformance suite at 2025-11-25. Reactor-based,
  Java 17+. Precedent: `bhauman/clojure-mcp` (775 stars) is built on it via interop.
- **Hand-rolled JSON-RPC** is genuinely viable for stdio — newline-delimited JSON-RPC
  2.0 over stdin/stdout. Datalevin ships exactly this as `dtlv mcp`.
- **Dead or stalled**: `theronic/modex` (last push 2025-04-05), `GaiwanTeam/mcp-sdk`
  (nothing since 2025-10-27), `unravel-team/mcp-clojure-sdk` (no Clojars release),
  `hugoduncan/mcp-clj` (still on protocol 2024-11-05, and a REPL tool not a library).

The live tradeoff: idiomatic-and-Ring-native but alpha and bus-factor-1, versus
conformance-tested and certain to track the spec but with Reactor interop in the call
path.

### 2. HTTP routing

reitit 0.11.0-rc1 (stable 0.10.1), active. Ring 1.15.5 + http-kit 2.8.1 — smallest
possible stack, hand-rolled dispatch. Pedestal 0.8.2-beta-11, no stable 0.8 yet;
interceptors buy async/SSE at the cost of ceremony. `party.donut/system` is lifecycle
DI, not a router — orthogonal, possibly useful separately.

### 3. Persistence

- **Datalevin 1.1.0** (2026-09-03, pushed 2026-09-14, 1.5k stars, used at Roam and
  Juji). LMDB, embedded, Datalog with **recursive rules explicitly benchmarked for
  transitive closure**, and — decisively — **`:db/cas` plus `with-transaction`**, so one
  atomic transaction can hold both the claim CAS and the event-log append. Meets every
  stated requirement directly.
- **SQLite via next.jdbc** (1.3.1118 / sqlite-jdbc 3.53.4.0). Recursive CTEs for
  hierarchy and closure; `UPDATE … WHERE assignee IS NULL` *is* the CAS; one
  transaction covers mutation plus log insert. Cheapest operationally, most familiar
  failure modes, inspectable by any tool.
- **XTDB 2** (v2.0.0 GA 2025-06-12, v2.2.0-rc1 2026-08-06) — bitemporal, SQL + XTQL,
  but **XTQL DML assertions and transaction functions were removed**; conditional
  writes are now SQL `ASSERT`. Design centre is object-store + log, not single-file
  local. **XTDB 1** is frozen at 1.24.5 (2025-03-25).
- **Datascript 1.8.1** — in-memory only, no durable transaction. Fails the brief.

### 4. Web UI

Hiccup + HTMX: zero JS build step, and read-heavy lists/filters/detail is its native
case; a graph view still needs a JS library via `<script>`. ClojureScript SPA (re-frame
1.4.7; UIx active; Helix last *tagged* release 2021) costs a watch process, node_modules
and a second compile target. **Electric Clojure is still v3-alpha, repo last pushed
2026-03-28, and carries a commercial licence with a non-commercial exception** — a
licensing constraint, not just a maturity one.

### 5. CLI startup

Measured: `java -version` 22 ms; `clojure -M -e '(println 1)'` **237 ms**. A JVM uberjar
will not reach sub-100 ms without AppCDS or CRaC. **babashka** (v1.13.222, releasing
multiple times daily) starts in ~10–15 ms and has an HTTP client and JSON built in — a
thin HTTP CLI fits cleanly. Its constraint: a fixed set of Java classes and **no runtime
Java class loading**; anything else must be a pod. GraalVM native-image is ~10–20 ms but
you own reflection config and a slow build.

### 6. Four surfaces, one artifact

**Yes, with one correction.** HTTP API, web UI and MCP-over-Streamable-HTTP can all
mount in **one Ring handler** in the daemon — `mcp-server`'s `ring-handler` is designed
for exactly this. But the CLI cannot live in that process: sub-100 ms rules out the JVM,
so it is necessarily a separate babashka binary hitting the HTTP API. "Four surfaces,
one process" was imprecise; it is three surfaces in one daemon plus a separate CLI
client.

Both MCP transport patterns are live, and **HTTP is now first-class**: Claude Code
supports `claude mcp add --transport http <name> <url>` against a local URL, and SSE is
deprecated in favour of Streamable HTTP. The stdio-proxy pattern persists for clients
that only launch local processes, and a babashka stdio shim forwarding JSON-RPC to the
daemon would be the same shape as the CLI client.

### Sources

[RokLenarcic/mcp-server](https://github.com/RokLenarcic/mcp-server) ·
[modelcontextprotocol/java-sdk](https://github.com/modelcontextprotocol/java-sdk) ·
[bhauman/clojure-mcp](https://github.com/bhauman/clojure-mcp) ·
[MCP versioning](https://modelcontextprotocol.io/specification/versioning) ·
[xtdb releases](https://github.com/xtdb/xtdb/releases) ·
[Datalevin](https://github.com/juji-io/datalevin) ·
[reitit](https://clojars.org/metosin/reitit) ·
[Electric](https://clojars.org/com.hyperfiddle/electric) ·
[babashka book](https://book.babashka.org/) ·
[Claude Code MCP docs](https://code.claude.com/docs/en/mcp) ·
[sparfenyuk/mcp-proxy](https://github.com/sparfenyuk/mcp-proxy)
