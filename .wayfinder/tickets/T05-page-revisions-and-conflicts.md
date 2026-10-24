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
