---
id: T04
title: Define page identity and linking
type: grilling
status: open
assignee: null
blocked-by: [T02]
---

## Question

How is a page identified, addressed and linked?

- Page id and URL: a slug, a number, a key in the issue sequence (`CLO-42` might then name a page), or something
  else? Must it be speakable and typeable like an issue id?
- Is a page title unique within its project? What happens to `[[Old Title]]` links when a page is renamed?
- Link syntax for pages in the same project and in other projects; how page links sit beside issue mentions.
- Moving a page within its tree, and between projects (allowed or not).
- Removing a page: never deleted like issues? Archived? What happens to its children and its backlinks?

## Inherited from [Amend scope and shape for the wiki](T02-amend-scope-for-the-wiki.md)

Pages are never deleted; a page leaves the way by being **archived**, a reversible flag that keeps its id,
history, links and backlinks and hides it from the default tree and search. Siblings are ordered by **rank**,
defaulting to creation order: decide what a move does to the moved page's rank. No structural edges between pages
and issues; prose links are the only bridge.
