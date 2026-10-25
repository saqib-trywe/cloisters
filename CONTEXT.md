# Context

Domain vocabulary for cloisters. A glossary, not a spec: no implementation details, no
decisions. Decisions live on [the map](.wayfinder/MAP.md); architecture lives in ADRs.

## Project

A namespace for issues and pages. Owns a short key (`CLO`), the one sequence that numbers
both its issues and its pages, and one page tree. Projects do not nest and carry no configuration — they exist to
scope ids and nothing else.

There is no separate *space*: where Confluence would say "the CLO space", cloisters says
"the CLO project's pages".

A key is 2–10 characters: an uppercase letter, then uppercase letters or digits (`CLO`,
`WF2`).

A project's key is permanent and a project is never removed: the key is part of every
issue id ever issued. Its display name is just a label and may change.

### Bound project

The project a working context means when it names none — a repository directory, or an
agent's connection to cloisters. Binding is set once, outside any conversation. Binding a
context to a project never creates the project. Naming a
project explicitly always overrides it; with no binding, nothing is assumed.

## Issue

The unit of work. One of cloisters' two substantial entities, beside the page. An issue has a title, a
markdown body, a status, labels, an assignee, an optional parent, blocking edges,
comments, and timestamps.

There are no issue *types* — no epic, story, bug, or task. Labels carry every semantic
distinction, which keeps cloisters ignorant of the vocabulary of whatever is using it.

### Issue id

A project key plus a monotonic integer: `CLO-42`. Assigned once, never reused, never
changed. Numbers increase in creation order; they are not promised to be gapless. Chosen to be speakable aloud and typeable into a command line.

Issues and pages draw from the same sequence, so an id names exactly one thing, issue or
page, and the id alone does not say which. Asking for a page as an issue, or the reverse,
is refused by saying what the id actually names.

### Version

A counter on each issue that moves only when its title, body or labels change. A writer
may state the version it last read; if the issue has moved on, the write is refused
rather than silently overwriting. Claims, comments, status changes and edges do not move
it — they have their own guards, or never conflict.

## Page

A wiki document: a title and a markdown body, belonging to one project's **page tree**.
A page has at most one parent page and any number of child pages; pages without a parent
are the tree's roots. A page tree never crosses projects.

A page is knowledge, not work: it has no status, assignee, claim or blocking edges. Pages
carry no labels and take no comments: to discuss a page, open an issue that mentions it.

A page is identified like an issue: an id such as `CLO-57`, from its project's sequence.
Its title is only a label — titles need not be unique — and a page never moves to another
project, since its id carries the key.

Siblings are ordered by **rank**, as issues are, defaulting to the page's number. A page
**moves** by taking a new parent, or none; it keeps its rank unless the move sets one, and
it can never move beneath itself.

### Archived

A page is either **live** or **archived**. Archiving is how a page leaves the way, since
pages, like issues, are never deleted. An archived page keeps its identity, its history,
and the links to and from it; it is only left out of the page tree and of search unless
asked for. Archiving is reversible. Links to an archived page still resolve, marked as
archived so a reader knows the page is stale.

A page with live children cannot be archived; the refusal names them. Archive or move
the children first — nothing disappears as a side effect.

### Revision

A saved state of a page. Every save makes a new revision; revisions are never edited or
removed, so a page's history can always be read, compared, and an earlier revision
restored — restoring makes a new revision, it does not rewind.

### Mentions of pages

Pages are mentioned exactly as issues are — see **Mention** — so there is no separate
kind of page link. Backlinks run in both directions: an issue lists the pages that
mention it, and a page lists the issues and pages that mention it.

## Status

Exactly two values: `open` and `closed`. Nothing else is a status.

**In progress** is not a status. It is *derived*: an open issue with an assignee is in
progress. This is a deliberate refusal — a stored in-progress flag can drift out of
agreement with the assignee, and a derived one cannot.

**Reopening** returns a closed issue to open, discarding its resolution (the timeline
keeps it). Like closing, it always carries a comment saying why.

Issues are never deleted. Closing as `dropped` is the only way out, including for issues
created by mistake.

## Resolution

Recorded when an issue is closed. Either `done` (the work was carried out) or `dropped`
(the issue was closed without being carried out — abandoned, obsolete, or ruled outside
the scope of the effort).

The distinction matters because "we finished this" and "we decided not to" are different
facts about the route taken, and a single closed status loses the difference.

Closing always carries a comment saying why. A resolution records *what* happened; the
comment records the reasoning a later reader will need.

## Assignee

Free text naming whoever or whatever holds the issue. Not a reference to a user entity —
cloisters has no users. An agent session and a human are the same kind of thing here.

The assignee names the *holder*, which for parallel work is a session, not a person: two
sessions driven by the same developer claim as distinct names (`saqib/wf-7f3a`). A name shared
between concurrent holders defeats the claim, since claiming what you already hold succeeds.

## Claim

Taking an issue by setting its assignee, conditional on it having none. A claim
**fails** rather than overwrites when the issue is already assigned, which is what makes
it safe for concurrent sessions to race for the same work. Claiming an issue you already
hold is not a conflict: it succeeds and changes nothing.

An open issue with no assignee is **unclaimed**.

The assignee changes only by claiming, releasing, or reassigning — each conditional on
who holds it now:

- **Release** — the holder gives the issue up.
- **Reassign** — someone other than the holder moves the issue on, naming the holder they
  expect to displace. Reassigning to nobody clears the claim.

A **stale claim** is one held long enough that its holder has probably gone — typically an
agent session that ended mid-work. Claims never lapse on their own; a stale claim stays
until someone reassigns it.

A closed issue's assignee is history — the record of who resolved it — so a closed issue
cannot be claimed, released or reassigned. Reopen it first if the work is live again.

## Comment

A remark appended to an issue. Comments are only ever added, never edited or removed, so
two writers can never conflict over one.

## Edges

Exactly three kinds of relationship between issues. Parent and blocks edges only connect
issues in the same project; mentions may reach across projects.

### Parent

Structural hierarchy. An issue has at most one parent and any number of **children**.
Hand-set. Carries containment, not sequencing — a parent is not blocked by its children.

### Blocks

An issue **blocks** another when the second cannot proceed until the first is closed.
Hand-set, many-to-many, and acyclic: writes that would form a cycle are rejected.

An issue is **blocked** while any issue blocking it is still open, and **unblocked**
once all of them are closed.

### Mention

A reference to an id occurring in prose: an issue's title, body or comments, or a page's
title or body. The id may name an issue or a page. Mentions are **derived**, not
curated: writing `CLO-42` in prose creates the edge, and whatever `CLO-42` names gains a
**backlink**. There is no way to hand-author or hand-remove a mention.

An id is written bare (`CLO-42`) or as a link to it (`[the glossary](/CLO-42)`). There is
no link by title: titles change, ids don't.

Only prose counts: an id inside code — an inline code span or a code block — is not a
mention, so pasted logs and code don't create backlinks. An id counts only when its
project exists; it may name something that doesn't exist yet, and becomes a live backlink
when that issue or page is created. Nothing mentions itself.

A mention reflects the current text: editing an id out of a title or body removes the
backlink. Comments are never edited, so a mention in a comment is permanent.

This is the whole of cloisters' "see also". Curated link types — *relates to*, *duplicates*,
*clones* — are deliberately absent; they go stale because nothing forces them to stay
true, whereas a derived edge cannot disagree with the text that produced it.

## Label

An opaque string attached to an issue. Cloisters attaches no meaning to any label and
interprets none of them, including namespaced ones like `wayfinder:map`. Meaning belongs
to whoever is reading.

Labels are case-insensitive: `Bug` and `bug` are the same label.

## Rank

The position of an issue in a total order, or of a page among its siblings. It defaults
to the issue's or page's number, so order is deterministic without anyone setting it, and
can be overwritten to reorder.

Rank is not priority. Cloisters has no priority.

A rank is any decimal number, so an issue or page can be placed between two others (3.5 sits
between 3 and 4) without moving either.

Ranks need not be unique; equal ranks are ordered by number.

## Event

A record that something about an issue changed: who, when, which field, and from what to
what. Events accumulate and are never edited or removed, forming an issue's **timeline**.

An issue's current state is the authority on what is true now; the timeline is the record
of how it got there. They are separate on purpose.

One write is one event, however many fields it changes; a comment given with the write
belongs to that event. A write that changes nothing records no event.

An event belongs to the issue written to, and also appears on the timeline of every issue
whose parent or blocking edges it changed, or that it unblocked or blocked again — closing
a blocker shows on each dependent it frees. A **mention** does not carry an event onto the
mentioned issue — the backlink appears, the timeline stays quiet.

The event names **who** by the surface and client that made the write (an agent through
MCP, the CLI, the web UI), not by a person.

An issue's **updated** time is the time of the latest event on its timeline — a comment or
a claim updates an issue as much as a title change does.

## Ready

An issue is **ready** when it is open, unblocked, and unclaimed — work anyone could take
right now.

Consumers may have their own word for a scoped slice of ready work (wayfinder calls its
version the *frontier*); cloisters does not adopt those words.
