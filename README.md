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

cloisters tracks its own build. Since the dogfood point the vocabulary, decisions and build plan are pages in
cloisters' own project `CLO` (*Context* CLO-4, *Decisions* CLO-5, *Build plan* CLO-7), and the milestones are issues
under the map CLO-1. [CONTEXT.md](CONTEXT.md), [docs/adr/](docs/adr/) and [docs/build-plan.md](docs/build-plan.md)
are frozen copies, and [docs/dogfood-export.jsonl](docs/dogfood-export.jsonl) is a readable export committed with
each milestone. The planning history is in [.wayfinder/tickets/](.wayfinder/tickets/).

## Status

The build follows the *Build plan* page (frozen as [docs/build-plan.md](docs/build-plan.md)), one milestone at a time. Only what is marked built works
today.

| Milestone | What it delivers | State |
|---|---|---|
| M0 Scaffold and CI | deps, uberjar, lint, format, test runner, CI on macOS and Linux | built |
| M1 Store and lifecycle | file locations, config, daemon lock, schema v1, migrations, snapshots, integrity checks | built |
| M2 Core | invariants, claims, events, page revisions, mentions, export | built |
| M3 Query grammar and search | the query language, full-text search, cursors | built |
| M4 HTTP and MCP | the HTTP API and the MCP endpoint | built |
| M5 CLI | every `cloisters` command, `daemon` subcommands, the stdio MCP proxy | built |
| M6 Web UI | issue and page views, the page editor, live updates | built |

So today the daemon starts, takes its lock, opens and migrates the store, snapshots it, and serves the HTTP API, MCP
and the web UI on `127.0.0.1`; the `cloisters` CLI covers every operation, runs the daemon as a login service and
binds repositories.

## Requirements

- macOS or Linux.
- [mise](https://mise.jdx.dev), which installs the pinned toolchain from [mise.toml](mise.toml):

  | Tool | Version | Used for |
  |---|---|---|
  | JDK (Zulu) | 21.54.109.0 | the daemon |
  | Clojure CLI | 1.12.6.1673 | building, testing and running the daemon from source |
  | babashka | 1.13.227 | the CLI |
  | node | 26.12.1 | CI tooling only (the MCP conformance suite) |
  | hyperfine | 2.0.0 | CI tooling only (the CLI's startup gate) |

- `sqlite3` is optional, for reading the store by hand.

## Setup

```sh
git clone git@github.com:saqib-trywe/cloisters.git
cd cloisters
mise install                  # the JDK, Clojure CLI, babashka and node pinned in mise.toml
mise use -g babashka@1.13.227 # babashka user-wide too; see below
npm ci                        # only if you want to run the conformance suite locally
clojure -T:build uber         # target/cloisters-daemon.jar
bbin install cli/cloisters.clj --as cloisters   # the CLI, as ~/.local/bin/cloisters
cloisters daemon install      # copy the jar, write the login service and start it
```

Install babashka user-wide as well. The installed CLI starts with `#!/usr/bin/env bb`, and outside this repository
mise's shim finds no babashka version unless one is installed globally. bbin copies the script, so run
`bbin install` again after every CLI change. To upgrade the daemon, rebuild the jar and run `cloisters daemon install`
again.

## Running the daemon

As a login service, with the CLI (see [The daemon as a service](#the-daemon-as-a-service)):

```sh
cloisters daemon install   # then: cloisters daemon start | stop | restart | status | logs
```

In the foreground, through the CLI, from the installed jar or a given one:

```sh
cloisters daemon run --jar target/cloisters-daemon.jar
```

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

The development instance inside this repository, which tracks cloisters' own build, uses
`CLOISTERS_HOME=.cloisters-dev` (gitignored) on the default port 7227, running a jar copied there:

```sh
export CLOISTERS_HOME="$PWD/.cloisters-dev"
cp target/cloisters-daemon.jar .cloisters-dev/
cloisters daemon run --jar .cloisters-dev/cloisters-daemon.jar
```

### Stopping

`cloisters daemon stop` stops the daemon of the current home: through the service manager when it runs one, then by
the pid recorded in the lock. In the foreground, Ctrl-C does the same. Never kill by pattern: that could hit another
instance.

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

One EDN map, read by the daemon at start and by every CLI command. Every key is optional.

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
| daemon jar, and `install.edn` recording it and its JDK | `$XDG_DATA_HOME/cloisters/lib/` | `~/.local/share/cloisters/lib/` |
| the service's log | `$XDG_STATE_HOME/cloisters/logs/daemon.log` | `~/.local/state/cloisters/logs/daemon.log` |
| repository binding | `.cloisters` in the repository, found by walking up | |
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

With the daemon stopped:

```sh
cloisters daemon stop
cloisters daemon restore daily-2026-11-07.db   # a name in backups/, or a path
cloisters daemon start
```

It runs the daemon jar offline, with the JDK recorded at install. It takes the lock (so it refuses while a daemon
runs), checks the snapshot with `quick_check`, refuses a snapshot whose schema is newer than the daemon knows, moves
the current store (and its `-wal` and `-shm` files) aside to `backups/pre-restore-<time>.db`, and copies the snapshot
in. Nothing is deleted, so a mistaken restore can itself be restored. The next start migrates the restored store as
usual.

### Exporting

```sh
cloisters daemon export cloisters.jsonl
```

JSON Lines in the contract's own types: projects, then issues and pages by project and number, then every event by
sequence. Two exports of one store are byte-identical. It reads the store file directly, so it works whether or not a
daemon is running. There is no import.

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
- **Headers.** `Cloisters-Project: CLO` binds unscoped searches and creates to a project, as the CLI does from a
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

## CLI

`cli/cloisters.clj`, one babashka script using only what babashka bundles, installed with
`bbin install cli/cloisters.clj --as cloisters`. It shares no code with the daemon: it sends requests and prints the
daemon's own text rendering, the same text MCP results carry. The spec is
[Choose the CLI approach](.wayfinder/tickets/T09-cli-approach.md) and the inherited
[Choose the CLI approach](docs/inherited/tickets/T06-cli-approach.md). `cloisters help` lists every command.

```sh
cloisters init CLO --new "Cloisters"   # create project CLO and bind this directory to it
cloisters new "Write the glossary" --label docs
cloisters search ready                 # alias: ls
cloisters claim CLO-1                  # --as defaults to $CLOISTERS_ASSIGNEE, then your username
cloisters edit CLO-1                   # $VISUAL or $EDITOR on the body
cloisters close CLO-1 done -m "Written."
cloisters page new "Glossary" --body-file glossary.md -m "First draft"
cloisters page tree
cloisters show CLO-2 --events          # an issue or a page; --events adds its history or revisions
```

| Command | Does |
|---|---|
| `search [QUERY…]`, `ls` | one search over issues and pages; the query is sent raw, and the daemon refuses a bad one with suggestions |
| `show ID [--events] [--revision n]` | an issue or a page |
| `edit ID` | `$EDITOR` on the body, saved against the version read; a page takes `--title` and `-m` (summary) too |
| `edit ID [--title t] [--label +a\|-b]… [--blocked-by +ID\|-ID]… [--parent ID\|none] [--rank n\|--before ID\|--after ID] [-m comment]` | an issue's fields, without the editor |
| `new "<title>" [--body-file f\|-] [--label l]… [--parent ID] [--blocked-by ID]… [-m comment]` | create an issue |
| `claim`, `release ID [--as name]` · `reassign ID --from x --to y` | claims |
| `close ID done\|dropped -m "…"` · `reopen ID -m "…"` | close and reopen |
| `close --query '<q>' done\|dropped -m "…"` | bulk close: every open match, leaves first, one close each; not atomic, it stops at the first refusal |
| `page new\|tree\|history\|diff\|move\|archive\|unarchive\|restore` | the page verbs |
| `project new KEY "Name"` · `project list` | projects; creating one is a human act, never MCP |
| `init KEY [--new "Name"]` | bind this directory: write `.cloisters` and the `cloisters` entry in `.mcp.json` |
| `mcp` | a stdio MCP server, proxied to the daemon with this directory's binding |
| `daemon install\|uninstall\|run\|start\|stop\|restart\|status\|logs\|export\|restore` | the daemon |

- **Output** is the daemon's text, or with `--json` its JSON (`structuredContent`). It never changes when piped.
- **Exit codes**: `0` done; `1` refused, with the error code first on stderr (`claim_conflict: …`, or the error body
  with `--json`); `2` a usage error; `3` the daemon is unreachable, naming the fix.
- **Binding.** Every command sends the project named by the nearest `.cloisters` (walking up from the working
  directory), or by `--project KEY`. Outside a bound directory, searches span every project and `new` needs
  `--project`.
- **Warnings.** One stderr line when the daemon's version differs from the CLI's, or its data directory differs from
  the one the CLI resolves (which `daemon export` and `daemon restore` open). The CLI never restarts the daemon.
- **The editor.** `edit` writes the body to a temporary file and opens `$VISUAL`, else `$EDITOR`, else `vi`. An
  unchanged file is a no-op. A save always passes the version read, so a concurrent edit is refused as
  `stale_version` instead of overwritten, and a refused save keeps the edited file and prints its path.
- **The events' actor** is `cli`.

### Binding a repository

```sh
cd my-repo
cloisters init MY --new "My project"   # or `cloisters init MY` for an existing project
git add .cloisters .mcp.json
```

`init` refuses a key that doesn't exist unless `--new` names it, so a typo never becomes a permanent key. It writes
`.cloisters` (`{:project "MY"}`), read by the CLI and `cloisters mcp`, and merges a `cloisters` server into
`.mcp.json` (`http://127.0.0.1:<port>/mcp?project=MY`), which Claude Code reads. Commit both.

### The stdio MCP proxy

For clients that only start stdio servers, configure `cloisters mcp` as the command, run from the bound directory. It
reads newline-delimited JSON-RPC from stdin, POSTs each message to `/mcp?project=<binding>` and writes each response
back as a line; a notification's 202 writes nothing. It is framing only, except that it mirrors a 2026-07-28
message's version, method and tool name into the headers HTTP requires, carries the 2025-11-25 session id, and:

- exits non-zero, with the fix on stderr, if no daemon answers at launch;
- while the daemon is down, answers each request with a JSON-RPC error naming the fix;
- after a daemon restart, replays the client's `initialize` once, so a 2025-11-25 client keeps working without
  reconnecting.

### The daemon as a service

`cloisters daemon install [--jar path]` copies the jar (default `target/cloisters-daemon.jar`) to the data directory's
`lib/`, records the JDK it found (`$JAVA_HOME`, else `java` on `PATH`, resolved to its real `java.home` so no version
manager's shim ends up in the unit; 21 or later), and writes a per-user service:

| OS | Unit | Managed with |
|---|---|---|
| macOS | `~/Library/LaunchAgents/cloisters.daemon.plist` | `launchctl` in the `gui/<uid>` domain |
| Linux | `~/.config/systemd/user/cloisters.service` | `systemctl --user` |

The service starts at login and restarts after a crash, never after a clean stop. Because launchd and systemd don't
inherit the shell's environment, the unit records `CLOISTERS_HOME`, or the resolved XDG locations; reinstalling picks
up a changed environment. Its output goes to `logs/daemon.log` (`cloisters daemon logs [-f] [-n lines]`). `--dry-run`
prints the unit without changing anything. `daemon uninstall` removes the unit and keeps the data and jar. Nothing
starts the daemon implicitly.

## The web UI

Open `http://127.0.0.1:7227/` (or the configured port). It is server-rendered HTML with htmx, served by the daemon
itself: no build step and no external requests. The spec is
[Choose the web UI approach](.wayfinder/tickets/T08-web-ui-approach.md).

| Path | Shows |
|---|---|
| `/?q=<query>` | a search in the shared query grammar; `/` alone reopens your last query, or `ready` on a first visit |
| `/projects` | each project with its open, ready and page counts, linking to its trees |
| `/tree/CLO`, `/tree/CLO/pages` | a project's issue tree and page tree (`?archived=yes` shows archived pages) |
| `/tree/CLO-5` | everything under one issue or page |
| `/CLO-5` | the issue or page with that id: rendered body, edges, mentions, backlinks; an issue's comments and history |
| `/CLO-5/edit`, `/new?parent=CLO-5` | the page editor: markdown with a live preview, and an edit summary |
| `/CLO-5/history`, `/CLO-5/r3`, `/CLO-5/diff?from=2&to=5` | a page's revisions, one revision, and a side-by-side diff (default: the previous) |

- **Scope is the query.** There is no project picker: `project:CLO` scopes a view, and the query lives only in the URL
  and the search box, so any view is a link you can paste to an agent. "Recent changes" is
  `kind:page sort:updated-desc`.
- **Inline edits.** On an issue: comment, add or remove labels, close or reopen (with a comment), reassign (to
  nobody, too), move up or down. On a page: move to a new parent, move up or down, archive or unarchive (with a
  summary), restore a revision. Creating issues, editing their title or body, edges and claims stay in the CLI and
  MCP. The UI's writes record `web`.
- **Live.** The UI listens to the API's event stream. An issue or page you are reading refreshes in place; a form
  you are typing in is left alone. Lists never reshuffle under you: they count updates in a bar instead.
- **The editor never touches your text.** When someone else saves the page while you edit it, a banner names them
  and links the diff. Saving always sends the version you loaded, so a stale save is refused: your text is kept, the
  change underneath is shown, and you merge by hand and save again.
- **Markdown** renders with the daemon's own parser, so the UI links exactly the mentions the daemon counts. Raw HTML
  shows as text; only `http`, `https`, `mailto` and site-relative links and images render. A mention of a closed
  issue is struck through, an archived page is muted and marked, and an id not created yet is muted.

## Development

```sh
clojure -M:lint          # clj-kondo over src, test, cli, build.clj
clojure -M:fmt check     # cljfmt; `clojure -M:fmt fix` rewrites
clojure -M:test          # test-runner
clojure -T:build uber    # target/cloisters-daemon.jar
clojure -M:run           # the daemon from source
scripts/conformance.sh   # MCP conformance at both revisions, against the uberjar (needs `npm ci`)
scripts/cli-gate.sh      # the CLI's 100 ms gate and daemon subcommands, against the uberjar (needs hyperfine)
scripts/ui-gate.sh       # the jsdom UI check, against the uberjar (needs `npm ci`)
```

The tests always run against real SQLite files in temporary directories, never a fake. The lock tests start the
daemon in a separate JVM, because a file lock belongs to a process. The MCP tests validate every tool's result
against the JSON Schema it declares. The CLI tests run `bb cli/cloisters.clj` as a process against a daemon served in
the test JVM. The UI gate drives the real UI, htmx and live updates included, under jsdom against the uberjar
([scripts/ui-test.js](scripts/ui-test.js)). CI runs lint, the format check, the tests, the uberjar, the conformance
suite, the CLI gate and the UI gate on `ubuntu-latest` and `macos-latest`. Run all of them before pushing.

| Path | Is |
|---|---|
| `src/cloisters/` | the daemon: `paths`, `lock`, `store`, `backup`, `restore` and `main`; the core (`core`, `contract`, `issues`, `pages`, `projects`, `events`, `query`, `search`); the adapters (`http`, `mcp`, `ui`), the shared `text` rendering, `markdown` (mentions and the safe renderer) and `diff` |
| `resources/assets/` | the UI's `app.css`, `live.js` and the vendored `htmx.min.js` (4.0.0) |
| `conformance/` | the MCP conformance suite's expected-failures baselines, one per revision |
| `scripts/` | `conformance.sh`, `cli-gate.sh` and `ui-gate.sh` (with `ui-test.js`), the CI gates run against the uberjar |
| `resources/migrations/` | numbered schema migrations; append-only once merged |
| `test/cloisters/` | tests |
| `cli/cloisters.clj` | the babashka CLI, one file |
| `build.clj` | tools.build: the daemon uberjar |
| `docs/build-plan.md`, `CONTEXT.md`, `docs/adr/` | frozen at the dogfood point; the pages CLO-7, CLO-4 and CLO-5 are current |
| `docs/dogfood-export.jsonl` | the export of cloisters' own tracking, committed with each milestone |
| `.cloisters`, `.mcp.json` | this repository's binding to project `CLO` |
| `.wayfinder/`, `docs/inherited/` | planning history; never edited |

Contributor conventions, including the Clojure style rules and shell quirks, are in [CLAUDE.md](CLAUDE.md). Each
milestone is built on its own `build/mN-<name>` branch from `main` and merged by squash PR once CI is green.
