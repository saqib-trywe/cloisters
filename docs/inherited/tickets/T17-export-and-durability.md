---
id: T17
title: Decide export and durability
type: grilling
status: closed
assignee: saqib
blocked-by: []
---

## Question

What is the answer to "the daemon won't start" and "I want my data out"?

Already true: the store is one SQLite file readable in `sqlite3` without tikka
([Choose the persistence engine](T03-persistence-engine.md)); migrations take a `VACUUM INTO` snapshot first
([Daemon lifecycle and repo-to-project binding](T10-daemon-lifecycle.md)).

- Is that enough, or does tikka need an export, and in what shape (JSON lines, markdown files per issue, both)?
- Is there an import, and does it contradict "no write carries its own timestamp"?
- Routine backups beyond migrations: scheduled snapshots, retention, or the user's own backup tooling?
- What does restore look like, and does the lock or schema version guard it?

## Resolution

Decided 2026-09-15.

- **Export: `tikka daemon export <file>`, JSON Lines in the contract's own types**: projects, issues with current state,
  comments and events, one record per line. It runs **offline from the daemon jar**, read-only against the store file, and works
  whether or not a daemon is running (WAL allows the concurrent read). This answers "the daemon won't start" without the daemon, in
  a format that survives schema changes. Rejected markdown per issue (lossy; browsing is the UI's job) and relying on `sqlite3` alone.
- **No import.** Restore is putting a snapshot back, and moving machines is copying the store. An import would be the one path writing
  historical timestamps, which the persistence engine forbade. Moving wayfinder's markdown maps onto tikka stays with the
  out-of-scope seeding of the acceptance test.
- **The daemon snapshots itself:** `VACUUM INTO backups/daily-<date>.db` at startup and every 24 hours, keeping the last 7 dailies and
  every migration snapshot. Copying a live WAL-mode file (as Time Machine does) can catch the database and its WAL at different moments;
  a snapshot is always consistent, and the user's backup tooling picks those files up. Rejected leaving backups to the user.
- **Restore: `tikka daemon restore <snapshot>`** with the daemon stopped. It takes the lock (refusing while a daemon holds it), checks the
  snapshot's integrity and that its schema is not newer than the daemon knows, moves the current store to
  `backups/pre-restore-<timestamp>.db`, and copies the snapshot in. The next start migrates as normal. Nothing is deleted, so a mistaken
  restore is itself restorable.
- **Corruption detection:** `PRAGMA quick_check` at daemon startup and on each snapshot after writing it. A failing store refuses to
  start, naming the newest passing snapshot and the restore command. A failing snapshot is deleted and logged.
