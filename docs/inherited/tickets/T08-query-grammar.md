---
id: T08
title: Define the shared query and filter grammar
type: grilling
status: closed
assignee: saqib
blocked-by: [T02]
---

## Question

All four surfaces filter issues. If each invents its own filter syntax, tikka has four
subtly different query semantics and the bugs live forever in the gaps. One grammar,
four renderings.

The scope floor refused a query *language* — so this is structured filters, and the
work is deciding exactly which dimensions exist and how they compose.

- Which **filter dimensions**: status, resolution, label, assignee, parent, project,
  rank range, text? Which combinations are worth supporting?
- How do filters **compose** — implicit AND only, or are OR and negation needed? (Each
  addition is a step back toward JQL; the bar should be high.)
- **Blocking-aware predicates.** `is:unblocked` and the frontier query are the ones
  that matter and cannot be expressed by field equality alone. Is `frontier` a named
  predicate in the grammar, or a distinct endpoint?
- **Labels**: free-form strings or a controlled set? Wayfinder uses namespaced labels
  (`wayfinder:map`, `wayfinder:research`) — does tikka give namespacing any meaning, or
  are labels opaque? (Opaque keeps tikka ignorant of wayfinder, which is the standing
  preference.)
- How the one grammar **renders** per surface: CLI flags, HTTP query params, MCP tool
  arguments, UI controls.
- **Sort and pagination**, which an agent needs to avoid unbounded results.

Settled by [Define tikka's core invariants](T02-core-invariants.md), and so inputs rather
than questions here:

- **Unblocked** means every blocker is closed, *whatever its resolution*.
- **Default order** is rank ascending, ties broken by issue number — always total.
- The grammar must be able to express **claims older than a given age**; that filter is
  the entire mechanism for finding stale claims, since claims never expire.
- Parent and blocks edges never cross projects, so blocking-aware predicates can be
  evaluated within a single project.

Depends on [Define tikka's core invariants](T02-core-invariants.md), since
blocking-and-closure semantics define what "unblocked" means.

## Resolution

Settled across two grilling rounds with saqib, 2026-09-15.

### One syntax, four renderings

A single text syntax — `ready label:bug -assignee:none` — is the query on every surface:

- **CLI**: a positional argument, `tikka ls 'ready label:bug'`.
- **HTTP**: a single `?q=` parameter.
- **Web UI**: one search box holding the string; controls edit the string; the URL carries
  it, so every view is shareable.
- **MCP**: a `query` string argument, grammar documented in the tool description.

Internally the string parses to a structured filter type in the shared core, cross-compiled
so Laminar validates locally; parse and print round-trip losslessly. Rejected per-surface
native forms (flags, params, JSON schemas), which are four translations that drift. Agents
write GitHub-style search strings fluently, a query copies verbatim between CLI output and
an agent chat, and a structured JSON schema would cost tokens on every tool listing.

### Composition

AND across filters, OR within one (comma-separated values), negation of a whole filter
with a leading `-`. No parentheses and no cross-filter OR — that is where a query language
begins, and the scope floor refused one. The inexpressible residue is answered by running
two queries.

### Filters — the complete set

| Filter | Values |
|---|---|
| `project` | key(s), or `*`; defaults to the bound project |
| `status` | `open`, `closed` |
| `resolution` | `done`, `dropped` |
| `assignee` | a name, `none`, `any` |
| `label` | exact, or prefix with trailing `*` (`label:wayfinder:*`) |
| `parent` | direct children of an issue |
| `under` | descendants of an issue, any depth |
| `blocked` / `unblocked` | bare predicates; unblocked = every blocker closed, any resolution |
| `blocks` / `blocked-by` | direct blocking edges to a given issue |
| `mentions` / `mentioned-by` | derived mention edges to a given issue |
| `ready` | bare predicate, exactly `status:open unblocked assignee:none` |
| `created-` / `updated-` / `closed-` / `claimed-` + `before`/`after` | relative duration or ISO date |
| `id` | explicit list |
| `text` | full text over title, body and comments |
| `sort` | `rank` (default), `created`, `updated`, `closed`, each `-asc`/`-desc` |

Refused: rank ranges (rank orders, it does not select), version, comment author, and
sorting by title, assignee or label.

### Ready, not frontier

"Open, unblocked and unclaimed" is a tikka concept named **`ready`** — generic "work
takeable now". It is deliberately not called frontier, which is wayfinder's word: tikka
never learns its consumers' vocabulary. Wayfinder's frontier is `ready parent:TIK-1`.

### Labels

Lowercased on write, so no two labels differ only by case. Matched exactly or by trailing
wildcard. The wildcard is plain prefix matching; the colon means nothing to tikka.

### Text search

Full text over title, body and comments. Results keep the requested order — no relevance
ranking. This is a new hard requirement on the persistence engine.

### Order and paging

Every sort breaks ties by issue number, so order is always total. **Cursor pagination** on
all surfaces, default 50, maximum 200; responses carry an opaque next cursor and whether
more exists. Rejected offset paging because agents write while other agents page, which
shifts offsets and skips or duplicates results. The UI pages by "load more".

> **Refined by [Choose the persistence engine](T03-persistence-engine.md):** the guarantee is
> that issues which did not change while paging appear exactly once; an issue whose sort key
> moves mid-paging may repeat or be missed.

### Times

`-before`/`-after` suffixes taking a relative duration (`30m`, `2h`, `3d` = that long ago)
or an ISO date/date-time. Date-only values use the daemon's local zone. No comparison
operators.

### Strictness

Unknown filter names, invalid values and malformed tokens reject the whole query; the error
names the token and suggests valid alternatives. A silently ignored typo returns the wrong
issues with no signal — the worst failure mode for an agent. An empty query is valid and
matches everything in scope. An `id:` that does not exist matches nothing rather than
erroring.

> **Amended by [Confirm wayfinder fits tikka's contract](T16-wayfinder-fits-tikka.md):** `assignee:` accepts a
> trailing `*` prefix match, like `label:`, so `assignee:saqib/*` finds every session claim of one person. `none` and `any`
> are unchanged.
