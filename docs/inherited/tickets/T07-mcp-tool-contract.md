---
id: T07
title: Design the MCP tool contract
type: grilling
status: closed
assignee: saqib
blocked-by: [T02, T03]
---

## Question

The most consequential design on the map: MCP is tikka's primary interface, so this
contract *is* the product's API.

Settled already: coarse CRUD plus a small set of intent tools, ~7–8 total, where an
intent tool is justified only by an invariant or a race it enforces — never by
convenience. `claim_issue` (atomic compare-and-set), `close_issue` (demands resolution
and comment) and `frontier` are the known members.

[Define tikka's core invariants](T02-core-invariants.md) added hard requirements this
contract must carry:

- The assignee is **not** settable through generic update. It needs `claim`, `release`
  and `reassign` (naming the expected holder) — so the ~7–8 tool budget is under
  pressure. Decide whether those are three tools or one assignee tool with a mode.
- `close_issue` **returns the issues it newly unblocked**.
- Reopen exists and is invariant-checked — a tool, or a mode of close?
- Writes to title/body/labels take an **optional expected version**; stale → rejected.
- Every invariant rejection **names the conflict** — the open children, the open
  blockers, or the cycle path. Design the error shape so an agent can act on it without a
  follow-up query.
- There is no delete tool, by rule.

What this ticket must produce:

- The **full tool list** with names, argument schemas, and return shapes.
- **Response design for an LLM reader.** How much of an issue does `get_issue` return —
  body, comments, events, edges, all of it? Token cost is a real constraint; an agent
  listing 40 issues must not receive 40 full bodies. What does the list/detail split
  look like?
- **How errors read.** An agent that loses a claim race, or trips a cycle rejection,
  must understand what happened and what to do instead from the error alone.
- **Whether tools are project-scoped or global**, and how an agent says which project
  it means without being told twice.
- Whether write tools are idempotent, and what a retry after an ambiguous failure does.

Depends on [Define tikka's core invariants](T02-core-invariants.md) for the invariants
being enforced, and [Choose the persistence engine](T03-persistence-engine.md) for what
the store can do atomically.

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
list/search tools take a single `query` string in the shared syntax, documented in the
tool description; results are cursor-paged (default 50, max 200); query errors name the bad
token and suggest alternatives. `ready` is a query predicate — decide whether a dedicated
`frontier`-style tool still earns its place, or whether search with `ready` suffices.

**Inherited from [Choose the persistence engine](T03-persistence-engine.md):**
the version moves only on title, body and label changes. Rank is any decimal number, so a
"move between" tool computes a midpoint and can run out of precision in theory (refused, conflict
named). Writes run one at a time and every write is atomic with its events. No write accepts a
timestamp. Cursors guarantee that unchanged issues appear exactly once, and issues whose sort key
moves mid-paging may repeat or vanish, so document that in paging tool descriptions. `text:` is a
case-insensitive substring match.

## Resolution

Decided 2026-09-15. **Nine tools.** The count comes from the rule, not a target: a tool
exists if it is basic CRUD or enforces an invariant or race, and operations with different meanings
get separate tools, never mode flags. About 10 is a smell alarm, not a limit. Rejected a hard cap of 8,
which would force mode enums (`set_status(mode: …)`) that LLMs misfire more often than clearly
named tools.

### Tools

| Tool | Arguments | Returns |
|---|---|---|
| `search_issues` | `query`, `cursor?`, `limit?` (default 50, max 200) | `{effective_query, issues[row], next_cursor, has_more}` |
| `get_issue` | `id`, `include?: ["events"]` | all fields, body, version, edges as id/title/status (parent, children, blockers, blocking, mentions, backlinks), comments in full; events only on request |
| `create_issue` | `title`, `project?`, `body?`, `labels?`, `parent?`, `blocked_by?`, `rank?` \| `rank_before?` \| `rank_after?`, `comment?` | row + `version` |
| `update_issue` | `id`, `expected_version?`, `title?`, `body?` \| `body_edits?: [{old, new}]`, `labels_add?`, `labels_remove?`, `parent?` (`null` clears), `blocked_by_add?`, `blocked_by_remove?`, `rank?` \| `rank_before?` \| `rank_after?`, `comment?` | row + `version` |
| `claim_issue` | `id`, `assignee`, `comment?` | row + `claimed_at` |
| `release_issue` | `id`, `assignee`, `comment?` | row |
| `reassign_issue` | `id`, `from` (name or `none`), `to` (name or `none`), `comment?` | row |
| `close_issue` | `id`, `resolution` (`done` \| `dropped`), `comment` (required) | row + `newly_unblocked[row]` |
| `reopen_issue` | `id`, `comment` (required) | row + `version` |

**Row:** id, title, status, resolution, assignee, labels, parent, blocked, rank, updated,
about 40–60 tokens. A row never carries a body.

### Shape decisions

- **Scoping.** Tools are global, since ids are globally unique. An MCP connection may be
  **bound** to a project once in the client's MCP config: the endpoint URL carries it
  (`…/mcp?project=TIK`), and the stdio proxy reads the repo's binding. When bound, creates default to that
  project and unscoped queries stay inside it. When unbound, `create_issue` requires `project`
  and unscoped queries span all projects. An explicit project always wins. `effective_query` shows the
  scoping that was applied. Rejected a `use_project` tool, because session state breaks under the stateless
  2026-07-28 core and gets lost when a conversation is compacted.
- **Assignee.** Three tools, because release is the common failure path for agents ("blocked, giving this back")
  and should not be phrased as reassigning from yourself. On a closed issue all three are refused
  (`issue_closed`): the assignee there records who resolved it.
- **No ready tool.** `search_issues` leads its description with `ready` ("start here to find
  work"). A canned query is convenience, which the rule refuses. The word "frontier" is not adopted.
- **Comments ride on writes.** There is no comment tool. Every write takes an optional `comment`, appended in the
  same transaction as the change it explains, and a plain comment is `update_issue(id, comment)`. Close and reopen
  **require** one.
- **Edges and labels.** Blocking can be set in one direction only (`blocked_by_*`); to say "this blocks X",
  update X. Labels are add/remove only, never whole-set replace, so concurrent label edits commute. Relative rank
  (`rank_before`/`rank_after`) computes the midpoint inside the write transaction, which is justified by the race
  on reading neighbours.
- **Body edits.** `body` replaces the whole body. `body_edits` applies exact, unique-match `{old, new}` replacements in
  order, all or nothing; a missing or ambiguous `old` is refused with `edit_mismatch`, naming the edit.
  Edits need no version, since an edit whose `old` text is still present is safe by construction.
  Rejected `body_append`, which is just an edit anchored at the end.
- **Returns.** Writes return the row, the version and operation-specific consequences, never the full
  issue. Rejected echoing the full issue, which wastes tokens after a one-label change.
- **Rendering.** Every result has a compact text block (one line per row, e.g.
  `TIK-42  open · claimed by scala-survey · blocked  Choose the persistence engine  [grilling]`)
  plus `structuredContent` matching a declared `outputSchema`, both rendered from the same value.
  Rejected serialising the JSON as text: it costs about 3× the tokens.
- **Search description.** A grammar cheat sheet of about 150 tokens headed by `ready`, plus the paging guarantee:
  issues unchanged while you page appear exactly once.
- **No actor argument.** The event actor comes from the connection. What that holds belongs to
  [Define the event log schema](T14-event-log-schema.md), which may reopen this with a reason.
- **Tools only.** No MCP resources or prompts: client support is uneven, and a resource would duplicate
  `get_issue`. Omitted on purpose.
- **Refused:** project tools ((creating a project is a human act via the CLI — *amended by [Daemon lifecycle and repo-to-project binding](T10-daemon-lifecycle.md): CLI only, not web*)), batch creates (convenience; sequential
  creates already work), and delete (by rule).

### Errors

Every domain rejection is an **`isError` tool result**, never a JSON-RPC error, because clients often
hide those from the model. It carries a text block written for the model (what happened, the conflicting facts,
what to do instead) and `structuredContent {error: <code>, …facts}`. Every error carries the facts
needed to act without a follow-up query. Protocol errors are reserved for malformed calls and unknown tools.

| Code | Carries |
|---|---|
| `not_found` | the id |
| `unknown_project` | the valid project keys |
| `invalid_query` | bad token, suggestions |
| `invalid_argument` | argument, reason |
| `stale_version` | current version; current values of the guarded fields the write touched (body included only if touched) |
| `edit_mismatch` | the failing edit, and whether `old` was missing or ambiguous |
| `claim_conflict` | holder, claimed-at |
| `not_holder` | actual holder |
| `issue_closed` | resolution, closed-at |
| `open_children` | children as id/title |
| `open_blockers` | blockers as id/title |
| `cycle` | the path |
| `cross_project_edge` | both ids and projects |
| `resolution_immutable` | current resolution |
| `reopen_blocked` | the closed parent or `done` dependents |
| `rank_precision` | the neighbours |

Example text: *"TIK-7 is already claimed by `scala-survey` (since 2026-09-14 10:02). Choose
another issue from `search_issues ready`, or if that claim is stale, `reassign_issue` from
`scala-survey`."*

### Retries

Idempotent where the operation allows it, with no idempotency keys. Claiming an issue you already hold succeeds, releasing an unassigned
issue is a no-op, and closing an already-closed issue with the same resolution succeeds with no newly unblocked
issues. `create_issue` and comments are not idempotent, and their descriptions tell agents to search before
retrying. Rejected a `request_id` on every write: over localhost an ambiguous failure is a crashed daemon, not a
lost packet.

This resolves the map's unmapped item **MCP failure semantics**.

> **Refined by [Settle body and mention conventions](T15-body-and-mention-conventions.md):** titles are a mention
> source too, and a mention may name an issue that doesn't exist yet (listed in `get_issue` only once it exists). Prose is
> always returned verbatim, never decorated, so `body_edits` round-trips exactly.

> **Amended by [Confirm wayfinder fits tikka's contract](T16-wayfinder-fits-tikka.md):** the `claim_issue`,
> `release_issue` and `reassign_issue` descriptions tell agents that the assignee must name their **session**, not just their
> person (e.g. `saqib/wf-7f3a`). Two concurrent sessions sharing a name would both "claim" successfully, because claiming what
> you hold is idempotent.
