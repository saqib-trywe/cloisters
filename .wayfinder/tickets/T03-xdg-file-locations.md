---
id: T03
title: Place cloisters' files in the XDG base directories
type: grilling
status: open
assignee: saqib
blocked-by: []
---

## Question

Tikka kept everything under one home, `~/.tikka` (overridable by `TIKKA_HOME`): store, config, lock, logs, backups,
and the daemon jar. Cloisters must use the standard locations instead: `~/.config`, `~/.local`, `~/.cache`.

- Which file goes where: config, store, snapshots, lock, logs, the daemon jar, any cache? (`XDG_CONFIG_HOME`,
  `XDG_DATA_HOME`, `XDG_STATE_HOME`, `XDG_CACHE_HOME`, `XDG_RUNTIME_DIR`.)
- Are the `XDG_*` variables honoured on macOS too, with the Linux defaults rather than `~/Library`?
- What replaces the single `TIKKA_HOME` override, which tikka relied on for a dev instance inside the repository,
  scratch daemons and test isolation? One `CLOISTERS_*` variable that relocates everything, or the XDG variables
  alone?
- Default port, chosen so cloisters runs beside a live tikka daemon on 7117.

Amends [Daemon lifecycle and repo-to-project binding](../../docs/inherited/tickets/T10-daemon-lifecycle.md) and
[Decide export and durability](../../docs/inherited/tickets/T17-export-and-durability.md) on file locations only.
