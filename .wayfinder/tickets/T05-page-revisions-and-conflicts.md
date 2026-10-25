---
id: T05
title: Define page revisions and edit conflicts
type: grilling
status: open
assignee: null
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
