#!/usr/bin/env bash
# The UI gate: the jsdom check (scripts/ui-test.js) drives the web UI, with the vendored htmx 4 and its shims, against
# the uberjar serving an empty store under a scratch CLOISTERS_HOME on a spare port (never 7227 or 7117). The daemon
# is stopped by its own pid. Usage: scripts/ui-gate.sh [port]
set -euo pipefail
cd "$(dirname "$0")/.."
jar="$(pwd)/target/cloisters-daemon.jar"

port="${1:-7293}"
home="$(mktemp -d)"
mkdir -p "$home/config"
echo "{:port $port}" > "$home/config/config.edn"

CLOISTERS_HOME="$home" java --enable-native-access=ALL-UNNAMED -jar "$jar" >> "$home/daemon.log" 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do
  if curl -fs "http://127.0.0.1:$port/api/meta" > /dev/null; then break; fi
  if ! kill -0 "$pid" 2>/dev/null; then cat "$home/daemon.log"; exit 1; fi
  sleep 1
done

node scripts/ui-test.js "http://127.0.0.1:$port"
