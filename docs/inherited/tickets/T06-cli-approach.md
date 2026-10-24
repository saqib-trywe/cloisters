---
id: T06
title: Choose the CLI approach
type: grilling
status: closed
assignee: saqib
blocked-by: [T13]
---

## Question

How is the CLI built and distributed?

The CLI is a thin HTTP client to the daemon with full CRUD, so its logic is trivial. The
decision is **startup time**: a CLI that takes a second to list issues will not get used.
The JVM startup floor is disqualifying on its own, so the CLI cannot be a plain JVM
process — this much survived from the Clojure survey.

- What is the startup budget? Sub-100ms is the bar for a tool that feels instant.
- Which approach hits it: **Scala Native**, **GraalVM native-image**, or something else?
  Read [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md)
  for measured figures and for whether cats-effect and the chosen HTTP client actually
  work on each.
- **Does the CLI use the Typelevel stack at all?** Initialising a cats-effect runtime
  costs startup time, and a native target may constrain which libraries load. A CLI that
  makes one HTTP call and prints may be better served by a minimal client with no effect
  system — which is legitimate, but it means the CLI shares less of the core. Decide how
  much sharing is worth how many milliseconds.
- **Shared code.** Domain types and codecs cross-compiled to the native target, or a
  small duplicated client? Duplication is the honest answer if cross-building costs more
  than the code it saves.
- **Output shape.** Human-readable tables by default with a machine flag for scripting,
  or structured always?
- **Build cost.** Native builds are slow and have their own failure modes; what does that
  do to the edit-run loop while developing the CLI?

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
queries are a positional argument in the shared syntax. Decide whether the CLI parses
locally (the parser must then build for the native target) or sends the raw string and lets
the daemon reject it — the latter keeps the CLI thinner at the cost of a round trip for typos.

**Inherited from [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md):**
startup no longer separates the options. cats-effect's published hello world takes 7.5 ms on Scala
Native (0.5.12) and 11.2 ms on GraalVM native-image. On Native, the Ember client, fs2-io, sttp 4,
circe, jsoniter-scala, decline and tapir-core all publish, so a CLI on the Typelevel stack costs nothing
in startup. The deciding factors are build time, binary size, and whether the query parser
cross-builds to Native for local validation. The CLI is also the natural stdio-to-HTTP MCP proxy
for clients that only spawn stdio servers.

**Inherited from [Choose the MCP server implementation and transport](T04-mcp-implementation.md):**
the CLI carries `tikka mcp`, a stdio-to-HTTP proxy for stdio-only MCP clients: newline-delimited
JSON-RPC on stdin forwarded to the daemon's `/mcp` with the repo's binding applied. On Scala Native the
cats-effect runtime is documented as single-threaded, with no blocking pool, so reading stdin must not block the
only thread. Verify how fs2-io reads stdin on Native before committing to cats-effect here.

**Inherited from [Design the HTTP API shape](T09-http-api-shape.md):**
the CLI's HTTP client is the tapir-derived sttp client from the shared module, so the endpoint
definitions must build for Scala Native. It sends `Tikka-Project` from the repo binding, calls
`GET /api/meta` to warn on a daemon version mismatch, and switches on the error body's `error`
code (statuses are only 404/400/409).

## Resolution

Decided 2026-09-15.

**Correction to the inherited caveat above:** cats-effect 3.7.0 brought full multithreading,
`epoll`/`kqueue` polling and `blocking` to Scala Native 0.5, so the "single-threaded, no blocking
pool" docs page was stale. fs2-io's `stdin` on Native reads asynchronously through the runtime's
file-descriptor poller on macOS and Linux, so the stdio proxy blocks no thread. The survey ticket is
corrected.

### Target and budget

- **Scala Native**, with **GraalVM native-image as the named fallback** if the skeleton finds a Native
  break. Every dependency publishes for Native (cats-effect 3.7.1, fs2 3.14.0, circe, sttp 4.0.26, tapir's
  sttp4 client, decline 2.6.2). The shared module already cross-builds for Scala.js, so Native is one more
  platform in an existing matrix. Rejected GraalVM as the primary (minute-long builds, tens of MB, reflection
  analysis breaks on dynamic dependencies) and a JVM with class-data sharing (hundreds of ms).
- **Budget:** `tikka search` against a running daemon, from process start to last byte printed,
  **under 100 ms at the median** by `hyperfine`. The multithreaded runtime's startup is the unknown;
  the first remedy if it misses is a single-threaded runtime configuration for the CLI, before any
  change of target.

### Runtime

- **cats-effect `IOApp` with the tapir-derived sttp client over sttp's http4s (Ember) backend.** It is
  pure Scala, with no system libcurl. `tikka mcp` genuinely needs concurrency (stdin, HTTP and stdout as one
  stream), and the shared module is cats-effect-shaped. **Fallback if startup misses:** the synchronous
  curl backend without cats-effect for one-shot commands.
- **Queries are sent raw** and the daemon rejects them with suggestions. The CLI is versioned separately
  from the daemon, so a local parser could disagree with the daemon's; the UI can parse locally because it
  ships inside the daemon.

### Output and exit codes

- **Human-readable by default, using the same compact text renderer as MCP results**, so terminal and
  agent output never drift. `--json` prints the `structuredContent` JSON. Output never switches format when piped.
- **Exit codes:** `0` success; `1` domain rejection (the contract's error code first on stderr, e.g.
  `claim_conflict: …`; the error body with `--json`); `2` usage error; `3` daemon unreachable.

### Commands

| Command | Maps to |
|---|---|
| `tikka search '<query>'` (alias `ls`) | `search_issues` |
| `tikka show TIK-42 [--events]` | `get_issue` |
| `tikka new "<title>" [--body-file f\|-] [--label l]… [--parent id] [--blocked-by id]… [-m comment]` | `create_issue` |
| `tikka edit TIK-42 [--title …] [--label +a -b] [--parent id\|none] [--blocked-by +id -id] [--rank n\|--before id\|--after id] [-m comment]` | `update_issue` |
| `tikka claim\|release TIK-42 [--as name]` | `claim_issue` / `release_issue` |
| `tikka reassign TIK-42 --from x --to y` | `reassign_issue` |
| `tikka close TIK-42 done\|dropped -m "…"` | `close_issue` |
| `tikka reopen TIK-42 -m "…"` | `reopen_issue` |
| `tikka close --query '<q>' dropped -m "…"` | **bulk close**, CLI only |
| `tikka project new KEY "Name"` | **project creation**, human only, not on MCP |
| `tikka mcp` | stdio proxy |

- `--as` defaults to `$TIKKA_ASSIGNEE`, then the OS username.
- **Bulk close** closes matching issues leaves-first, one ordinary close each, **not atomic**. It stops at
  the first rejection, lists what it closed and exits `1`. One core call never mutates many issues.
- **`tikka edit TIK-42` with no flags** opens `$EDITOR` on the body and **always passes the version it
  read**, so a concurrent agent edit surfaces as `stale_version` instead of being overwritten. An unchanged
  file is a no-op.

### `tikka mcp`

**Framing only.** It reads newline-delimited JSON-RPC from stdin, POSTs each message to
`/mcp?project=<binding>`, passes through `MCP-Protocol-Version` and `Mcp-Session-Id`, and writes each response
body back as a line. A 202 response to a notification writes nothing. It never interprets the protocol, so revision
support lives entirely in the daemon and never requires rebuilding the proxy.

### Build and install

- The CLI module cross-builds for the JVM and Native. **Development runs on the JVM**; Native links only in CI,
  at install, and for the startup benchmark. **CI links in release mode and fails the build if the
  `hyperfine` check misses 100 ms.**
- **Install:** one build task links a release binary and copies it to `~/.local/bin/tikka`. No
  packaging.
- **macOS and Linux only.** fs2's async stdin on Native covers those two, and Windows is not wanted.
  A deliberate limit.

> **Amended by [Walking skeleton — surfaces over one core](T11-walking-skeleton.md):** the HTTP backend is
> **sttp's curl cats backend, not Ember**. Ember on Native dynamically links `s2n-tls` and `libidn2` from Homebrew even
> for plain-HTTP localhost, so the "pure Scala, no system library" rationale was wrong. The curl backend links the OS
> `libcurl` plus `libidn2`, which is a runtime dependency on macOS (Homebrew) and Linux. The whole shared core and
> cats-effect stay. Measured: `tikka search` against a live daemon runs in **11.3 ms** (release) and 14.2 ms (debug); the
> binary is 12 MB. The single-threaded-runtime and GraalVM fallbacks were not needed.

> **Refined by [Decide export and durability](T17-export-and-durability.md):** `tikka daemon export <file>` and
> `tikka daemon restore <snapshot>` are daemon-jar operations that the CLI invokes, not HTTP calls. Both must work when no
> daemon is running.

> **Refined by the build's scaffold milestone ([build plan](../../docs/build-plan.md), M0):** on the JVM the CLI uses
> sttp's `HttpClientCatsBackend`, one file per platform. A refused connection is sttp's `ConnectException` on the JVM, but on
> Native the curl backend raises a plain `RuntimeException` ("Command failed with status COULDNT_CONNECT"), so exit code `3`
> must recognise both.
