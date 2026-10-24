---
id: T11
title: Walking skeleton — surfaces over one core
type: prototype
status: closed
assignee: saqib
blocked-by: [T03, T04, T05, T06, T14]
---

## Question

The one execution ticket on this map. Everything else produces decisions; this produces
throwaway code that proves the central technical assumption before the spec is handed
off: **the surfaces genuinely share one core.**

The target arrangement: **HTTP API, Laminar assets and MCP served by one http4s server in
a single daemon**, plus a **separate native CLI** against its HTTP API. The JVM startup
floor rules the CLI out of the daemon process, so this is not four surfaces in one
process — proving the arrangement above is still the point.

Build the thinnest end-to-end slice. One issue, one field beyond the id, no invariants,
no polish:

- The daemon starts on cats-effect and holds the chosen store.
- Create an issue via the **CLI**, and measure real startup time against the budget set
  in [Choose the CLI approach](T06-cli-approach.md).
- See it in the **Laminar UI**, using the shared cross-compiled domain type.
- Read it back over the **HTTP API**.
- Read it over **MCP** from a real MCP client, not a test harness — the leg most likely to
  break, and a mock would prove nothing.

What the prototype must answer:

- Does the transport and implementation from
  [Choose the MCP server implementation and transport](T04-mcp-implementation.md) survive
  contact with a real client — in particular, does any Reactor bridge behave under
  cats-effect cancellation?
- Is "one core, many adapters" real, or did something force duplication — across the
  JVM/Scala.js boundary, or across the JVM/native-CLI boundary?
- Did either revisit condition in [ADR 0001](../../docs/adr/0001-scala-with-typelevel-stack.md)
  trigger: a disproportionately painful MCP bridge, or a CLI that can only hit its startup
  budget by abandoning the shared core?
- Where did the friction actually turn up, versus where the map predicted?

Throwaway code. Evidence for the spec, not the first commit of tikka — resist growing it
into the product.

**Inherited from [Design the HTTP API shape](T09-http-api-shape.md):**
the skeleton proves one core under two adapters: tapir endpoints in a cross-compiled shared module,
interpreted to http4s on the daemon and to sttp clients on Scala.js and Scala Native, with tapir
`Schema` also feeding the hand-rolled MCP layer's tool schemas. If tapir's Native or JS client
does not build, that is the finding.

**Inherited from [Choose the CLI approach](T06-cli-approach.md):**
the skeleton links the CLI for Scala Native and measures `tikka search` against a running daemon
with `hyperfine`. The budget is a 100 ms median, and the multithreaded cats-effect runtime's startup is the
specific unknown. If it misses, try a single-threaded runtime configuration, then the sync curl
backend without cats-effect, then GraalVM, and record which one held.

**Inherited from [Daemon lifecycle and repo-to-project binding](T10-daemon-lifecycle.md):**
build migrations (`user_version`, snapshot before migrating) and the `daemon.lock` from the first
commit; retrofitting either onto a store that already holds real tickets is how data gets lost. Honour
`TIKKA_HOME` everywhere so the skeleton's daemon never touches a real store. Service installation
(`tikka daemon install`) can wait; `tikka daemon run` in the foreground is enough to prove the
surfaces.

**Inherited from [Shape the Laminar frontend](T05-web-ui-approach.md):**
sbt 2 with JVM/JS/Native cross-projects and no npm. The shared module holds only the contract (types,
tapir endpoints, circe codecs, query parser, text renderer, line diff). The daemon serves `fastLinkJS`
output from disk in dev mode and embeds `fullLinkJS` output in the jar. Laminar 18 and Waypoint 10 milestones.
Record the `fullLinkJS` bundle size.

**Scoped 2026-09-15:** mention detection is **out of the skeleton**. It proves four surfaces over
one core, which mentions don't test. The store keeps a place for mention edges, left empty.
Detection rules are [Settle body and mention conventions](T15-body-and-mention-conventions.md).

## Resolution

Built 2026-09-15 on branch
[`prototype/walking-skeleton`](https://github.com/saqib-trywe/tikka/tree/prototype/walking-skeleton)
(directory `skeleton/`, never merged). That branch is the primary source. Scope, as agreed before building: one
project (`SKL`), issues with a title only, the empty query only, no invariants, events, claims or mentions. The skeleton kept
`TIKKA_HOME`, the lock and migrations because they are part of the arrangement being proven. The ticket's Reactor-bridge
question was replaced, because ADR 0002 removed the bridge.

**Verdict: the architecture holds.** One core under two adapters, one shared contract on three platforms,
and both of ADR 0001's revisit conditions clear by a wide margin. What failed was a handful of specifics the map got wrong,
recorded below and amended into their tickets.

### The questions

- **Does the MCP implementation survive a real client?** Yes. Claude Code 2.1.270, running headless, negotiated **2026-07-28**
  against the hand-rolled `/mcp`, called `search_issues` and `get_issue`, and answered correctly. The daemon logged the actor as
  `mcp:claude-code (2026-07-28)`. The legacy path (initialize → session → tool call) works too. Every applicable
  conformance scenario passes at **both** revisions (2025-11-25: `server-initialize`, `ping`, `tools-list`,
  `dns-rebinding-protection`; 2026-07-28: `server-stateless`, `tools-list`, `dns-rebinding-protection`). The remaining
  failures are the suite's "not testable" checks that need its own fixture tools. The adapter is about 200 lines.
- **Is "one core, many adapters" real?** Yes. `Core` is one trait, and HTTP (tapir → http4s) and MCP both call it. The shared
  module (opaque ids, types, tapir endpoints, circe codecs, renderer) compiled **unchanged** on the JVM, Scala.js and Scala
  Native. The daemon, the Laminar UI (tapir client over fetch) and the Native CLI (tapir client over curl) all use it.
  tapir's `Schema` produced the MCP `inputSchema`/`outputSchema` directly, descriptions included. **Nothing was duplicated
  at either boundary.**
- **Did either ADR 0001 revisit condition trigger?** No. The MCP layer was the smallest adapter, not a painful bridge. The CLI
  keeps the whole shared core and cats-effect and runs **`tikka search` against a live daemon in 11.3 ms ± 0.3 ms** (release
  build, hyperfine, 200 runs; debug build 14.2 ms; `curl` itself 8.3 ms) against a 100 ms budget. None of the startup
  fallbacks were needed.
- **Where did friction actually turn up?** Not where the map predicted. Laminar 18 milestones on Scala 3.9, tapir on Native,
  Native link times (about 10 s) and the multithreaded runtime's startup were all non-events. The real friction:

| Finding | Consequence |
|---|---|
| **Ember on Native dynamically links `s2n-tls` and `libidn2` from Homebrew**, even for plain-HTTP localhost; it broke when `s2n` wasn't installed. sttp's curl backend links the OS `libcurl` plus `libidn2`; sttp's own CI installs `libidn2-dev` for every Native target. | The CLI uses **sttp's curl cats backend**, not Ember. The binary needs `libidn2` at runtime (Homebrew on macOS, a standard package on Linux). Amended in [Choose the CLI approach](T06-cli-approach.md). |
| **Claude Code validates `structuredContent` against `outputSchema`**; `"next_cursor": null` against an optional-string schema failed. The conformance suite didn't catch it. | Drop nulls from `structuredContent`, and test every tool result against its own declared schema. |
| **2026-07-28 details the MCP implementation ticket lacked:** `server/discover` is mandatory; `ping` and `initialize` are removed (a modern request for them must return 404/`-32601`); results carry `resultType` and `_meta` serverInfo; `tools/list` and `server/discover` **require `ttlMs` and `cacheScope`**; requests mirror `Mcp-Method`/`Mcp-Name` into headers (`-32020` on mismatch); a partial `_meta` is modern and malformed (`-32602`). | Amended in [Choose the MCP server implementation and transport](T04-mcp-implementation.md). |
| **The released conformance suite (npm 0.1.16) doesn't know 2026-07-28**; scenarios exist only on GitHub `main`. | CI runs the suite from GitHub until a release ships. |
| **tapir's default decode-failure response is plain text**, breaking the error contract for blank titles or bad ids. | A five-line custom handler returns `invalid_argument`. It must be installed from day one. Amended in [Design the HTTP API shape](T09-http-api-shape.md). |
| **An unclosed `PRAGMA user_version` result set made `VACUUM INTO` fail** ("SQL statements in progress"). | The migration runner needs a test that migrates a non-empty store. |
| **JDK 26 warns** about `sun.misc.Unsafe` in Scala 3.9's own `LazyVals`, and about sqlite-jdbc's native loading. | The service unit passes `--enable-native-access=ALL-UNNAMED`; track the Scala runtime warning. Amended in [Daemon lifecycle and repo-to-project binding](T10-daemon-lifecycle.md). |
| **sbt 2 specifics:** `%%%` is gone (`%%` picks the platform suffix); exported classpaths use virtual paths (so the daemon runs from an assembly jar, which needs a `module-info.class` merge rule); the background build server doesn't see the client's environment variables; batch-mode commands must be `;`-joined in one argument. | Build conventions for the real build. |
| **A `clang` installed by swiftly shadowed Apple's on `PATH`** and lacked SDK headers. | Pin `/usr/bin/clang` in `nativeConfig` (machine-specific, but cheap to guard). |
| **doobie is 1.0.0-RC12 on Maven Central**; the survey's RC13 exists only as a GitHub release. | Survey corrected. |
| **`fullLinkJS` bundle: 2.8 MB, 427 KB gzipped** (`fastLinkJS` 4.9 MB). | Recorded; irrelevant over localhost, as the frontend ticket expected. |

### What the store and API checks showed

A second daemon was refused, naming the lock holder. Migrations went v0→v1 with no snapshot, then v1→v2 with a `VACUUM INTO`
snapshot that reopens at v1 with its rows. A store at v9 was refused. `sqlite3` shows WAL, `user_version` and plain rows. The
API returned 200/404/400 with contract error bodies. The hardening gave 403 for a foreign `Origin` or a rebinding `Host`. The UI
was served embedded (byte-identical to `fullLinkJS`) and from disk in dev mode, with an SPA fallback for `/i/SKL-1`, and was
confirmed in a browser. An issue created by the Native CLI read back identically over HTTP, MCP and the CLI.
