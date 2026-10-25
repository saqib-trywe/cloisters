---
id: T04
title: Define page identity and linking
type: grilling
status: closed
assignee: saqib
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

## Resolution

Grilled with saqib, 2026-10-25, in two rounds; every recommendation accepted. The glossary changes are in
[CONTEXT.md](../../CONTEXT.md) (Project, Issue id, Page, Archived, Mention, Rank).

### Identity

- **Pages share the project's sequence.** `CLO-57` names an issue or a page, never both; the number is issued once,
  to whichever is created. One token form and one detector, so every inherited mention rule (code never counts,
  existing keys only, forward references, the rescan on project creation, the boundary rule) covers pages unchanged.
  Issue numbers gain gaps, which the glossary already allows. Rejected a separate page token (`CLO-P7`: a second rule
  and detector, awkward aloud) and title slugs (renames break links unless cloisters rewrites prose, which verbatim
  prose forbids).
- **The id doesn't say its kind.** Asking for a page as an issue, or the reverse, is refused by naming what the id is
  ("CLO-7 is a page"). A forward reference learns its kind when the thing is created.
- **Titles are labels, not unique.** Nothing links by title, so uniqueness would be an invariant guarding something
  cosmetic. Duplicates show in search and the tree and are archived away.

### Addressing and links

- **One URL shape for both kinds: `/CLO-57`.** Keys are uppercase, so it never clashes with `/api`, `/mcp` or static
  assets. Amends [Settle body and mention conventions](../../docs/inherited/tickets/T15-body-and-mention-conventions.md):
  a link destination counts when its path is exactly `/KEY-N` (was `/i/KEY-N`). Rejected `/i/` and `/p/`, which make
  every writer know the kind.
- **Two ways to write a reference:** the bare id `CLO-57`, or a markdown link `[the glossary](/CLO-57)`. No
  `[[Title]]` syntax.
- **"Page link" folds into Mention**: a mention is an id in prose (an issue's title, body or comments; a page's title
  or body) naming an issue or a page, which gains a backlink. Nothing mentions itself.

### Moving

- **Within the tree:** a move sets a new parent, or none to become a root. A move beneath the page itself or a
  descendant is refused as a cycle. **The page keeps its rank** unless the same write sets one; since a page's rank
  defaults to its number, it lands in about creation order among its new siblings, and nothing is renumbered.
- **Between projects: refused.** The id carries the key, so a move would break every link. Relocate by creating the
  new page and archiving the old one with a line pointing to it; history stays where it was written. Issues don't
  move between projects either.

### Archiving

- **A page with live children cannot be archived**; the refusal names them, per "rejected writes name the conflict".
  Archive or move the children first. Rejected cascading (pages vanish from search as a side effect) and leaving live
  children under a hidden parent. Unarchiving stays a single-page act.
- **Links to an archived page still resolve.** The web UI shows them muted and marked "archived"; CLI and MCP return
  prose verbatim, and the mention lists say the page is archived, so an agent knows it is reading something stale.
