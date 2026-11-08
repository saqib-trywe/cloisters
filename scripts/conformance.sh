#!/usr/bin/env bash
# Runs the pinned MCP conformance suite against the daemon uberjar at both protocol revisions, each against its
# expected-failures baseline in conformance/. The daemon runs under a scratch CLOISTERS_HOME on a spare port (never
# 7227 or 7117) and is stopped by its own pid. Usage: scripts/conformance.sh [port]
set -euo pipefail
cd "$(dirname "$0")/.."

port="${1:-7291}"
home="$(mktemp -d)"
mkdir -p "$home/config"
echo "{:port $port}" > "$home/config/config.edn"

CLOISTERS_HOME="$home" java --enable-native-access=ALL-UNNAMED -jar target/cloisters-daemon.jar \
  > "$home/daemon.log" 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$port/api/meta"; then break; fi
  if ! kill -0 "$pid" 2>/dev/null; then cat "$home/daemon.log"; exit 1; fi
  sleep 1
done

status=0
for revision in 2025-11-25 2026-07-28; do
  echo "=== MCP conformance, requirements $revision"
  npx conformance server --url "http://127.0.0.1:$port/mcp" --requirements "$revision" \
    --expected-failures "conformance/baseline-$revision.yml" -o "$home/results-$revision" \
    > "$home/conformance-$revision.log" 2>&1 || status=1
  sed -n '/=== SUMMARY ===/,$p' "$home/conformance-$revision.log"
done

if [ "$status" -ne 0 ]; then
  echo "=== daemon log"
  cat "$home/daemon.log"
fi
exit "$status"
