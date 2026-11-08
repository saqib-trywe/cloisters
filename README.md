# cloisters

An issue tracker for humans and agents, plus a simple wiki, in Clojure. It is a rewrite of tikka. One daemon owns one
SQLite store and serves an HTTP API, MCP for agents and a web UI over one core. A separate babashka CLI talks to the
daemon's HTTP API.

- **Issues** are units of work: title, markdown body, open or closed, labels, an assignee taken by compare-and-set
  claims, a parent, and blocking edges.
- **Pages** are wiki documents: title and markdown body in a per-project page tree, with numbered revisions and
  archiving.
- Issues and pages share one id sequence per project, so `CLO-7` names exactly one thing. Writing an id in prose
  creates a mention, and the thing it names gains a backlink.

The vocabulary is defined in [CONTEXT.md](CONTEXT.md). Decisions live in [docs/adr/](docs/adr/) and the closed tickets
in [.wayfinder/tickets/](.wayfinder/tickets/).

## Status

The build follows [docs/build-plan.md](docs/build-plan.md), one milestone at a time. Only what is marked built works
today.

| Milestone | What it delivers | State |
|---|---|---|
| M0 Scaffold and CI | deps, uberjar, lint, format, test runner, CI on macOS and Linux | built |
| M1 Store and lifecycle | file locations, config, daemon lock, schema v1, migrations, snapshots, integrity checks | built |
| M2 Core | invariants, claims, events, page revisions, mentions, export | built |
| M3 Query grammar and search | the query language, full-text search, cursors | built |
| M4 HTTP and MCP | the HTTP API and the MCP endpoint | planned |
| M5 CLI | every `cloisters` command, `daemon` subcommands, the stdio MCP proxy | planned |
| M6 Web UI | issue and page views, the page editor, live updates | planned |

So today the daemon starts, takes its lock, opens and migrates the store, snapshots it, and holds until stopped. It
does not listen on a port yet, and the CLI has no commands.

## Requirements

- macOS or Linux.
- [mise](https://mise.jdx.dev), which installs the pinned toolchain from [mise.toml](mise.toml):

  | Tool | Version | Used for |
  |---|---|---|
  | JDK (Zulu) | 27.30.107.0 | the daemon |
  | Clojure CLI | 1.12.6.1673 | building, testing and running the daemon from source |
  | babashka | 1.13.227 | the CLI |
  | node | 26.12.1 | CI tooling only (the MCP conformance suite) |

- `sqlite3` is optional, for reading the store by hand.

## Setup

```sh
git clone git@github.com:saqib-trywe/cloisters.git
cd cloisters
mise install                  # the JDK, Clojure CLI, babashka and node pinned in mise.toml
mise use -g babashka@1.13.227 # babashka user-wide too; see below
npm ci                        # only if you want to run the conformance suite locally
```

Install babashka user-wide as well. The installed CLI starts with `#!/usr/bin/env bb`, and outside this repository
mise's shim finds no babashka version unless one is installed globally.

## Running the daemon

From source:

```sh
clojure -M:run
```

From the uberjar:

```sh
clojure -T:build uber    # writes target/cloisters-daemon.jar
java --enable-native-access=ALL-UNNAMED -jar target/cloisters-daemon.jar
```

Always pass `--enable-native-access=ALL-UNNAMED`, because sqlite-jdbc loads native code. The `:run` alias already
sets it.

On start the daemon:

1. resolves its directories and reads `config.edn`
2. takes the daemon lock
3. checks the store's integrity, then migrates it (snapshotting it first if it has data)
4. takes today's snapshot
5. prints one line and holds until stopped:

```text
cloisters daemon 0.1.0-SNAPSHOT on store /Users/you/.local/share/cloisters/cloisters.db (schema v1)
```

### A scratch or development instance

Give it its own home, so it never touches a real install. Use a spare port, never 7227 (the default, kept for a real
install) or 7117 (tikka):

```sh
export CLOISTERS_HOME="$(mktemp -d)"
mkdir -p "$CLOISTERS_HOME/config"
echo '{:port 7298}' > "$CLOISTERS_HOME/config/config.edn"
clojure -M:run
```

The development instance inside this repository uses `CLOISTERS_HOME=.cloisters-dev` (gitignored) on port 7228:

```sh
mkdir -p .cloisters-dev/config
echo '{:port 7228}' > .cloisters-dev/config/config.edn
CLOISTERS_HOME=.cloisters-dev clojure -M:run
```

### Stopping

Press Ctrl-C in the foreground, or send `SIGTERM` to the pid recorded in the lock. Never kill by pattern: that could
hit another instance.

```sh
kill "$(sed -E 's/.*:pid ([0-9]+).*/\1/' "$CLOISTERS_HOME/data/daemon.lock")"
```

A stop lets an in-flight write finish (for up to 5 seconds), checkpoints the WAL into the store file, and clears the
lock.

### When it refuses to start

The daemon exits with status 1 and one line on stderr naming the problem:

| Message | Meaning and fix |
|---|---|
| `another daemon holds …/daemon.lock: pid 25323, port 7298, version 0.1.0-SNAPSHOT` | A daemon is already running on this store. Use it, or stop it first. |
| `…/config.edn: :port must be a TCP port number, not "7228"` | Fix the named key in `config.edn`. |
| `…/config.edn is not readable EDN: …` or `… must hold one map, such as {:port 7227}` | Fix the file's syntax. |
| `the store is at schema v2, but this daemon knows only up to v1; run a newer daemon` | The store was migrated by a newer daemon. Run that version; an old daemon never opens a newer store. |
| `the store …/cloisters.db failed its integrity check (…). The newest passing snapshot is …; with the daemon stopped, run: cloisters daemon restore …` | The store is damaged. Restore the named snapshot (see [Restoring a snapshot](#restoring-a-snapshot)). |

## Configuration

### config.edn

One EDN map, read by the daemon at start (and, from M5, by the CLI). Every key is optional.

| Key | Default | Meaning |
|---|---|---|
| `:port` | `7227` | The TCP port the daemon serves on, bound to `127.0.0.1` only (from M4). Must be an integer from 1 to 65535. |

```clojure
;; ~/.config/cloisters/config.edn
{:port 7227}
```

A missing file means every default applies. An unusable value stops the daemon with a message naming the file and
key; nothing falls back silently.

### File locations

cloisters follows the XDG base directory layout on macOS as well as Linux. It never uses `~/Library`.

| File | Location | Default |
|---|---|---|
| config | `$XDG_CONFIG_HOME/cloisters/config.edn` | `~/.config/cloisters/config.edn` |
| store (plus `-wal` and `-shm` while running) | `$XDG_DATA_HOME/cloisters/cloisters.db` | `~/.local/share/cloisters/cloisters.db` |
| snapshots | `$XDG_DATA_HOME/cloisters/backups/` | `~/.local/share/cloisters/backups/` |
| daemon lock | `$XDG_DATA_HOME/cloisters/daemon.lock` | `~/.local/share/cloisters/daemon.lock` |
| daemon jar (from M5) | `$XDG_DATA_HOME/cloisters/lib/` | `~/.local/share/cloisters/lib/` |
| logs (from M5) | `$XDG_STATE_HOME/cloisters/logs/` | `~/.local/state/cloisters/logs/` |
| cache | none yet | |

An XDG variable that is unset, empty or relative is ignored, as the XDG spec requires.

Snapshots count as data rather than cache, because restoring depends on them. The lock sits beside the store, so it
moves with the store: two daemons can never open the same store, however their other directories are set.

### Environment variables

| Variable | Effect |
|---|---|
| `CLOISTERS_HOME` | Relocates everything under one directory and overrides the XDG variables. A relative path resolves against the working directory. |
| `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_STATE_HOME`, `XDG_CACHE_HOME` | Standard XDG locations, used when `CLOISTERS_HOME` is unset. |

With `CLOISTERS_HOME=/tmp/ch`:

```text
/tmp/ch/config/config.edn
/tmp/ch/data/cloisters.db
/tmp/ch/data/daemon.lock
/tmp/ch/data/backups/daily-2026-11-08.db
/tmp/ch/state/logs/
```

With only XDG variables set:

```sh
XDG_DATA_HOME=/srv/data XDG_CONFIG_HOME=/srv/config clojure -M:run
# store:  /srv/data/cloisters/cloisters.db
# config: /srv/config/cloisters/config.edn
```

## Data and durability

### The store

The store is one SQLite file in WAL mode, written with `synchronous=FULL`, so a committed write survives power loss.
All writes go through one connection, one at a time, and reads use a small pool beside it. Times are ISO-8601 UTC
text with milliseconds, and every status and kind is stored as a word, so `sqlite3` reads the store without cloisters:

```sh
sqlite3 ~/.local/share/cloisters/cloisters.db
```

```sql
PRAGMA user_version;                                   -- the schema version
SELECT key, name, next_number FROM project;            -- projects and their next id
SELECT project || '-' || number AS id, kind FROM item; -- every id, and whether it names an issue or a page
SELECT number, title, status, assignee FROM issue WHERE project = 'CLO' AND status = 'open' ORDER BY rank, number;
SELECT number, title, parent, archived, version FROM page WHERE project = 'CLO';
SELECT seq, project || '-' || number AS id, at, actor, comment, summary, revision FROM event ORDER BY seq DESC LIMIT 20;
SELECT field, op, old, new FROM change WHERE event = 42 ORDER BY position;
SELECT project, number FROM item_text WHERE item_text MATCH '"claim"'; -- case-insensitive substring search
```

The tables are:

| Table | Holds |
|---|---|
| `project` | key, name and the next number in the shared sequence |
| `item` | every issued id and its kind (`issue` or `page`), so one id names one thing |
| `issue` | each issue's current state |
| `label` | issue labels, case-insensitive |
| `blocks` | blocking edges between issues |
| `page` | each page's current state |
| `mention` | ids found in prose, including ones that don't exist yet |
| `event`, `change`, `event_related` | the append-only event log: one event per write, its field changes, and the other timelines it appears on |
| `item_text` | the full-text index over both kinds |

The schema is in [resources/migrations/](resources/migrations/). Read the store freely, but don't write to it while a
daemon is running: the daemon checks invariants in code, and the database constraints are only a backstop.

### Migrations

On start the daemon compares the store's `PRAGMA user_version` with the migrations it carries. If the store is behind,
the daemon migrates it:

1. If the store already has data, it writes a snapshot named `<UTC time>-v<old version>.db` (for example
   `20261108T101500.123Z-v1.db`) and checks it. Migration snapshots are kept forever.
2. It applies every missing migration in one transaction, so a failed migration leaves the store exactly as it was.

The daemon refuses a store newer than it knows, naming both versions. Migrations only go forward.

### Snapshots

The daemon writes `backups/daily-<date>.db` when it starts and every 24 hours after. It keeps the last seven dailies,
and a second snapshot on the same day replaces the first. Each snapshot is written with `VACUUM INTO`, which gives a
consistent copy even while the daemon is writing; copying the live file could catch the database and its WAL at
different moments. Each snapshot is then checked with `PRAGMA quick_check`. A snapshot that fails is deleted and
logged, and the daemon keeps running.

Back up the `backups/` directory with your usual tooling (Time Machine, restic, …). Those files are always
consistent; the live store file is not.

### Integrity checks

The daemon runs `PRAGMA quick_check` on the store at every start. If the store fails, the daemon refuses to start and
names the newest snapshot that passes.

### Restoring a snapshot

`cloisters daemon restore <snapshot>` arrives with the CLI in M5. It takes the lock, checks the snapshot, moves the
current store aside to `backups/pre-restore-<time>.db` and copies the snapshot in. Until then, do the same by hand,
with the daemon stopped:

```sh
data=~/.local/share/cloisters
snap="$data/backups/daily-2026-11-07.db"
sqlite3 "$snap" 'PRAGMA quick_check'   # must print: ok
mv "$data/cloisters.db" "$data/backups/pre-restore-$(date -u +%Y%m%dT%H%M%SZ).db"
rm -f "$data/cloisters.db-wal" "$data/cloisters.db-shm"
cp "$snap" "$data/cloisters.db"
```

Nothing is deleted, so a mistaken restore can itself be restored. The next start migrates the restored store as
usual.

## Planned surfaces

None of these exist yet. They are summarised here so the configuration above makes sense; the linked tickets are the
spec.

- **HTTP API (M4)** on `http://127.0.0.1:<port>/api/…`: REST plus intent actions for issues and pages, `Accept:
  text/plain` for the compact text rendering, an event feed and a resumable SSE stream. See
  [Define the shared contract and HTTP adapter](.wayfinder/tickets/T10-shared-contract-and-http.md).
- **MCP (M4)** at `http://127.0.0.1:<port>/mcp?project=CLO`, protocol revisions 2025-11-25 and 2026-07-28, with
  sixteen tools. See [Choose the MCP implementation](.wayfinder/tickets/T07-mcp-implementation.md) and
  [Extend the MCP tool contract for pages](.wayfinder/tickets/T11-mcp-tools-for-pages.md).
- **CLI (M5)**, installed with `bbin install cli/cloisters.clj --as cloisters`. bbin copies the script, so reinstall
  after every change. It covers `new`, `show`, `edit`, the `page` verbs, `project new`, `init`, the `daemon`
  subcommands (`install`, `run`, `start`, `stop`, `status`, `logs`, `export`, `restore`) and the `cloisters mcp` stdio
  proxy. See [Choose the CLI approach](.wayfinder/tickets/T09-cli-approach.md).
- **Repository binding (M5)**: a committed `.cloisters` file, `{:project "CLO"}`, found by walking up from the working
  directory. `cloisters init CLO` writes it, along with the project-scoped MCP entry.
- **Web UI (M6)** at `http://127.0.0.1:<port>/`: server-rendered with htmx. See
  [Choose the web UI approach](.wayfinder/tickets/T08-web-ui-approach.md).

## Development

```sh
clojure -M:lint          # clj-kondo over src, test, cli, build.clj
clojure -M:fmt check     # cljfmt; `clojure -M:fmt fix` rewrites
clojure -M:test          # test-runner
clojure -T:build uber    # target/cloisters-daemon.jar
clojure -M:run           # the daemon from source
```

The tests always run against real SQLite files in temporary directories, never a fake. The lock tests start the
daemon in a separate JVM, because a file lock belongs to a process. CI runs lint, the format check, the tests, the
uberjar and a CLI load check on `ubuntu-latest` and `macos-latest`. Run all of them before pushing.

| Path | Is |
|---|---|
| `src/cloisters/` | the daemon: `paths` (locations and config), `lock`, `store` (connections and migrations), `backup` (snapshots and integrity), `main` |
| `resources/migrations/` | numbered schema migrations; append-only once merged |
| `test/cloisters/` | tests |
| `cli/cloisters.clj` | the babashka CLI, one file |
| `build.clj` | tools.build: the daemon uberjar |
| `docs/build-plan.md` | the milestone order |
| `.wayfinder/`, `docs/inherited/` | planning history; never edited |

Contributor conventions, including the Clojure style rules and shell quirks, are in [CLAUDE.md](CLAUDE.md). Each
milestone is built on its own `build/mN-<name>` branch from `main` and merged by squash PR once CI is green.
