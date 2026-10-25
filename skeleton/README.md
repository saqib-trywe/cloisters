# PROTOTYPE: cloisters walking skeleton

Throwaway code that answered the "Walking skeleton: surfaces over one core, issues and pages" ticket. It lives only on
the `prototype/walking-skeleton` branch and is never merged. The verdict and findings are in the ticket's resolution.

One daemon (Ring on Jetty 12, reitit, jsonista, malli, HoneySQL over next.jdbc and SQLite, hiccup and htmx) serves the
HTTP API, hand-rolled MCP and the web UI over one core. A separate babashka CLI talks to its HTTP API.

## Run it

```sh
cd skeleton
mkdir -p .cloisters-skel/config && echo '{:port 7228}' > .cloisters-skel/config/config.edn
CLOISTERS_HOME=.cloisters-skel clojure -M:run          # daemon on 127.0.0.1:7228
BABASHKA_BBIN_BIN_DIR=/tmp/bin bbin install ./cli/cloisters.clj --as cloisters
CLOISTERS_HOME=$PWD/.cloisters-skel /tmp/bin/cloisters search text:glos
```

`CLOISTERS_SKEL_MIGRATIONS=1` stops the migration runner at v1, so a later run migrates a non-empty store.

| File | Is |
|---|---|
| `src/cloisters/core.clj` | the one core: issues, pages, mentions, revisions, search |
| `src/cloisters/contract.clj` | malli schemas per operation, JSON Schema for MCP |
| `src/cloisters/store.clj` | read pool, locked writer, daemon lock, `user_version` migrations |
| `src/cloisters/markdown.clj` | nextjournal/markdown tree to mentions and safe hiccup |
| `src/cloisters/{http,mcp,ui}.clj` | the three thin adapters |
| `src/cloisters/text.clj` | compact text rendering (MCP text block, `Accept: text/plain`) |
| `cli/cloisters.clj` | babashka CLI: `new`, `show`, `page new`, `search` |
| `scripts/` | the smoke helpers and the jsdom htmx test used to verify it |
