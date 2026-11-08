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
| M4 HTTP and MCP | the HTTP API and the MCP endpoint | built |
| M5 CLI | every `cloisters` command, `daemon` subcommands, the stdio MCP proxy | planned |
| M6 Web UI | issue and page views, the page editor, live updates | planned |

So today the daemon starts, takes its lock, opens and migrates the store, snapshots it, and serves the HTTP API and
MCP on `127.0.0.1`. There is no web UI yet, and the CLI has no commands: create projects over the HTTP API (below).

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
5. serves HTTP and MCP on `127.0.0.1:<port>`, prints one line and holds until stopped:

```text
cloisters daemon 0.1.0-SNAPSHOT on http://127.0.0.1:7227 store /Users/you/.local/share/cloisters/cloisters.db (schema v1)
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
| `:port` | `7227` | The TCP port the daemon serves on, bound to `127.0.0.1` only. Must be an integer from 1 to 65535. |

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

## HTTP API

Served on `http://127.0.0.1:<port>/api/…`. The spec is
[Define the shared contract and HTTP adapter](.wayfinder/tickets/T10-shared-contract-and-http.md) and the inherited
[Design the HTTP API shape](docs/inherited/tickets/T09-http-api-shape.md). Bodies and responses are JSON in
snake_case, with nulls left out; request bodies take the same arguments as the MCP tool of the same name, without
`id`.

| Route | Does |
|---|---|
| `GET /api/meta` | the daemon version and data directory |
| `GET /api/projects` · `POST /api/projects` `{key, name}` | list or create projects |
| `GET /api/search?q=&cursor=&limit=` | one search over issues and pages: `{effective_query, results, next_cursor, has_more}` |
| `POST /api/issues` · `GET /api/issues/{id}?include=events` · `PATCH /api/issues/{id}` | create, read, update an issue |
| `POST /api/issues/{id}/claim` · `/release` · `/reassign` · `/close` · `/reopen` | the intent actions |
| `GET /api/issues/{id}/events?cursor=&limit=` | an issue's full history, with full before and after text |
| `POST /api/pages` · `GET /api/pages/{id}?revision=&include=revisions` · `PATCH /api/pages/{id}` | create, read (optionally an old revision), update or move a page |
| `POST /api/pages/{id}/archive` · `/unarchive` · `/restore` | archive and unarchive (summary required), restore a revision |
| `GET /api/pages/{id}/revisions` · `GET /api/pages/{id}/diff?from=&to=` | a page's revisions; a diff between two |
| `GET /api/events?after=&limit=` | every event after a sequence number |
| `GET /api/events/stream?after=` | the same as server-sent events, each `id:` its sequence; resumes from `Last-Event-ID` |

- **Text.** Send `Accept: text/plain` for the compact text rendering MCP's text block carries (what the CLI prints).
- **Errors** are `{error, message, …facts}`, with status 404 (`not_found`, `unknown_project`, `wrong_kind`), 400
  (`invalid_query`, `invalid_argument`, `edit_mismatch`) or 409 (every invariant or concurrency refusal). Switch on
  `error`, never on the status. As text, a refusal reads `claim_conflict: …`.
- **Headers.** `Cloisters-Project: CLO` binds unscoped searches and creates to a project, as the CLI will from a
  repository's binding. `Cloisters-Client: cli` names the event actor; without it, events record `http`.
- **Localhost only.** The daemon binds `127.0.0.1`, and refuses with 403 any `Host` other than `localhost` or
  `127.0.0.1` on its port, and any foreign `Origin`.

```sh
api() { curl -s -H 'Content-Type: application/json' -H 'Cloisters-Client: cli' "$@"; }
api -X POST localhost:7298/api/projects -d '{"key":"CLO","name":"Cloisters"}'
api -X POST localhost:7298/api/issues -d '{"project":"CLO","title":"Write the glossary"}'
api -H 'Accept: text/plain' 'localhost:7298/api/search?q=ready'
```

## MCP

Served at `http://127.0.0.1:<port>/mcp`, hand-rolled per [ADR 0001](docs/adr/0001-hand-rolled-mcp.md): protocol
revisions 2026-07-28 (stateless) and 2025-11-25 (initialize handshake), JSON responses only, tools only. Bind a
connection to a project in the URL, so creates default to it and unscoped searches stay in it:

```sh
claude mcp add --scope project --transport http cloisters 'http://127.0.0.1:7227/mcp?project=CLO'
```

Sixteen tools ([Design the MCP tool contract](docs/inherited/tickets/T07-mcp-tool-contract.md),
[Extend the MCP tool contract for pages](.wayfinder/tickets/T11-mcp-tools-for-pages.md)): `search`, `get_issue`,
`create_issue`, `update_issue`, `claim_issue`, `release_issue`, `reassign_issue`, `close_issue`, `reopen_issue`,
`get_page`, `create_page`, `update_page`, `archive_page`, `unarchive_page`, `restore_page` and `diff_page`. Each
returns a text block and `structuredContent` matching its declared `outputSchema`; a refusal is an `isError` result
carrying `{error, …facts}`. Events record the actor as `mcp:<client name>`.

## Planned surfaces

None of these exist yet; the linked tickets are the spec.

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
scripts/conformance.sh   # MCP conformance at both revisions, against the uberjar (needs `npm ci`)
```

The tests always run against real SQLite files in temporary directories, never a fake. The lock tests start the
daemon in a separate JVM, because a file lock belongs to a process. The MCP tests validate every tool's result
against the JSON Schema it declares. CI runs lint, the format check, the tests, the uberjar, the conformance suite
and a CLI load check on `ubuntu-latest` and `macos-latest`. Run all of them before pushing.

| Path | Is |
|---|---|
| `src/cloisters/` | the daemon: `paths`, `lock`, `store`, `backup` and `main`; the core (`core`, `contract`, `issues`, `pages`, `projects`, `events`, `query`, `search`); the adapters (`http`, `mcp`) and the shared `text` rendering |
| `conformance/` | the MCP conformance suite's expected-failures baselines, one per revision |
| `scripts/` | `conformance.sh`, the CI conformance gate |
| `resources/migrations/` | numbered schema migrations; append-only once merged |
| `test/cloisters/` | tests |
| `cli/cloisters.clj` | the babashka CLI, one file |
| `build.clj` | tools.build: the daemon uberjar |
| `docs/build-plan.md` | the milestone order |
| `.wayfinder/`, `docs/inherited/` | planning history; never edited |

Contributor conventions, including the Clojure style rules and shell quirks, are in [CLAUDE.md](CLAUDE.md). Each
milestone is built on its own `build/mN-<name>` branch from `main` and merged by squash PR once CI is green.
