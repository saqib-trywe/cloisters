# cloisters

A Clojure rewrite of tikka (an issue tracker for humans and agents) plus a simple wiki. One daemon serves the HTTP
API, MCP and the web UI over one core and one SQLite store; a separate babashka CLI talks to its HTTP API.

- Vocabulary: [CONTEXT.md](CONTEXT.md). Decisions: `docs/adr/` and the closed tickets in `.wayfinder/tickets/`.
- The build follows [docs/build-plan.md](docs/build-plan.md): one milestone per branch, each implementing the tickets
  it names. Tickets are the spec; read them to the end, because *Amended by* notes override the text above them.
- `.wayfinder/` and `docs/inherited/` are history: never edit them.

## Layout

| Path | Is |
|---|---|
| `src/cloisters/` | the daemon: core, store, and the HTTP, MCP and UI adapters |
| `test/cloisters/` | tests, run by cognitect-labs test-runner |
| `cli/cloisters.clj` | the babashka CLI, one file |
| `build.clj` | tools.build: the daemon uberjar |
| `package.json` | node tooling for CI only: the MCP conformance suite (jsdom from M6) |

## Commands

```sh
clojure -M:lint          # clj-kondo over src, test, cli, build.clj
clojure -M:fmt check     # cljfmt; `clojure -M:fmt fix` rewrites
clojure -M:test          # test-runner
clojure -T:build uber    # target/cloisters-daemon.jar
clojure -M:run           # the daemon from source
```

CI runs lint, format check, tests, the uberjar and a CLI load check on `ubuntu-latest` and `macos-latest`. Run all
of them before pushing.

## Clojure conventions

- Mainstream, idiomatic Clojure: learning the stack is part of the point.
- The core validates; adapters stay thin and duplicate nothing. The CLI shares no code with the daemon: it sends
  requests and prints the daemon's own text rendering.
- The CLI uses only what babashka bundles (`babashka.http-client`, cheshire, `babashka.cli`, `babashka.fs`,
  `babashka.process`). A library loaded from source would be interpreted on every start and break the 100 ms gate.
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
  green. The repository has no ruleset, so no `--admin`.
- A milestone is done when its checks pass, not when its code is written.
- Stage explicit paths. Never commit `target/`, `.cpcache/`, `node_modules/` or `.cloisters-dev/`.
- The walking skeleton (branch `prototype/walking-skeleton`, directory `skeleton/`) is reference only. Copy from it
  only after review, and only the vendored `htmx.min.js`, the jsdom shims in `scripts/ui-test.js`, `paths.clj`, and
  the lock and migration runner.
- Run scratch daemons under `CLOISTERS_HOME` on a spare port, never on 7227 (a real install) or 7117 (tikka). Kill
  them by the pid in their lock, never by pattern.

## Shell quirks

- In Saqib's zsh, `bb` is an alias for `brew bundle`: call babashka through `mise exec -- bb` or by path.
- `noclobber` is on: overwrite with `>|`, append to a new file with `>>|`.
- `$VAR` does not word-split into separate arguments.
- `cat` is aliased to `bat`: use `command cat`.
- Babashka must be installed user-wide (`mise use -g babashka`), not only through this repo's `mise.toml`: the
  installed CLI's `#!/usr/bin/env bb` finds no version outside the repo. `bbin` copies the script, so reinstall after
  every CLI change.
- gh: the active account is `saqibsaleem-mands`; `gh auth switch -u saqib-trywe` for this repository and switch back
  after. Git pushes go over SSH.
