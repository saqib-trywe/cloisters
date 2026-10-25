---
id: T13
title: Walking skeleton: surfaces over one core, issues and pages
type: prototype
status: closed
assignee: saqib
blocked-by: [T03, T04, T06, T10]
---

## Question

Does the chosen Clojure stack hold together? Build a throwaway walking skeleton: one daemon serving HTTP, MCP and the
web UI over one core and store in the XDG locations, plus a separate thin CLI, round-tripping one issue and one page
(create, read, link, backlink) through every surface, and served to a real Claude Code MCP client.

Like tikka's [walking skeleton](../../docs/inherited/tickets/T11-walking-skeleton.md), record what it corrects in the
decisions it was built on.

## Inherited from [Place cloisters' files in the XDG base directories](T03-xdg-file-locations.md)

Run the skeleton under `CLOISTERS_HOME` (a temporary home, port 7228 or similar) so it never touches the real XDG
locations or the live tikka on 7117.

## Inherited from [Define page identity and linking](T04-page-identity-and-linking.md)

Round-trip a mention in each direction through the shared id space: a page mentioning an issue and an issue
mentioning a page, each showing the backlink, both at `/CLO-N`.

## Inherited from [Choose the persistence stack](T06-persistence-stack.md)

Exercise the persistence stack end to end: the hand-rolled `user_version` migration runner with its snapshot,
the `item` table allocating one number to an issue and the next to a page, HoneySQL over next.jdbc, the read pool
plus locked write connection, and a stored revision number.

## Inherited from [Choose the MCP implementation](T07-mcp-implementation.md)

Serve the hand-rolled MCP handler to a real Claude Code client at 2026-07-28 and run the conformance suite
(`0.2.0-alpha.12`) at both revisions against the skeleton.

## Inherited from [Choose the web UI approach](T08-web-ui-approach.md)

The UI is server-rendered hiccup with htmx: render one page through nextjournal/markdown with a mention of an
issue linked, show its backlink, and edit it through the version-guarded editor.

## Inherited from [Choose the CLI approach](T09-cli-approach.md)

The CLI is a babashka script installed with `bbin`, using bundled libraries only: round-trip an issue and a page
through it, printing the daemon's text rendering, and measure `cloisters search` with `hyperfine` against the 100 ms
gate.

## Inherited from [Define the shared contract and HTTP adapter](T10-shared-contract-and-http.md)

Ring on Jetty 12, reitit for routing only, jsonista, malli contract validated by the core. Prove the async SSE
handler resumes by `Last-Event-ID`, that `Accept: text/plain` returns MCP's text rendering, that malformed JSON gets
the `invalid_argument` body, and that `wrong_kind` lets the CLI's `show` find a page.

## Inherited from [Extend the MCP tool contract for pages](T11-mcp-tools-for-pages.md)

Call `create_page`, `get_page` and `update_page` from Claude Code over MCP, and see an issue's `get_issue`
list the page among its backlinks with kind `page`.

## Inherited from [Extend the query grammar and search to pages](T12-query-grammar-for-pages.md)

Run one `search` over MCP and `GET /api/search` that returns the skeleton's issue and page as mixed rows, and a
`text:` match in the shared FTS5 trigram table.

## Resolution

Built 2026-10-25 on the local branch `prototype/walking-skeleton` (directory `skeleton/`, commit `3a6b4c0`, never
merged). That branch is the primary source; its README says how to run it. Scope, agreed with saqib before building:
project `CLO` only; issues with title and body; pages with title, body and parent; mention detection and backlinks in
both directions; events with stored revisions; the `stale_version` guard on page edits; `wrong_kind`; search over
`kind:` and `text:` only; the resumable SSE stream; an issue view, page view and guarded page editor in the UI; CLI
`new`, `show`, `page new` and `search`; six MCP tools (`search`, `get_issue`, `create_issue`, `get_page`,
`create_page`, `update_page`). Left out: claims, blockers, labels, comments, archiving, diff, restore, the
`cloisters mcp` stdio proxy and service install. Babashka 1.13.227 and bbin 0.2.5 were installed through mise for it.

**Verdict: the stack holds.** One core (about 250 lines) sits under three thin adapters (HTTP about 150, MCP about
150, UI about 110), and nothing is duplicated between them. The CLI shares no code; it prints the daemon's own text
rendering. Nothing forced a re-decision. What the map got wrong were specifics, recorded below and amended into
their tickets.

### What each check showed

- **Store and migrations.** `CLOISTERS_HOME` held everything on port 7228, clear of the real XDG locations and tikka.
  A second daemon was refused, naming the lock holder's pid, port and version. The runner went v0→v1 with no snapshot,
  then took a v1→v2 `VACUUM INTO` snapshot of a non-empty store; the snapshot reopens at v1 with its rows. The `item`
  table issued `CLO-1` to an issue and `CLO-2` to a page. `sqlite3` reads WAL, `user_version`, events with stored
  revisions, and plain rows. A clean stop checkpointed the WAL, and `quick_check` reported `ok`.
- **Mentions in both directions.** An issue mentioning the not-yet-created `CLO-2` gained it as a mention when the
  page was created (a forward reference). The page's `[the ticket](/CLO-1)` gave the issue its backlink. Backticked
  `CLO-3` did not count. Both show on `/CLO-N`, over the API and over MCP, each entry carrying its kind.
- **MCP from a real client.** Claude Code 2.1.288, running headless, negotiated **2026-07-28**. It called `get_page`,
  `create_page` (a child), `update_page` with `expected_version`, `get_issue` (listing two backlinks of kind `page`)
  and `search` (mixed rows, `text:`). The events record `mcp:claude-code (2026-07-28)`. A stale `update_page` and a
  `get_issue` on a page came back as tool results (`stale_version`, `wrong_kind`) with no client-side schema error.
- **Conformance `0.2.0-alpha.12`.** Every applicable scenario passes at both revisions. At 2025-11-25:
  `server-initialize`, `ping`, `tools-list`, `dns-rebinding-protection`, `server-session-lifecycle`. At 2026-07-28:
  `server-stateless` (21 of 25 checks), `tools-list`, `dns-rebinding-protection`, `server-sse-multiple-streams` and
  `http-header-validation`, plus `caching`'s checks on `server/discover` and `tools/list`. Every other failure is
  "not testable" (the suite's own fixture tools, such as `test_missing_capability`), or a resources or prompts probe
  that correctly answers `-32601` because cloisters advertises neither capability.
- **HTTP.** Status 201/200/404/400/409 with contract bodies. `Accept: text/plain` returns MCP's text. Malformed JSON
  gets the `invalid_argument` body. A foreign `Origin` or a rebinding `Host` gets 403. The async SSE handler replays
  from `Last-Event-ID`, then streams live events. A write that changes nothing records no event, so the stream
  stays silent.
- **Web UI.** Driven through jsdom against the live daemon. The page renders through nextjournal/markdown with a
  mention linked and titled on hover, plus its children, mentions and backlinks. The renderer escapes `<script>`,
  drops a `javascript:` link and leaves code unlinked. Typing in the editor posts the htmx preview. An edit made
  underneath the editor (over the API) reaches the editor through SSE → `htmx.trigger` → the banner fragment
  ("Changed since you opened it: now r8"). A stale save answers 409, keeps the human's text, shows the current
  body and moves the form to the current version, so the hand-merged resave goes through.
- **CLI.** `bbin install` of `cli/cloisters.clj`, with bundled libraries only. It round-tripped an issue and a page:
  `page new --body-file -` reads stdin, and `show` on a page follows `wrong_kind`. Exit codes 1 (code first on
  stderr) and 2 work. **`cloisters search` against the live daemon: 32.9 ms ± 0.6 ms** (hyperfine, 100 runs) through
  mise's `bb` shim, **20.8 ms ± 0.5 ms** with babashka on `PATH` directly, and `curl` alone 7.2 ms. That clears the
  100 ms gate either way.

### Findings

| Finding | Consequence |
|---|---|
| **Babashka pinned only in the repo's `mise.toml` breaks the installed CLI everywhere else.** `#!/usr/bin/env bb` resolves mise's shim, which has no version outside this repo. The shim also costs about 12 ms per run. In saqib's zsh, `bb` is an alias for `brew bundle`, but that only shadows the prompt, never a shebang. | Babashka must be installed user-wide. Amended in [Choose the CLI approach](T09-cli-approach.md). |
| **bbin 0.2.5 copies a local script** into the bin directory. | Reinstall after every CLI change; `bbin install` is a release step, not a link. Amended in [Choose the CLI approach](T09-cli-approach.md). |
| **`java.net.http` refuses a `Host` header**, so the CLI sends `Host: 127.0.0.1:<port>`. | The hardening already accepts it; keep both `localhost` and `127.0.0.1` allowed. |
| **Jetty does not commit SSE headers until the first write**, so a quiet stream looks unopened until the first keep-alive. | The stream opens with a comment line. Amended in [Define the shared contract and HTTP adapter](T10-shared-contract-and-http.md). |
| **An uncaught exception returns Jetty's HTML 500 page.** | Every adapter wraps a catch-all that answers in the contract's shape (JSON or text, MCP `-32603`). Amended in [Define the shared contract and HTTP adapter](T10-shared-contract-and-http.md). |
| **Two snake_case edges:** jsonista's key functions for HTTP, and a separate walk for MCP's `structuredContent` (which also drops nulls). | Use one jsonista mapper for both edges, and drop nulls before encoding. Amended in [Define the shared contract and HTTP adapter](T10-shared-contract-and-http.md). |
| **2026-07-28 details beyond tikka's notes:** an unsupported version is `-32022` with `data: {supported, requested}`; header checks come before the version check; `Mcp-Method` is required on every modern request (missing is `-32020`, like a mismatch); the suite now has a `caching` scenario, satisfied by `ttlMs` and `cacheScope` on `server/discover` and `tools/list`. | Amended in [Choose the MCP implementation](T07-mcp-implementation.md). |
| **The suite's requirement sets include fixture-tool, resources, prompts and input-required scenarios** that cloisters cannot pass by design, so `--requirements` exits non-zero. | CI runs `--requirements` at both revisions against an `--expected-failures` baseline listing exactly those. Amended in [Choose the MCP implementation](T07-mcp-implementation.md). |
| **malli `:merge` needs `malli.util`'s registry**; `mu/merge` works directly. `{:closed true}` maps emit `additionalProperties: false`, which Claude Code honoured. `[:and :string [:re …]]` emits a redundant `allOf`. | Build conventions. Write titles as a single `:re`. |
| **HoneySQL raw fragments bind parameters with `[:lift x]`** inside `[:raw [...]]`, not with `?`. `RETURNING` works on SQLite for id allocation. | Build convention for the FTS5 `MATCH`. |
| **htmx 4 ships as `4.0.0` under npm's `next` tag** (`latest` is still 2.0.11). Under jsdom it needs `document.adoptedStyleSheets`, a default XPath result type and a cross-realm `fetch` bridge. | Vendor `4.0.0` deliberately. The UI test harness carries the three shims (in `skeleton/scripts/ui-test.js`). |
| **Clojure 1.12 does not auto-import newer `java.lang` classes** such as `ProcessHandle`. | Trivial; write `java.lang.ProcessHandle`. |
| **With `--enable-native-access=ALL-UNNAMED` on JDK 27 the daemon logged no JDK warnings** (not tried without the flag). | The inherited service-unit flag stands. |
