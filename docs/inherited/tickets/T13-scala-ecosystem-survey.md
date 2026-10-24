---
id: T13
title: Survey the Scala ecosystem for tikka's four surfaces
type: research
status: closed
assignee: saqib
blocked-by: []
---

## Question

What does the Scala 3 ecosystem offer today for each of tikka's surfaces, given the
Typelevel stack and Laminar are fixed? Concrete facts — versions, release dates, commit
activity, Scala 3 support — not hedged prose.

1. **MCP servers in Scala or on the JVM.** Highest value. Is there any Scala-native MCP
   library? If the path is interop with the official Java SDK, that SDK is Reactor /
   Reactive-Streams based — how cleanly does it bridge into cats-effect via
   `fs2-reactive-streams`, and at what cost? Which protocol revision does each support?
   Can an MCP endpoint mount inside an existing http4s server?
2. **Typelevel HTTP.** http4s health and Scala 3 support. For tapir: can endpoint
   definitions be shared between the JVM server and a Scala.js client to generate a
   typed client for Laminar? That code-sharing question matters disproportionately here.
3. **Embedded persistence, Typelevel-compatible.** Must run in-process with zero
   external services, survive restarts, express recursive queries (hierarchy walks,
   transitive closure over blocking edges), support an atomic compare-and-set, and
   append to the event log in the *same* transaction. doobie + SQLite, magnum, Quill.
   Verify whether skunk is Postgres-wire-only and therefore disqualified.
4. **Laminar and Scala.js.** Versions and activity. Realistic build toolchain (sbt vs
   scala-cli vs mill, plus Vite). How cross-compilation of shared domain types works in
   practice. Which JSON libraries cross-compile cleanly, and their code-size cost on JS.
5. **CLI startup**, targeting sub-100ms. Does cats-effect work on Scala Native? Scala
   Native's maturity and Scala 3 support. GraalVM native-image with cats-effect and any
   reflection pain. Measured figures where available.
6. **Cross-cutting.** Can HTTP API, web UI serving, and MCP all mount in one http4s
   server in one daemon? How does MCP stdio coexist with a long-running HTTP daemon —
   thin stdio proxy, or clients connecting to a local HTTP endpoint directly?

Landscape only; the decisions it feeds are separate tickets.

## Resolution

Surveyed 2026-09-15, inline. Versions come from Maven Central metadata and activity from
the GitHub API on that date; the startup figures are the cats-effect project's own
published measurements. Landscape only: the decisions it feeds are still open.

**Scala baseline.** Scala **3.9.0** (2026-09-03) is the new LTS; the previous LTS line is
3.3.8. Libraries built on 3.3 can be consumed from 3.9, but not the reverse (fast-mcp-scala
1.0.0 already needs 3.9). Build tools: sbt 2.0.9, Mill 1.1.9, scala-cli 1.17.0, all
released within the last fortnight.

### 1. MCP

The spec is at **2026-07-28** (stable, stateless core). The only JVM library that targets it
is fast-mcp-scala, and that library is ZIO-based. Everything Typelevel-native sits at
2025-11-25 or earlier, so tikka should expect to speak 2025-11-25 first.

| Library | Effect / HTTP | Protocol | Latest | Activity |
|---|---|---|---|---|
| `modelcontextprotocol/java-sdk` | Reactor, Jackson; servlet transports | 2025-11-25, conformance-tested | 2.0.1, 2026-08-19 | 3.7k★, 40 commits since mid-June |
| `andimiller/scala-mcp` | cats-effect 3.7, circe; `mcp-http4s` (Ember) | not stated | 0.13.0, 2026-06-12 | 1★, 2 commits since mid-June; JVM/JS/Native core |
| `ingarabr/scala-mcp-sdk` | cats-effect, fs2, circe; `server-http4s` gives `HttpRoutes[F]` | 2025-11-25 | 0.3.0, 2026-05-01 | 6★, **no commits since May 1** |
| `linkyard/scala-effect-mcp` | cats-effect, fs2; `mcp-server-http4s` | 2025-06-18 (README) | 0.3.5, 2026-04-16 | 13★, 1 commit since mid-June |
| `softwaremill/chimp` | tapir/sttp; streaming via Ox, ZIO or Pekko only | 2025-03-26 (HTTP transport) | 0.5.2, 2026-08-19 | 102★, 61 commits since mid-June |
| `TJC-LP/fast-mcp-scala` | **ZIO 2**, zio-json | **2026-07-28** | 1.0.0, 2026-09-09 | 24★, 100+ commits since mid-June |
| `windymelt/mcp-scala` | — | — | v0.1.1, 2025-05 | effectively dormant |

- **http4s mounting.** Three cats-effect libraries (ingarabr, linkyard, andimiller) expose MCP as
  http4s routes, so MCP mounts inside tikka's own server with no bridge. All three are
  small projects maintained by one person.
- **Java SDK bridge.** The Java SDK's public API is Reactive Streams, with Reactor
  inside. `fs2-reactive-streams` lives in the fs2 repository (3.14.0) and converts
  `Publisher` to and from `Stream`. The bigger cost is the transport: the SDK's server
  transports are servlet-based, so mounting it in http4s means writing an http4s
  transport provider yourself. The alternative is running a second HTTP server just for MCP.
- **chimp** is the best-maintained Scala option, but its bidirectional/streaming modules
  cover Ox, ZIO and Pekko, not cats-effect. Its basic transport runs on any tapir backend,
  http4s included.
- **Hand-rolled** JSON-RPC over circe is still viable for tikka's narrow need (about 8 tools, no
  sampling or elicitation). The 2026-07-28 stateless core makes it simpler, not harder.

### 2. Typelevel HTTP

- **http4s** 0.23.37 (2026-09-08) is the stable line and is actively released. 1.0 is still
  milestones (1.0.0-M48). Ember is the server, and it builds for JVM, Scala.js and **Scala
  Native** (0.23.x Native artifacts exist).
- **tapir** 1.13.31 (2026-08-07). `tapir-http4s-server` interprets endpoints on the
  daemon. `tapir-sttp-client4` publishes for **Scala.js**, so one endpoint definition in a
  cross-compiled module gives the server and a typed Laminar client, and `tapir-core`
  also publishes for Scala Native. Drift between server and client becomes a compile
  error. Cost: tapir becomes a second vocabulary over http4s routes.
- sttp client 4.0.26 publishes for Scala Native.

### 3. Embedded persistence

- **SQLite via `xerial/sqlite-jdbc`** 3.53.4.0 (2026-08-26). A single jar with native libs
  for macOS, Linux and Windows. The bundled build **compiles in FTS5, R*Tree and JSON**, so
  full-text search needs no extension loading. Recursive CTEs (needed for `under:` and
  blocker transitive closure) and `UPDATE … RETURNING` are core SQLite.
- **doobie** 1.0.0-RC13 (2026-06-12; *corrected by the walking skeleton: RC13 is a GitHub release only; Maven Central's latest is 1.0.0-RC12*). Still RC after years, but it is the de facto Typelevel
  JDBC layer and actively maintained. It has no SQLite-specific module; it goes through generic JDBC.
  `Update0.run` returns the affected-row count, which is the compare-and-set check for claims. Composing
  the mutation and the event insert in one `ConnectionIO` makes them a single transaction.
  SQLite allows one writer at a time, so the transactor needs WAL mode, a busy timeout, and in practice
  one write connection. JDBC calls run on the cats-effect blocking pool.
- **skunk** 2.0.0-RC3: "a data access library for Scala + Postgres", speaking the
  Postgres wire protocol only. **Disqualified.**
- **magnum** 2.0.0-M3 (2026-04-02), direct-style, 284★. It fits tikka's stack poorly (see
  the CLAUDE.md override). **Quill** (`zio-protoquill`) has had no release since 4.8.6 (2024-10-30).

### 4. Laminar and Scala.js

- **Laminar**: stable **17.2.1** (2025-03-26). **18.0.0-M5** (2026-02-27) is the
  current milestone, and the repo was pushed as recently as 2026-09-15. **Waypoint** (routing): 10.0.0-M7. Both have a
  single maintainer. Choose deliberately between the 17 line and the 18 milestones.
- **Scala.js** 1.22.0 (2026-06-20), active. Scala 3.9 output needs linker 1.22 or newer.
- **Vite**: `vite-plugin-scalajs` 1.1.0, last release **2025-08-04** and no pushes since.
  It works, but it is a stalled link in the toolchain.
- **Cross-compilation** is routine: sbt `crossProject(JVMPlatform, JSPlatform[, NativePlatform])`
  or Mill's equivalent, with `%%%` dependencies. The Typelevel core (cats-effect 3.7.1, fs2
  3.14.0, circe 0.14.16, http4s 0.23.37) publishes for all three platforms.
- **JSON on JS**: circe (0.14.16; 0.15.0-M1 exists) and jsoniter-scala (2.40.1)
  both cross-compile. circe is the heavier of the two on Scala.js and jsoniter-scala the
  smaller, but **bundle sizes were not measured here**. Measure in the skeleton before
  deciding on size grounds.
- `http4s-dom` has had no release since 2025-04-30. `tapir-sttp-client4` or plain fetch are the live
  client options.

### 5. CLI startup (sub-100ms target)

- **Scala Native** 0.5.12 (2026-05-22). cats-effect has supported Native since 3.3.14. The
  project's published hello world starts in **7.5 ms ± 1.2 ms** (hyperfine).
  *Corrected 2026-09-15 by [Choose the CLI approach](T06-cli-approach.md):* the survey originally
  repeated the docs page's claim that the Native runtime is single-threaded with no blocking pool.
  That page is stale. **cats-effect 3.7.0 brought full multithreading, `epoll`/`kqueue` polling and
  `blocking` to Scala Native 0.5.** The 7.5 ms figure predates the multithreaded runtime, so its
  startup is unmeasured.
  On Native: fs2-io 3.14.0, http4s Ember **client** 0.23.37, sttp client 4.0.26,
  decline 2.6.2 (CLI arg parsing), circe and jsoniter-scala. A cats-effect, Ember, circe
  and decline CLI builds entirely from published Native artifacts.
- **GraalVM native-image** with cats-effect: the project's published hello world takes
  **11.2 ms** mean. It requires `--no-fallback`, which is only safe without `Enumeration`s.
  cats-effect ships its own reachability metadata. fast-mcp-scala reports about 35 MB stdio
  binaries with zero hand-written metadata, which is useful precedent. Build times are
  long.
- Both clear 100 ms by an order of magnitude, so **startup no longer separates the two
  options**. What separates them is build time, binary size, and whether the whole
  dependency graph publishes for Native (Native) or tolerates closed-world reflection
  analysis (GraalVM).

### 6. Cross-cutting

- **One daemon, one server:** yes. The API routes, static Laminar assets
  (http4s `fileService`/`resourceServiceBuilder`) and an MCP route (from any of the three
  http4s-native MCP libraries, or hand-rolled) all compose as `HttpRoutes[IO]` combined
  with `<+>` on one Ember server. A push channel for live UI updates is an fs2 `Stream`
  served as SSE from the same server.
- **stdio vs daemon:** Streamable HTTP lets MCP clients that speak it connect straight to
  the daemon's localhost endpoint (Claude Code does, via `--transport http`). Clients
  that only launch stdio servers need a thin stdio-to-HTTP proxy. The native CLI binary is
  the natural place for it (`tikka mcp`), so no separate JVM process is needed.

**Consequences for open tickets.** Persistence: SQLite through doobie meets every stated requirement,
so what's left there is tuning, not choosing an engine. MCP: the choice is a small
http4s-native library versus hand-rolled circe versus the Java SDK with a custom transport.
Every Typelevel-compatible option is behind 2026-07-28. Frontend: the tapir shared-endpoint client is real on Scala.js,
Laminar 17 vs 18-milestone needs a call, and the Vite plugin has stalled. CLI: startup is a
non-issue on both native paths, and the whole Typelevel client stack publishes for Scala Native.
