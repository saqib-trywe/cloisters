# cloisters

A Clojure rewrite of tikka (an issue tracker for humans and agents) plus a simple wiki. One daemon serves the HTTP
API, MCP and the web UI over one core and one SQLite store; a separate babashka CLI talks to its HTTP API.

- **cloisters tracks its own build** (project `CLO`, bound by `.cloisters` and `.mcp.json`), so work goes through the
  `cloisters` MCP tools. Read vocabulary with `get_page CLO-4` (*Context*), decisions under `CLO-5` (*Decisions*,
  one child page per ADR) and the plan with `get_page CLO-7` (*Build plan*). The map is the issue `CLO-1`; its
  milestones are its children, and `search` with `ready parent:CLO-1` gives the frontier.
- `CONTEXT.md`, `docs/adr/` and `docs/build-plan.md` are frozen at the dogfood point: edit the pages instead, and
  write new ADRs as child pages of `CLO-5`.
- The build follows the *Build plan* page: one milestone per branch, each implementing the tickets it names. Tickets
  are the spec; read them to the end, because *Amended by* notes override the text above them.
- `.wayfinder/` and `docs/inherited/` are history: never edit them.

## The dev daemon

The tracking daemon runs from `.cloisters-dev/` (gitignored) on port 7227, the default: it is Saqib's only cloisters.
Start it from the repository root, from a jar copied there, because `clojure -T:build uber` deletes `target/`:

```sh
export CLOISTERS_HOME="$PWD/.cloisters-dev"
cp target/cloisters-daemon.jar .cloisters-dev/
cloisters daemon run --jar .cloisters-dev/cloisters-daemon.jar
```

Every CLI command against it needs that `CLOISTERS_HOME`. If MCP tools fail to connect, ask Saqib to start it; never
start a second one. After each milestone merge: build the jar, `cloisters daemon stop`, copy the jar, start it again,
then commit the export with the merge:
`JAVA_HOME=$(mise where java) cloisters daemon export docs/dogfood-export.jsonl --jar .cloisters-dev/cloisters-daemon.jar`.

## Layout

| Path | Is |
|---|---|
| `src/cloisters/` | the daemon: core, store, and the HTTP, MCP and UI adapters |
| `test/cloisters/` | tests, run by cognitect-labs test-runner |
| `cli/cloisters.clj` | the babashka CLI, one file |
| `build.clj` | tools.build: the daemon uberjar |
| `conformance/` | MCP conformance expected-failures baselines, one per protocol revision |
| `scripts/conformance.sh` | the conformance gate: runs the uberjar under a scratch home, both revisions |
| `scripts/cli-gate.sh` | the CLI gate: `cloisters search` under 100 ms (hyperfine), then the daemon subcommands against the uberjar |
| `package.json` | node tooling for CI only: the MCP conformance suite (jsdom from M6) |

## Commands

```sh
clojure -M:lint          # clj-kondo over src, test, cli, build.clj
clojure -M:fmt check     # cljfmt; `clojure -M:fmt fix` rewrites
clojure -M:test          # test-runner
clojure -T:build uber    # target/cloisters-daemon.jar
clojure -M:run           # the daemon from source
scripts/conformance.sh   # MCP conformance at both revisions, after `npm ci` and the uberjar
scripts/cli-gate.sh      # the CLI's 100 ms gate and daemon subcommands, after the uberjar
```

CI runs lint, format check, tests, the uberjar, MCP conformance and the CLI gate on `ubuntu-latest` and
`macos-latest`. Run all of them before pushing.

## Clojure conventions

- Mainstream, idiomatic Clojure: learning the stack is part of the point.
- Operations live in `cloisters.issues`, `cloisters.pages`, `cloisters.projects`, `cloisters.events` and
  `cloisters.search`, and take the context map described in `cloisters.core`. Each validates its arguments with
  `contract/validate!`, refuses with `core/fail` (the contract's error code plus the facts the reader needs), writes
  through `core/write!`, and records at most one event through `core/record!`. The query grammar itself is
  `cloisters.query`, which knows nothing of the store.
- The core validates; adapters stay thin and duplicate nothing. `cloisters.http` and `cloisters.mcp` only turn
  requests into argument maps and results into responses; both render text through `cloisters.text` and JSON through
  the one mapper in `cloisters.json`. A tool's `outputSchema` is its entry in `contract/results`: change the result
  and the schema together, and the MCP test validates every tool's result against it.
- Conformance baselines list only what cloisters cannot pass by design (fixture tools, resources, prompts, tasks…).
  A newly passing entry fails CI as stale: delete it. The CLI shares no code with the daemon: it sends
  requests and prints the daemon's own text rendering.
- The CLI uses only what babashka bundles (`babashka.http-client`, cheshire, `babashka.cli`, `babashka.fs`,
  `babashka.process`). A library loaded from source would be interpreted on every start and break the 100 ms gate.
  The CLI can't load on the JVM, so `cli_test.clj` runs it as a process (`bb cli/cloisters.clj`) against a daemon
  served in the test JVM, with `VISUAL` set to a script for the editor flow. Never let a test install a service.
- Pin every version exactly (`deps.edn`, `mise.toml`, `package.json`). Check versions against Maven Central or
  Clojars metadata and release notes, not docs pages.
- Write malli schemas with `mu/merge`, not `:merge`. Write titles as a single `:re`, not
  `[:and :string [:re …]]`.
- Migrations are numbered `.sql` files in `resources/migrations/`, listed in `cloisters.store/migration-files`. They
  are append-only: never edit a merged one; add the next number.
- In HoneySQL raw fragments, bind parameters with `[:lift x]`; `RETURNING` allocates ids.
- Write `java.lang.ProcessHandle` and other newer `java.lang` classes fully qualified: Clojure 1.12 does not
  auto-import them.
- The CLI sends `Host: 127.0.0.1:<port>` (the JDK HTTP client refuses to set `Host` otherwise); the localhost
  hardening allows both `localhost` and `127.0.0.1`.
- The daemon and its service unit run with `--enable-native-access=ALL-UNNAMED`.

## Working rules

- Branch `build/mN-<name>` from `main` (never from another milestone branch) and merge by squash PR once CI is
  green: `gh pr merge <n> --squash --admin --delete-branch`. The ProtectMainCloisters ruleset requires a review that a
  solo author cannot give, so merges use the admin bypass, and only after CI passes on both platforms.
- A milestone is done when its checks pass, not when its code is written.
- Stage explicit paths. Never commit `target/`, `.cpcache/`, `node_modules/` or `.cloisters-dev/`.
- The walking skeleton (branch `prototype/walking-skeleton`, directory `skeleton/`) is reference only. Copy from it
  only after review, and only the vendored `htmx.min.js`, the jsdom shims in `scripts/ui-test.js`, `paths.clj`, and
  the lock and migration runner.
- Run scratch daemons under a temporary `CLOISTERS_HOME` on a spare port, never on 7227 (the dev daemon, holding
  cloisters' own tracking) or 7117 (tikka). Kill them by the pid in their lock, never by pattern. Never point a test
  or scratch command at `.cloisters-dev`.

## Shell quirks

- In Saqib's zsh, `bb` is an alias for `brew bundle`: call babashka through `mise exec -- bb` or by path.
- `noclobber` is on: overwrite with `>|`, append to a new file with `>>|`.
- `$VAR` does not word-split into separate arguments.
- `cat` is aliased to `bat`: use `command cat`.
- Outside mise's shell activation (as in an agent's shell), `JAVA_HOME` is the global JDK 17, which the CLI refuses
  for `daemon export`, `restore` and `install`: run them as `JAVA_HOME=$(mise where java) cloisters daemon …`.
- Babashka must be installed user-wide (`mise use -g babashka`), not only through this repo's `mise.toml`: the
  installed CLI's `#!/usr/bin/env bb` finds no version outside the repo. `bbin` copies the script, so reinstall after
  every CLI change.
- gh: use only the `saqib-trywe` account for this repository. If another account is active, run
  `gh auth switch -u saqib-trywe` and leave it there. Git pushes go over SSH.
