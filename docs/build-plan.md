# Build plan

The planning map ([.wayfinder/MAP.md](../.wayfinder/MAP.md)) is complete: every decision lives in exactly one
resolved ticket, either a cloisters ticket or an inherited tikka ticket that carries over. This plan only **orders**
the build. It restates no decision: each milestone names the tickets it implements, and those tickets are the spec.
The ordering itself was decided in [Write the build plan](../.wayfinder/tickets/T15-build-plan.md).

**Read tickets to the end.** Several carry *Amended by* notes below their resolution, and those notes override the
text above them. The walking skeleton's amendments matter most. Read inherited tickets with "tikka" as "cloisters"
and `TIK` as `CLO`; where a cloisters ticket re-decides something, the cloisters ticket wins.

Every milestone covers **issues and pages together**. The shared id space, cross-kind mentions and one `search` make
a later "pages milestone" a second pass through every layer.

## Working rules

- **Start clean.** The [walking skeleton branch](https://github.com/saqib-trywe/cloisters/tree/prototype/walking-skeleton)
  is reference. Write new code, except for files you can copy verbatim after review: the vendored `htmx.min.js`
  (4.0.0), the jsdom shims in `scripts/ui-test.js`, `paths.clj`, and the lock and migration runner.
- **Branches.** One branch per milestone (`build/m0-scaffold`, `build/m1-store`, …), always branched from `main`,
  never stacked on the previous milestone. Each merges to `main` by squash PR once CI is green. `main` only ever
  holds working milestones. The repository has no ruleset, so PRs merge without `--admin`.
- **Sessions.** Start each milestone in a fresh session.
- **Done** means the milestone's checks pass, not that its code is written.
- **Staging.** Stage explicit paths only; never commit build artifacts (`target/`, `.cpcache/`, `node_modules/`,
  `.cloisters-dev/`).
- **Conventions** live in a root `CLAUDE.md`, written in M0. Vocabulary lives in [CONTEXT.md](../CONTEXT.md).
- **Research inline.** Verify library facts from Maven Central or Clojars metadata and release notes, not docs pages.

## CI

GitHub Actions runs on `ubuntu-latest` and `macos-latest`. A gate switches on in the milestone that creates the
thing it tests, and stays on after that:

| Gate | From |
|---|---|
| clj-kondo, cljfmt check, test-runner | M0 |
| Tests against a real temporary SQLite file, including migrating a non-empty store | M1 |
| MCP conformance `0.2.0-alpha.12` with `--requirements` at both revisions, against the `--expected-failures` baseline (installed in M0) | M4 |
| CLI startup gate: `cloisters search` under 100 ms against a live daemon | M5 |
| jsdom UI check against a live daemon, with the htmx 4 shims | M6 |

## Milestones

### M0: Scaffold and CI

- Root `deps.edn` and a tools.build uberjar for the daemon.
- The babashka CLI at `cli/cloisters.clj`.
- cognitect-labs test-runner, clj-kondo and cljfmt.
- `mise.toml` pins JDK 27, the Clojure CLI, babashka and node.
- `CLAUDE.md` carries the Clojure conventions, the skeleton's build conventions and the shell quirks (see below).
- CI installs the pinned conformance suite; its scenarios first run in M4.

Implements: [Survey the Clojure ecosystem for cloisters](../.wayfinder/tickets/T01-clojure-ecosystem-survey.md),
[Choose the CLI approach](../.wayfinder/tickets/T09-cli-approach.md) (tooling only),
[Walking skeleton](../.wayfinder/tickets/T13-walking-skeleton.md) (its findings table).

**Done when:** CI runs lint, format and an empty test suite green on both platforms.

### M1: Store and lifecycle

- Paths: XDG locations, plus the `CLOISTERS_HOME` override.
- EDN config and the daemon lock.
- The hand-rolled `user_version` migration runner, with `VACUUM INTO` snapshots and `quick_check`.
- Schema v1:
  - the `item` table enforcing the shared id space
  - projects, issues, and pages with parent, sibling rank and archive
  - stored revisions, labels, parent and blocking edges, mentions carrying kind, events with changes
  - one FTS5 table over both kinds
- HikariCP readers plus one locked writer.

Implements: [Choose the persistence stack](../.wayfinder/tickets/T06-persistence-stack.md),
[Place cloisters' files in the XDG base directories](../.wayfinder/tickets/T03-xdg-file-locations.md); inherited
[Choose the persistence engine](inherited/tickets/T03-persistence-engine.md),
[Daemon lifecycle and repo-to-project binding](inherited/tickets/T10-daemon-lifecycle.md) (lock, migrations),
[Decide export and durability](inherited/tickets/T17-export-and-durability.md) (snapshots, integrity),
[Define the event log schema](inherited/tickets/T14-event-log-schema.md) (tables).

**Done when:** real-SQLite tests show a second daemon refused by the lock, a non-empty store migrated with its
snapshot reopening at the old version, and one id sequence shared by issues and pages.

### M2: Core

- Every invariant, with rejections that name the conflict.
- Compare-and-set claims, decimal ranks, versions, and one event per write (none when nothing changes).
- Page revisions and the `stale_version` guard; archive, unarchive and restore, with `archived_parent` and
  `live_children`; diff.
- Mention detection: code never counts, forward references, project rescans, backlinks in both directions.
- The export function, which writes `page` records and page events carrying their revision.

Implements: [Amend scope and shape for the wiki](../.wayfinder/tickets/T02-amend-scope-for-the-wiki.md),
[Define page identity and linking](../.wayfinder/tickets/T04-page-identity-and-linking.md),
[Define page revisions and edit conflicts](../.wayfinder/tickets/T05-page-revisions-and-conflicts.md),
[Extend export and durability to pages](../.wayfinder/tickets/T14-export-and-durability-for-pages.md); inherited
[Define tikka's core invariants](inherited/tickets/T02-core-invariants.md),
[Define the event log schema](inherited/tickets/T14-event-log-schema.md),
[Settle body and mention conventions](inherited/tickets/T15-body-and-mention-conventions.md),
[Decide export and durability](inherited/tickets/T17-export-and-durability.md) (export).

**Done when:** real-store tests pass for:

- every invariant
- the concurrent claim race
- page moves, archiving and restoring
- stale page edits
- forward mentions and project rescans
- a deterministic export

### M3: Query grammar and search

- The parser with suggestions, compiled through HoneySQL.
- `kind:` (implied by kind-only filters), with `archived:no` as the default.
- Full-text search over both kinds.
- Keyset cursors and sorts.
- `assignee:` prefix matching.
- Recent changes and page trees, expressed as queries.

Implements: [Extend the query grammar and search to pages](../.wayfinder/tickets/T12-query-grammar-for-pages.md);
inherited [Define the shared query and filter grammar](inherited/tickets/T08-query-grammar.md),
[Confirm wayfinder fits tikka's contract](inherited/tickets/T16-wayfinder-fits-tikka.md).

**Done when:** tests cover every row of the grammar's filter table, including the page filters.

### M4: HTTP and MCP

- HTTP adapter:
  - Ring on Jetty, reitit routing, one jsonista mapper for both JSON edges (nulls dropped), and the malli contract
    validated by the core
  - adapter catch-alls and localhost hardening
  - REST plus intent actions, including `/api/pages`; `Accept: text/plain`; `/api/meta`
  - the event feed and resumable SSE stream, which opens with a comment
- MCP adapter:
  - the hand-rolled JSON-RPC Ring handler at `/mcp`, at 2025-11-25 and 2026-07-28
  - all sixteen tools, rendered by the shared text renderer

Implements: [Define the shared contract and HTTP adapter](../.wayfinder/tickets/T10-shared-contract-and-http.md),
[Choose the MCP implementation](../.wayfinder/tickets/T07-mcp-implementation.md) and
[ADR 0001](adr/0001-hand-rolled-mcp.md),
[Extend the MCP tool contract for pages](../.wayfinder/tickets/T11-mcp-tools-for-pages.md); inherited
[Design the HTTP API shape](inherited/tickets/T09-http-api-shape.md),
[Design the MCP tool contract](inherited/tickets/T07-mcp-tool-contract.md),
[Choose the MCP server implementation and transport](inherited/tickets/T04-mcp-implementation.md).

**Done when:**

- conformance at both revisions matches the baseline in CI
- a test validates every tool result against its own declared schema
- a real Claude Code session completes claim → edit → close on an issue, and create → update → archive on a page

### Dogfood point

Move the rest of the build, and the planning documents, into cloisters itself. From here, work goes through
cloisters' own MCP surface.

- **Daemon.**
  - `CLOISTERS_HOME=.cloisters-dev/` inside the repo (gitignored), on port **7228**, so 7227 and the XDG locations
    stay free for a real install, and tikka keeps 7117.
  - It runs from a jar copied into `.cloisters-dev/`; after each milestone merge, copy the new jar and restart.
  - Kill scratch daemons by the pid in their lock, never by pattern.
- **Binding.** Create project `CLO`. Bind the repository through `.cloisters` and a project-scoped `.mcp.json`
  entry. Put the daemon's start command in `CLAUDE.md`.
- **Issues.** A map issue, *Build cloisters' first release*, with M5 and M6 as its children, worked wayfinder-style
  per [Confirm wayfinder fits tikka's contract](inherited/tickets/T16-wayfinder-fits-tikka.md).
- **Pages.**
  - a *Context* page, from `CONTEXT.md`
  - a *Decisions* page with one child page per ADR
  - a *Build plan* page, from this file
  - The build plan page mentions the milestone issues, and each milestone issue mentions the pages it implements, so
    mentions work in both directions.
- **Repo copies.**
  - `CONTEXT.md`, `docs/adr/` and this file are frozen, each with a header naming its page: "Superseded at the
    dogfood point by CLO-n; edit the page."
  - `CLAUDE.md` tells sessions to read vocabulary through `get_page`, and new ADRs are written as pages.
  - `.wayfinder/` and `docs/inherited/` stay untouched as history.
- **Durability.** Commit the export's output as `docs/dogfood-export.jsonl` with each milestone merge. It is a
  readable record; it cannot restore anything, because cloisters has no import.

**Reached when:** a fresh Claude Code session in this repository finds the map issue's frontier, reads the *Context*
page, and sees backlinks in both directions.

### M5: CLI

- Every command, plus the `page` verbs, shared `show`/`edit`, and the `$EDITOR` flow, where a refused edit keeps the
  file.
- Bulk close, `project new` and `init`.
- The `daemon` subcommands: install, run, start, stop, status, logs, export, restore.
- The `cloisters mcp` stdio proxy.
- Installed by `bbin`, which copies the script: reinstall after every change. babashka must be installed user-wide,
  not only through the repo's `mise.toml`.

Implements: [Choose the CLI approach](../.wayfinder/tickets/T09-cli-approach.md); inherited
[Daemon lifecycle and repo-to-project binding](inherited/tickets/T10-daemon-lifecycle.md),
[Decide export and durability](inherited/tickets/T17-export-and-durability.md).

**Done when:** the 100 ms gate is green in CI, and `cloisters init` plus Claude Code works end to end in a fresh
repository.

### M6: Web UI

- Server-rendered hiccup with htmx 4.0.0 (vendored), as a thin adapter over the core.
- Issue views: ready, tree and detail.
- Page views: tree, page view, the plain markdown editor with preview, the always-on guard and hand merge, diff, and
  recent changes.
- Live updates over the one SSE stream, and inline edits.
- The safe nextjournal/markdown renderer, with mentions linked.

Implements: [Choose the web UI approach](../.wayfinder/tickets/T08-web-ui-approach.md),
[Define page revisions and edit conflicts](../.wayfinder/tickets/T05-page-revisions-and-conflicts.md) (the editor);
inherited [Settle body and mention conventions](inherited/tickets/T15-body-and-mention-conventions.md).

**Done when:** the jsdom check is green in CI, and Saqib confirms a browser walkthrough of every view and edit.

## Build conventions from the skeleton

These go into `CLAUDE.md` in M0. The detail is in
[Walking skeleton](../.wayfinder/tickets/T13-walking-skeleton.md).

- Write malli schemas with `mu/merge`, not `:merge`. Write titles as a single `:re`, not
  `[:and :string [:re …]]`.
- In HoneySQL raw fragments, bind parameters with `[:lift x]`; `RETURNING` allocates ids.
- The CLI sends `Host: 127.0.0.1:<port>`; the hardening allows both `localhost` and `127.0.0.1`.
- Write `java.lang.ProcessHandle` and other newer `java.lang` classes fully qualified.
- The service unit keeps `--enable-native-access=ALL-UNNAMED`.
- Shell quirks: in Saqib's zsh, `bb` is an alias for `brew bundle` (call babashka by path or through `mise exec`);
  `noclobber` is on (use `>|`); `$VAR` does not word-split.
