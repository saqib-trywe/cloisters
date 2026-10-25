---
id: T03
title: Place cloisters' files in the XDG base directories
type: grilling
status: closed
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

## Resolution

Grilled with saqib, 2026-10-25, in one round; every recommendation accepted. Amends
[Daemon lifecycle and repo-to-project binding](../../docs/inherited/tickets/T10-daemon-lifecycle.md) and
[Decide export and durability](../../docs/inherited/tickets/T17-export-and-durability.md) on file locations and
formats only; everything else in them carries.

### XDG on both platforms

Cloisters reads `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_STATE_HOME` and `XDG_CACHE_HOME` on macOS and Linux alike,
defaulting to `~/.config`, `~/.local/share`, `~/.local/state` and `~/.cache`. Never `~/Library`: one rule, one set of
docs and tests. Only the service unit goes where the OS dictates (`~/Library/LaunchAgents`, `~/.config/systemd/user`).

### Which file goes where

| File | Location |
|---|---|
| config | `$XDG_CONFIG_HOME/cloisters/config.edn` |
| store | `$XDG_DATA_HOME/cloisters/cloisters.db` (and its WAL files) |
| snapshots | `$XDG_DATA_HOME/cloisters/backups/` |
| daemon lock | `$XDG_DATA_HOME/cloisters/daemon.lock` |
| daemon jar | `$XDG_DATA_HOME/cloisters/lib/` |
| logs | `$XDG_STATE_HOME/cloisters/logs/` |
| cache | none yet; not created until something needs it |

- **Snapshots are data, not state**: restore depends on them, so they are not disposable.
- **The lock sits beside the store**, not in state or `XDG_RUNTIME_DIR`: it exists to stop two daemons opening the same
  store, so it must move with the store. A lock in state would let two daemons with different state directories share
  one data directory, and macOS has no `XDG_RUNTIME_DIR`.
- The CLI binary's location belongs to [Choose the CLI approach](T09-cli-approach.md).

### `CLOISTERS_HOME`

When set, it overrides the XDG variables and relocates everything under one directory, as `config/`, `data/`,
`state/` and `cache/` with the layout above. Unset, the XDG locations apply. Rejected the XDG variables alone: four
variables to isolate an instance, and exporting them relocates every XDG-aware tool. The dev instance is
`CLOISTERS_HOME=.cloisters-dev` (gitignored) with its own port; tests use a temporary home.

### The service's environment

launchd and systemd user units do not inherit the shell's environment, so **`cloisters daemon install` resolves the
locations at install time and writes them into the unit**, as tikka recorded the JDK path. `/api/meta` reports the
daemon's data directory, and the CLI warns once per invocation, like the version warning, when it differs from the
directory the CLI resolved itself (which is what `daemon export` and `daemon restore` open). Reinstalling picks up a
changed environment.

### Formats and names

**EDN, not TOML**, for both files: native to Clojure and babashka with no dependency, and the idiomatic choice. The
config is `config.edn`; the committed repo binding is a `.cloisters` file, `{:project "CLO"}`, found by walking up
from the working directory.

### Port

**7227** by default, clear of the live tikka on 7117. The dev instance uses 7228 in its own config.
