---
id: T15
title: Write the build plan
type: grilling
status: closed
assignee: saqib
blocked-by: []
---

## Question

Every decision ticket is closed. What ordered milestones take cloisters from an empty repository to the destination,
so that build sessions can execute without re-deciding anything? Tikka's
[build plan](../../docs/inherited/build-plan.md) precedent ran scaffold and CI, then domain, store and core, then the
query grammar, then HTTP and MCP, then a dogfood point, then CLI, then web UI. Decide:

- The milestones, their order and what each must prove before the next starts, including where pages enter (with
  issues from the start, or after them).
- The **dogfood point**: when cloisters holds its own remaining tickets and its `CONTEXT.md`, ADRs and build plan as
  pages (the destination's acceptance test), and how this map's open work moves in.
- What CI enforces from the first milestone: tests against a real SQLite file, the migration test on a non-empty
  store, conformance at both revisions against an expected-failures baseline, the 100 ms CLI gate, a jsdom UI check.
- Where the plan lives until the dogfood point, and the branch, PR and session conventions (tikka's carry unless
  re-decided).

## Inherited from [Walking skeleton: surfaces over one core, issues and pages](T13-walking-skeleton.md)

The stack held with nothing re-decided; the primary source is the [`prototype/walking-skeleton`](https://github.com/saqib-trywe/cloisters/tree/prototype/walking-skeleton) branch. Fold in
its build conventions: babashka installed user-wide, with a `mise.toml` pinning the repo's tools; bbin reinstall
after CLI changes; the SSE opening comment; adapter catch-alls; one jsonista mapper for both JSON edges; `mu/merge`
and single-`:re` schemas; `[:lift x]` in HoneySQL raw fragments; htmx `4.0.0` vendored deliberately; the jsdom shims
for htmx 4; kill scratch daemons by the pid in their lock, never by pattern.

## Resolution

Grilled with saqib on 2026-10-25. The plan is [docs/build-plan.md](../../docs/build-plan.md). It orders the build and
restates no decision.

- **Order.** M0 Scaffold and CI → M1 Store and lifecycle → M2 Core → M3 Query grammar and search → M4 HTTP and MCP →
  **dogfood point** → M5 CLI → M6 Web UI. Tikka's store-and-core milestone is split in two because pages double the
  core. Every milestone covers **issues and pages together**: the shared id space, cross-kind mentions and one
  `search` would make a later pages milestone a second pass through every layer, and the dogfood point needs the page
  tools.
- **Start clean.** The skeleton is reference. Copy files verbatim only where they hold up (vendored htmx 4.0.0, the
  jsdom shims, `paths.clj`, the lock and migration runner); everything else is new code, because rewriting the core is
  where the learning is.
- **Conventions.** Tikka's carry:
  - one `build/mN-…` branch per milestone, always from `main`, merged by squash PR once CI is green
  - a fresh session per milestone
  - done means the checks pass
  - stage explicit paths only

  The private repository on a free plan can't have rulesets, so there's no `--admin`. A root `CLAUDE.md`, written in
  M0, carries the Clojure conventions and the skeleton's build conventions.
- **Toolchain and CI.** deps.edn with tools.build, cognitect-labs test-runner, clj-kondo and cljfmt. `mise.toml` pins
  JDK 27, the Clojure CLI, babashka and node; babashka is also installed user-wide. Actions run on ubuntu and macOS.
  Each gate switches on in the milestone that creates what it tests: real SQLite and the migration test (M1),
  conformance against the baseline (M4, installed in M0), the 100 ms CLI gate (M5), jsdom (M6).
- **Dogfood point, after M4.**
  - The daemon runs from `CLOISTERS_HOME=.cloisters-dev/` in the repo (gitignored) on port 7228, keeping 7227 and the
    XDG locations free for a real install.
  - Project `CLO`, bound through `.cloisters` and `.mcp.json`.
  - A map issue, *Build cloisters' first release*, with M5 and M6 as children.
  - Pages *Context*, *Decisions* (one child page per ADR) and *Build plan*, with mentions both ways between the plan
    and the milestone issues.
  - `CONTEXT.md`, the ADRs and the plan file are frozen in git with a "superseded by CLO-n" header; the pages are the
    source of truth.
  - `.wayfinder/` and `docs/inherited/` stay as history.
  - `docs/dogfood-export.jsonl` is committed with each milestone merge: a readable record, since no import exists.
