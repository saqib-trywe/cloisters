---
id: T09
title: Choose the CLI approach
type: grilling
status: closed
assignee: saqib
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

## Resolution

Grilled with saqib, 2026-10-25, in one round; every recommendation accepted. Tikka's
[Choose the CLI approach](../../docs/inherited/tickets/T06-cli-approach.md) carries except for its Scala Native target
and runtime: human-readable output by default with `--json` for `structuredContent`, never switching when piped; exit
codes `0` success, `1` domain rejection (error code first on stderr), `2` usage, `3` daemon unreachable; queries sent
raw; the command table read as `cloisters`; `--as` defaulting to `$CLOISTERS_ASSIGNEE`, then the OS username; bulk
close leaves-first and not atomic; `project new` and `init` human-only; the `daemon` subcommands, with `export` and
`restore` run as daemon-jar operations on the JDK recorded at install; macOS and Linux only.

### Target and dependencies

- **Babashka**, measured by the survey at 14.6 ms for an HTTP GET plus JSON parse. Rejected GraalVM native-image
  (reflection config, minute-long builds, a per-platform CI matrix) for a single-user tool where `bb` being installed
  is fine.
- **Only what babashka bundles**: `babashka.http-client`, cheshire, `babashka.cli`, `babashka.fs`,
  `babashka.process`. No shared `.cljc` and no libraries from source, which SCI would interpret on every start (malli
  alone would wreck startup). The daemon validates everything; the CLI only sends.
- **The daemon renders the compact text** the CLI prints, the same function MCP results use, so terminal and agent
  output come from one place. Tikka shared the renderer as code; cloisters shares it by calling it.

### Install and gate

- **`bbin install` from the repo checkout**, giving `~/.local/bin/cloisters`. No packaging.
- **CI gate: `cloisters search` against a live daemon under 100 ms at the median** by `hyperfine`. Mostly a tripwire
  against a source dependency creeping in.

### Commands for pages

Shared ids mean shared verbs where the meaning is the same:

- `cloisters show CLO-57 [--revision 3] [--events]` shows an issue or a page.
- `cloisters edit CLO-57` with no flags opens `$EDITOR` on either's body, always passing the version it read; for a
  page it takes `--title` and `-m` (edit summary).

Page-only verbs, mirroring the web UI's page actions:

| Command | Does |
|---|---|
| `cloisters page new "<title>" [--parent id] [--body-file f\|-] [-m summary]` | create |
| `cloisters page tree [CLO\|CLO-57]` | tree or subtree |
| `cloisters page history CLO-57` | revisions with summaries |
| `cloisters page diff CLO-57 [FROM TO]` | defaults to the previous revision |
| `cloisters page move CLO-57 --parent id\|none [--rank n\|--before id\|--after id]` | move or reorder |
| `cloisters page archive\|unarchive CLO-57 -m "…"` | summary required |
| `cloisters page restore CLO-57 3 [-m …]` | new revision from r3 |

`cloisters new` stays issue creation.

- **A refused `$EDITOR` save keeps the edited file** and prints its path with the error, so twenty minutes of edits
  survive a `stale_version` and can be merged by hand, as the UI editor keeps the human's text.

### `cloisters mcp`

The inherited framing-only stdio proxy, in babashka from bundled pieces: newline-delimited JSON-RPC from `*in*`,
POSTed to `/mcp?project=<binding from .cloisters>`, protocol headers passed through, each response written back as a
line, a 202 writing nothing. Its daemon-down behaviour is inherited from
[Daemon lifecycle and repo-to-project binding](../../docs/inherited/tickets/T10-daemon-lifecycle.md).

> **Amended by [Walking skeleton: surfaces over one core, issues and pages](T13-walking-skeleton.md):** `cloisters
> search` ran in 20.8 ms with babashka on `PATH` (32.9 ms through mise's shim), well inside the 100 ms gate.
> **Babashka must be installed user-wide** (`mise use -g babashka` or Homebrew), not only pinned in a repo's
> `mise.toml`: the installed script's `#!/usr/bin/env bb` finds no version outside that repo. **bbin copies a local
> script**, so a CLI change takes a reinstall. The JDK HTTP client refuses a `Host` header, so the CLI sends
> `Host: 127.0.0.1:<port>`, which the hardening accepts.
