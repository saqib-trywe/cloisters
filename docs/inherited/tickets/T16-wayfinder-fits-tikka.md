---
id: T16
title: Confirm wayfinder fits tikka's contract
type: grilling
status: closed
assignee: saqib
blocked-by: []
---

## Question

The map's destination says it is done "when wayfinder itself could be hosted on tikka". Before anyone builds, walk
every wayfinder operation against the settled contract and name each gap: the nine MCP tools
([Design the MCP tool contract](T07-mcp-tool-contract.md)), the query grammar
([Define the shared query and filter grammar](T08-query-grammar.md)), the invariants
([Define tikka's core invariants](T02-core-invariants.md)) and mentions
([Settle body and mention conventions](T15-body-and-mention-conventions.md)).

Operations to walk: the map as an issue with a label and a body holding Destination, Notes, Decisions so far, the
unmapped items and Out of scope; tickets as child issues with a type label; blocking; the frontier query and "the first
frontier ticket in order"; claiming by assignee before work, with concurrent sessions skipping claimed tickets; recording a
resolution, closing, and appending to the map's Decisions; create-then-wire; ruling a ticket out of scope; updating or
retiring invalidated tickets without deletion; loading the map at low resolution; referring to tickets by name.

Resolve with: each operation's tikka expression, and any gap either fixed by amending a ticket or consciously accepted.
Tikka still never learns what wayfinder is.

## Resolution

Decided 2026-09-15. **Wayfinder fits**, with one gap fixed and two limits accepted. Tikka learns nothing about wayfinder:
everything rides on labels, parent and blocking edges, claims, closes and markdown.

| Wayfinder operation | tikka expression |
|---|---|
| Map: an issue labelled `wayfinder:map` | `create_issue(labels: ["wayfinder:map"])`; found by `search_issues label:wayfinder:map` |
| Map body sections | markdown body |
| Tickets: child issues with a type label | `create_issue(parent: <map>, labels: ["wayfinder:<type>"])` |
| Create-then-wire | sequential creates, then `update_issue(blocked_by_add)`; cycles refused with the path |
| Frontier | `ready parent:<map>`: tikka's unblocked means every blocker closed, and the map is not its own child |
| First frontier ticket in order | default rank order |
| Load the map at low resolution | `get_issue(<map>)` plus `search_issues parent:<map>` rows (no bodies) |
| Claim before work | `claim_issue(<ticket>, "<dev>/<session>")`, see below |
| Resolution comment and close | `close_issue(done, comment: <answer>)` |
| Append to Decisions so far | `update_issue(<map>, body_edits: [{old: "## Not yet specified", new: "- [name](/i/KEY-N): gist\n\n## Not yet specified"}])`, safe for concurrent sessions without a version |
| Refer by name | `[Title](/i/KEY-N)`; the link destination counts as a mention and renders live |
| Rule out of scope | `close_issue(dropped, comment: why)`, exempt from the open-blocker rule |
| Retire invalidated tickets | close `dropped`, or re-wire blockers; no deletion needed |
| Inherited notes on other open tickets | `body_edits`, needing no claim or version |
| Stale claims | `claimed-before:<age>`, then `reassign_issue(from, to: none)` |
| Frontier visible in the UI | tree view with open, claimed and blocked markers |
| Map closes when no tickets remain | enforced: a closed issue has no open children |

### Gap fixed: parallel sessions of one developer

Wayfinder claims by assigning to "the dev driving the map". Two parallel sessions claiming as the same name would **both**
succeed, because the tool contract makes claiming what you already hold idempotent, and both would do the work. A shared name cannot
separate a session's own retried claim from a sibling's claim. **Fix: the assignee names the session** (`saqib/wf-7f3a`).
The `claim_issue` description tells agents to use a name unique to their session, and the grammar's `assignee:` gains trailing-`*`
prefix matching (`assignee:saqib/*`), as labels have. Idempotent claims stay. Rejected dropping idempotency (a retry would report
its own claim as a conflict) and accepting the race. The glossary's Assignee entry is amended.

### Limits accepted

- **Superseded decisions keep `done`.** A resolution is immutable while closed. Supersession is a comment plus a body note (as this
  map did for the Clojure survey); the work was completed, so `done` stays true.
- **One project per map.** Parent and blocking edges stay within a project; a map is bound to one repo, and so one project.
  Dependencies on other projects are mentions (`WF-12`), which cross projects and show as backlinks. Cross-map blocking, if ever
  needed, is a question for the invariants.
