---
id: T15
title: Settle body and mention conventions
type: grilling
status: closed
assignee: saqib
blocked-by: []
---

## Question

How is an issue id recognised in prose, and what does a mention do once it is found?

Settled around it: mentions are **derived**, never hand-authored; they are stored as edges and
rewritten in the same transaction as the body or comment that produced them
([Choose the persistence engine](T03-persistence-engine.md)); they may cross projects
([Define tikka's core invariants](T02-core-invariants.md)); they never carry an event onto the
mentioned issue ([Define the event log schema](T14-event-log-schema.md)); markdown is rendered by
Laika in the browser with raw HTML off ([Shape the Laminar frontend](T05-web-ui-approach.md)).
The walking skeleton deliberately leaves mention detection out.

- **Detection.** Which occurrences of `TIK-42` count? Inside code spans and fenced code blocks?
  Inside URLs (`/i/TIK-42`)? Adjacent to other characters (`TIK-42s`, `(TIK-42)`, `xTIK-42`)?
  Case-sensitive? Does detection parse markdown, or pattern-match raw text, and do the web UI
  and the daemon then agree on what counts?
- **Unknown ids.** A mention of `TIK-99` before TIK-99 exists, or of a project key that doesn't
  exist: stored and resolved later, ignored, or rejected? Issue numbers are never reused, so a
  forward reference can only ever mean one issue.
- **Rendering.** Do mentions become live links in the web UI, and does a link show the target's
  title and status? What does the CLI and MCP text show?
- **Titles.** Do mentions in a title count, or only bodies and comments?
- **Edits.** Editing a body removes a mention: the backlink disappears. Is that right when the
  timeline still shows the old text?

## Resolution

Decided 2026-09-15.

### Detection

- **Markdown-aware.** Prose is parsed with **Laika** in a module built for the JVM and Scala.js (Laika 1.3.2 publishes no
  Scala Native artifact; the CLI never parses prose). The daemon detects mentions and the UI renders links from the same
  syntax tree, so the UI never links something the daemon didn't count. **Code never counts**: inline code spans and fenced or
  indented code blocks are skipped, so pasted logs and code create no backlinks. Rejected a raw-text regex, which would turn a
  pasted log into a dozen accidental backlinks.
- **Sources:** an issue's **title, body and comments**. Titles count because "Follow-up to TIK-3" is the most deliberate
  mention there is. The glossary is amended.
- **Token:** `KEY-N`, uppercase only, where the characters on either side are **not** letters, digits, `_` or `-`. `TIK-42`,
  `(TIK-42)`, `TIK-42.` and `/i/TIK-42` count; `tik-42`, `xTIK-42`, `TIK-42s`, `TIK-42-fix` and `TIK-042` don't.
  **Self-mentions are ignored.**
- **Link destinations** count when their path is exactly `/i/KEY-N`, whatever the host: a link into tikka's own issue page is
  a deliberate reference. Other URLs rely on the boundary rule.

### Resolution of tokens

- **A token counts only if its key is an existing project**, which removes `UTF-8`, `ISO-8601` and `SHA-256` style false
  positives.
- **Forward references are stored.** A mention of `TIK-99` before TIK-99 exists becomes a live backlink when it is created.
  Numbers are never reused, so it can only ever mean one issue. Rejected dropping them, since planning prose refers ahead constantly.
- **Creating a project rescans all prose for its key** in the same transaction, so whether a token counts never depends on
  when the text was written.
- **Mentions reflect current text.** Editing an id out of a title or body removes the backlink; the timeline keeps the old
  text. Comments are append-only, so a mention in a comment is permanent.

### Rendering

- **Web UI:** each counted mention is a link to `/i/KEY-N`. Closed targets are struck through, hovering shows the title and status,
  and ids that don't exist yet render as muted plain text ("SKL-99 doesn't exist yet"). Title and status come from the mentions
  list `get_issue` already returns, so no extra request is needed. Ids in code stay plain.
- **CLI and MCP:** prose is returned **exactly as written**, never decorated, because agents quote it back into `body_edits`,
  which needs exact text. Mentions and backlinks are already listed next to the body in `get_issue`.

### Consequences

- Mention changes write **no events**. The edit event already stores before and after text, and the mentioned issue's timeline
  stays quiet ([Define the event log schema](T14-event-log-schema.md)).
- The query grammar is unchanged: `mentions:TIK-99` for an issue that doesn't exist yet matches nothing ("an unknown id
  matches nothing"); once TIK-99 exists, the stored forward references match.
