---
id: T15
title: Write the build plan
type: grilling
status: open
assignee: null
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

The stack held with nothing re-decided; the primary source is the local `prototype/walking-skeleton` branch. Fold in
its build conventions: babashka installed user-wide, with a `mise.toml` pinning the repo's tools; bbin reinstall
after CLI changes; the SSE opening comment; adapter catch-alls; one jsonista mapper for both JSON edges; `mu/merge`
and single-`:re` schemas; `[:lift x]` in HoneySQL raw fragments; htmx `4.0.0` vendored deliberately; the jsdom shims
for htmx 4; kill scratch daemons by the pid in their lock, never by pattern.
