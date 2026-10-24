---
id: T10
title: Daemon lifecycle and repo-to-project binding
type: grilling
status: closed
assignee: saqib
blocked-by: [T06]
---

## Question

A daemon owning the store solves the concurrency problem and creates an operational one:
something has to start it, and something has to decide which project a command means.

- **Starting.** Does the user start the daemon explicitly, does the CLI start it on
  demand when it finds nothing listening, or is it a launchd-style background service?
  On-demand start is the most pleasant and the most surprising when it goes wrong.
- **Port.** Fixed default, or discovered via a file under `~/.tikka/`? Fixed is simpler
  until two things want it.
- **Repo binding.** A `.tikka` file in the repo naming the project key was the sketch.
  Is it committed (so it travels with the repo) or ignored (local preference)? What does
  the CLI do when run outside any bound directory — error, or fall back to a default
  project?
- **Bootstrapping a project**: how does one get created, and does binding a repo to a
  non-existent project create it or fail?
- **Store location and the single-store assumption.** One store under `~/.tikka/` was
  settled; confirm nothing wants a per-repo store, and decide what happens if two
  daemons are started.
- **Upgrades**: what happens to the store when the schema changes? Answering "nothing,
  it is local, I will delete it" is legitimate — but say so deliberately.

Depends on [Choose the CLI approach](T06-cli-approach.md), since the CLI is what most
often trips the lifecycle.

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
an unscoped query defaults to the bound project. Decide what "bound" means where there is no
working directory — notably MCP clients — and what an unscoped query does then.

**Inherited from [Choose the persistence engine](T03-persistence-engine.md):**
the store is one SQLite file under `~/.tikka/`, opened in WAL mode with a single write connection,
so a second daemon on the same file would contend for the writer; decide how it is refused
(a lock file, or the port). Schema upgrades are yours; SQLite's `user_version` pragma makes a
migration runner cheap if you want one.

**Inherited from [Design the MCP tool contract](T07-mcp-tool-contract.md):**
an MCP connection is bound by its endpoint URL (`…/mcp?project=TIK`); the stdio proxy
(`tikka mcp`) reads the repo's binding and applies it. Unbound connections require `project` on
create and search all projects. Creating a project is a human act through the CLI or web, never MCP.

**Inherited from [Choose the MCP server implementation and transport](T04-mcp-implementation.md):**
decide what `tikka mcp` (the stdio proxy) does when no daemon is listening. MCP sessions on the
2025-11-25 lifecycle are in-memory and lost on restart (clients re-initialize), so restarts are
cheap for MCP. The daemon binds `127.0.0.1` only.

**Inherited from [Design the HTTP API shape](T09-http-api-shape.md):**
the CLI sends the binding as a `Tikka-Project` header and warns when `GET /api/meta` reports a
daemon version different from its own; decide whether that warning ever becomes an automatic
restart.

**Inherited from [Choose the CLI approach](T06-cli-approach.md):**
the CLI exits `3` when the daemon is unreachable; decide whether it (and `tikka mcp`) starts the
daemon instead. `tikka project new KEY "Name"` is the project-creation command, so decide how it
relates to binding a repo. The CLI and daemon are versioned separately, and the CLI warns on mismatch
via `/api/meta`. macOS and Linux only.

## Resolution

Decided 2026-09-15.

### Running the daemon

- **A per-user OS service.** `tikka daemon install` writes a launchd agent (macOS) or systemd user unit
  (Linux): start at login, restart on crash. `tikka daemon start|stop|restart|status|logs` wrap the service
  manager; `tikka daemon run` runs it in the foreground for development. **Nothing starts the daemon
  implicitly.** HTTP MCP clients cannot start anything, so the daemon must already be up, and a service
  manager gives crash restart without tikka reimplementing supervision. Rejected CLI auto-start (finding
  a JVM and detaching a child from a native binary: pleasant when it works, baffling when it doesn't).
- **What it runs as:** an assembly jar in `~/.tikka/lib/`, run by a JDK 21+ whose absolute path is
  recorded in the service unit at install. Upgrade = rebuild, reinstall the jar, `tikka daemon restart`.
  No GraalVM daemon: startup doesn't matter for an all-day process.
- **Port:** a fixed default, overridable in `~/.tikka/config.toml`, which the daemon and CLI both read. If
  the port is taken, the daemon refuses to start and names the port and config key. No discovery file:
  MCP configs hardcode the URL, so the port must not move.
- **One daemon per home:** an exclusive lock on `~/.tikka/daemon.lock`, held for the daemon's lifetime and
  recording pid, port and version. A second daemon fails immediately, naming the holder. `status` reads it.
  The port alone isn't enough, because a differently configured second daemon would open the same store.
- **`TIKKA_HOME`** overrides `~/.tikka` (store, config, lock, logs, backups) for every daemon and CLI
  command. Developing tikka while its own tickets live in tikka means a dev daemon with its own home and
  port; tests use a temporary home. There is no per-repo store: one store per home.
- **Graceful stop:** stop accepting requests, let in-flight writes finish for up to 5 s, checkpoint the WAL,
  release the lock.
- **Logs** live under the home, read with `tikka daemon logs`.

### When the daemon is down, or a different version

- **CLI:** exits `3`, naming the fix (`tikka daemon start`, or `tikka daemon install` if no service is
  installed).
- **`tikka mcp`:** checks `/api/meta` at launch and, if the daemon is down, exits non-zero with the same message on stderr,
  so the MCP client reports that the server failed to start. If the daemon dies mid-session, the proxy keeps running and answers each
  request with a JSON-RPC error naming the fix, extracting only the request `id` (the one exception to
  framing-only), then resumes forwarding once the daemon is back. A post-upgrade restart shouldn't force every
  stdio client to reconnect by hand.
- **Version mismatch:** one stderr warning line per CLI invocation, **never an automatic restart**, which
  would drop other agents' in-flight requests and sessions.

### Upgrades

**Numbered, forward-only migrations built into the daemon, tracked by SQLite's `user_version`**, applied at
startup in one transaction after a `VACUUM INTO <home>/backups/<timestamp>-v<N>.db` snapshot. A daemon
that finds a store newer than it knows refuses to open it, naming both versions. Rejected "it's local,
delete it": the store is the permanent record of agents' work, and issues are never deleted.

### Projects and binding

- **Repo binding:** a **committed** `.tikka` file (TOML, `project = "TIK"`), found by walking up from the
  working directory the way git finds `.git`. Committed because it's single-user and a fresh clone
  should just work. TOML so it can grow.
- **Outside a bound directory**, the CLI behaves like unbound MCP: unscoped searches span all projects, and
  `tikka new` needs `--project`.
- **Key format:** 2–10 characters, an uppercase letter then uppercase letters or digits. Keys are permanent,
  so validation is strict.
- **`tikka project new KEY "Name"`** creates a project. **CLI only**: the web UI lists projects but never
  creates them (this amends the tool contract's "CLI or web").
- **`tikka init KEY`** writes `.tikka` and the project-scoped MCP entry (`claude mcp add --scope project
  --transport http tikka http://127.0.0.1:<port>/mcp?project=KEY`, or prints the snippet for other clients).
  It **fails if KEY doesn't exist**, pointing at `tikka project new`, so a typo never becomes a permanent key.
  `tikka init KEY --new "Name"` creates and binds in one explicit step. The binding thus lives in two files
  (`.tikka` for CLI and stdio, the MCP config URL for HTTP clients); `init` writes both, and that duplication is
  accepted.

> **Amended by [Walking skeleton — surfaces over one core](T11-walking-skeleton.md):** the service unit runs the
> daemon with `--enable-native-access=ALL-UNNAMED` (sqlite-jdbc loads native code; JDK 26 warns otherwise). The migration
> runner must close every statement and result set before `VACUUM INTO`, and needs a test that migrates a non-empty store.

> **Refined by [Decide export and durability](T17-export-and-durability.md):** the daemon writes daily `VACUUM INTO`
> snapshots (at startup and every 24 h; 7 kept, migration snapshots kept indefinitely) and runs `PRAGMA quick_check` on the store at
> startup and on each snapshot. `tikka daemon export <file>` (JSON Lines, offline, read-only) and `tikka daemon restore
> <snapshot>` (daemon stopped, lock taken, current store moved aside) join the `daemon` subcommands.
