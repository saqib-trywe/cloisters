# Local-markdown tracker

The wayfinder map and its tickets live here as markdown files. This file documents
the conventions a session needs to read and write them.

## Layout

```
.wayfinder/
  README.md      this file
  MAP.md         the map (label: wayfinder:map)
  tickets/
    T01-<slug>.md
```

## Ticket frontmatter

```yaml
---
id: T01
title: <the ticket's name — how humans refer to it>
type: research | prototype | grilling | task
status: open | closed
assignee: <name of the session/dev holding the claim, or null>
blocked-by: [T02, T03]
---
```

Markdown has no native blocking relationship, so `blocked-by` is the body-convention
fallback. It lists ticket ids that must be `closed` before this ticket is takeable.

## Wayfinding operations

**Create the map** — write `MAP.md`.

**Create a ticket** — write `tickets/T<nn>-<slug>.md` with the frontmatter above and a
`## Question` section. Ids are assigned in creation order and never reused.

**Claim a ticket** — set `assignee` in the frontmatter, and commit, *before* any work.
An open ticket with `assignee: null` is unclaimed.

**Resolve a ticket** — append a `## Resolution` section to the ticket body, set
`status: closed`, and append a one-line gist plus link to the map's Decisions-so-far.

**Rule a ticket out of scope** — set `status: closed` with no `## Resolution`; add a
line to the map's Out-of-scope section instead of Decisions-so-far.

**Query the frontier** — open + unclaimed + every id in `blocked-by` closed:

```sh
.wayfinder/frontier.sh
```
