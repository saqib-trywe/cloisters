# 1. Scala with the Typelevel stack, overriding the house direct-style skill

Date: 2026-09-14

## Status

Accepted. Supersedes the implicit choice of Clojure made while charting.

## Context

Tikka was originally charted as a Clojure project, and an ecosystem survey had
already been completed on that basis. The language was changed to Scala 3 before any
product code existed, so the cost of the switch was limited to planning artifacts.

Scala presents a fork that Clojure did not. Two coherent stacks exist:

- **Typelevel** — cats-effect `IO`, http4s, fs2, circe. Monadic effects.
- **Direct-style** — Ox structured concurrency on virtual threads, Tapir's `.handle`
  family, Magnum, jsoniter. No effect wrapper.

This environment has the `direct-style-scala` skill installed and enabled as a plugin.
It auto-loads for any Scala task and is explicit: *"NEVER use `.serverLogic` — it
requires a monadic wrapper (`Future`, `IO`) and MUST NOT be used."* Choosing Typelevel
therefore means knowingly overriding a standing convention of this machine, not merely
picking a library set.

Several project-specific facts pull toward direct-style, and were weighed:

- MCP's stdio transport requires reading blocking `java.io` streams, which the skill
  documents as a shutdown-deadlock hazard with a dedicated chapter. Virtual threads
  handle blocking reads natively.
- The official Java MCP SDK is Reactor / Reactive-Streams based. Reaching cats-effect
  from it requires an `fs2-reactive-streams` bridge; Ox could simply block.
- The CLI targets sub-100ms startup, which likely means Scala Native or GraalVM
  native-image, where avoiding the cats-effect runtime is one less variable.

## Decision

Build tikka on **Scala 3 with the Typelevel stack** — cats-effect, http4s, fs2, circe —
with **Laminar** for the web UI.

The `direct-style-scala` skill is **overridden for this repository**. That override is
recorded in `CLAUDE.md` so that sessions loading the skill do not repeatedly re-open the
question or "correct" the wiring back to `.handle`.

## Consequences

**Accepted costs.**

- Every Scala session here auto-loads a skill whose guidance contradicts the codebase.
  `CLAUDE.md` mitigates this but does not eliminate the friction; expect to restate it.
- The Reactor-to-cats-effect bridge for MCP is real work, and it sits on the primary
  interface rather than at the edge.
- MCP stdio transport needs care: blocking stream reads inside a cats-effect runtime
  must be confined to a blocking execution context, or they will starve the compute
  pool. This is a known hazard, now explicitly on the record.
- Sub-100ms CLI startup is harder with a cats-effect runtime to initialise, and may
  force the CLI to avoid the effect stack entirely even though the daemon uses it.

**Gains.**

- A large, mature, well-documented ecosystem with strong Scala 3 support.
- fs2 gives genuine streaming, relevant if the event log ever needs tailing.
- Laminar cross-compiles the domain model between JVM and browser, making the "one
  core, many surfaces" claim literal rather than aspirational — this is independent of
  the effect-system choice, but it is the reason the frontend decision is not in
  tension with the backend one.

**Revisit if** the MCP bridge proves disproportionately painful, or if CLI startup
cannot be met without splitting the codebase so severely that the shared core is lost.
Both are failure modes the walking-skeleton prototype is designed to expose early.
