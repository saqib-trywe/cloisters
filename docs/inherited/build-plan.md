# Build plan

The planning map ([.wayfinder/MAP.md](../.wayfinder/MAP.md)) is complete: every decision lives in exactly one
resolved ticket. This plan only **orders** the build. It restates no decision: each milestone names the tickets it
implements, and those tickets are the spec.

**Read tickets to the end.** Several carry *Amended by* or *Refined by* notes below their resolution, and those notes
override the text above them. The walking skeleton's findings are the most important of these.

The [walking skeleton branch](https://github.com/saqib-trywe/tikka/tree/prototype/walking-skeleton) is reference only.
The build starts clean, reusing ideas and patterns rather than files.

## Working rules

- One branch per milestone (`build/m0-scaffold`, `build/m1-core`, …), merged to `main` by pull request once CI is
  green. `main` only ever holds working milestones.
- A milestone is done when its checks pass, not when its code is written.
- Conventions: [CLAUDE.md](../CLAUDE.md) (Typelevel stack, explicit return types, opaque types, error ADTs, no
  class-level `var`, braceless syntax). Vocabulary: [CONTEXT.md](../CONTEXT.md).
- Research inline; verify library facts from Maven Central metadata and release notes, not docs pages.

## Milestones

### M0: Scaffold and CI

sbt 2 cross-build: `shared` (JVM/JS/Native: the contract), `prose` (JVM/JS: Laika-based mention detection and rendering),
`core` and `daemon` (JVM), `ui` (JS), `cli` (JVM/Native). munit and scalafmt. GitHub Actions compiles and tests on all three
platforms, links the Native CLI and runs the startup gate, and installs the pinned MCP conformance suite (its scenarios run
from M3).

Implements: Shape the Laminar frontend; Choose the CLI approach; ADR 0002. Heed the skeleton's sbt 2 findings (`%%`, no
`%%%`; virtual classpaths; `;`-joined batch commands; pinned `/usr/bin/clang`).

**Done when:** CI is green for an empty build on every platform.

### M1: Domain, store and core

Opaque domain types and error ADTs; schema v1 (projects, issues, labels, parent and blocking edges, mentions, events with
changes and related issues, FTS5 trigram search); forward-only migrations with snapshots, `quick_check`, `daemon.lock`,
`TIKKA_HOME`, config. The core service: every invariant, claims, decimal ranks, versions, `body_edits`, events, and mention
detection with forward references and project rescans.

Implements: Define tikka's core invariants; Choose the persistence engine; Define the event log schema; Settle body and
mention conventions; Daemon lifecycle and repo-to-project binding; Decide export and durability (snapshots and integrity).

**Done when:** tests against a real temporary SQLite store pass for every invariant, the concurrent claim race, migrating
a non-empty store, forward mentions and project rescans.

### M2: Query grammar

Shared parser with suggestions, compiled to SQL; keyset cursors; sorts; full-text search; `assignee:` prefix matching.

Implements: Define the shared query and filter grammar; Confirm wayfinder fits tikka's contract.

**Done when:** the grammar's filter table is covered by tests, and the parser's tests pass on the JVM, JS and Native.

### M3: HTTP and MCP adapters

tapir endpoints (with the decode-failure handler), localhost hardening, event feed and SSE stream, `/api/meta`. The
hand-rolled dual-era MCP adapter with all nine tools, the shared text renderer, and a test validating every tool result
against its own declared schema.

Implements: Design the HTTP API shape; Design the MCP tool contract; Choose the MCP server implementation and transport;
Walking skeleton — surfaces over one core (its amendments).

**Done when:** the conformance suite passes at both revisions in CI, and a real Claude Code session completes claim → edit
→ close.

### Dogfood point

Move the remaining milestones into tikka as issues, as a wayfinder-style map per Confirm wayfinder fits tikka's
contract, and work them through tikka's own MCP surface from here on.

**Reached 2026-09-17.** The map is TIK-1, *Build tikka's first release*, with M4 and M5 as its children, in a dev
home at `.tikka-dev/` (gitignored) served on port 7117. The repository is bound to project `TIK` through `.tikka` and
the project-scoped MCP entry in `.mcp.json`. From here, a milestone's status lives in tikka, not in this file.

### M4: Native CLI

Every command, the `$EDITOR` flow, bulk close, `project new`, `init`, the `daemon` subcommands (install, run, start, stop,
status, logs, export, restore), and the `tikka mcp` stdio proxy.

Implements: Choose the CLI approach; Daemon lifecycle and repo-to-project binding; Decide export and durability.

**Done when:** the startup gate is green, and `tikka init` plus Claude Code works end to end in a fresh repository.

### M5: Web UI

Laminar 18 and Waypoint; ready, tree and detail views; live updates; inline edits; mention links rendered through `prose`.

Implements: Shape the Laminar frontend; Settle body and mention conventions.

**Done when:** a browser walkthrough of every view and inline edit is confirmed by the user.
