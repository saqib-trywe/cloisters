---
id: T05
title: Define page revisions and edit conflicts
type: grilling
status: closed
assignee: saqib
blocked-by: [T04]
---

## Question

What exactly is a revision, and what happens when two writers edit one page?

- Does a revision capture title and parent as well as body? Does every save make one, including no-op saves?
- Concurrent edits: a version guard that refuses a stale save, like tikka's issue version? Is the human editor in
  the web UI guarded the same way as an agent?
- Diffing and restore: what is compared, and what a restore records.
- Who made a revision: by surface and client, as tikka's events do?
- How revisions relate to the event log (fog until here: see Not yet specified).

## Inherited from [Amend scope and shape for the wiki](T02-amend-scope-for-the-wiki.md)

No drafts or publish step: saving is publishing. Decide whether archiving, unarchiving and sibling reordering
make revisions, and whether a revision carries a save message (pages take no comments, so a save message is the only
place a page records why it changed).

## Inherited from [Define page identity and linking](T04-page-identity-and-linking.md)

A page is `CLO-57` from the project's shared sequence; its title is a label, not unique. Moves (new parent, or
none) keep rank unless set, and cycles are refused; moves between projects are refused. Archiving is refused while a
page has live children. Decide whether a move, a rename or a rank change makes a revision.

## Resolution

Grilled with saqib, 2026-10-25, in two rounds; every recommendation accepted. The glossary changes are in
[CONTEXT.md](../../CONTEXT.md) (Version, Revision, Edit summary, Event). This also clears the map's fog patch "The
event log for pages".

### Revisions are a view over the event log

- **Pages join tikka's event log** ([Define the event log schema](../../docs/inherited/tickets/T14-event-log-schema.md)):
  one write, one event, the same global sequence, the same "who" (surface plus client), full before and after text for
  prose changes, writes that change nothing record nothing, rejected writes leave no trace.
- **A revision is the page as it stood after an event that changed its title or body.** Events hold full text, so any
  revision reads on its own: no second store to drift from the log, the argument that already made comments events.
  Rejected a separate snapshot table beside the log.
- **Numbered per page**, r1, r2, …, derived and stable because events are never removed: "CLO-57 r3".
- **Content is title and body only.** Moves, rank changes and archiving are timeline events, not revisions, so
  restoring the text never moves or unarchives a page.

### Why a page changed

An **edit summary**, a distinct term because pages take no comments. It rides on the write's event, like an issue
write's comment. **Optional on edits, required to archive or unarchive**, mirroring close and reopen.

### Concurrent edits

- **A page's version is its latest revision number**: only title and body move it, so one counter serves both.
- **Agents** keep the inherited guards: an optional expected version, and `body_edits` (exact unique-match
  replacements) safe without one.
- **The web UI editor always sends the version it loaded.** A stale save is refused; the editor keeps the human's
  text, shows a diff of what changed underneath, and the human merges by hand and saves against the new version.
  Rejected an automatic three-way merge (a merge engine is a "while we're here") and last write wins.
- **Archived pages stay editable**: archiving hides a page, it does not lock it, and the version guard still applies.

### Diff and restore

- **Diff:** any two revisions of one page, defaulting to the previous one. Body as a unified line diff, title as
  before and after: the one shared line-diff implementation issue events already use. No diffs across pages.
- **Restore rN:** a write setting title and body to rN's, making a new revision whose event records the source
  revision as data ("r7: restored from r3"). Takes an expected version like any edit. Restoring content identical to
  the current is a no-op. Never touches parent, rank or archived state.

### Timelines

The inherited relation rule holds: a page event appears on the page and on every page whose structural edges it
changed, so **a move shows on the page, its old parent and its new parent** and moves each one's updated time. Edits and
archiving touch only the page. Mentions never relate.
