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

# A busy port would let this daemon exit while the checks below answer a foreign store.
if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
  echo "FAIL: port $port is already in use; pass a spare port: $0 [port]" >&2
  exit 1
fi

CLOISTERS_HOME="$home" java --enable-native-access=ALL-UNNAMED -jar target/cloisters-daemon.jar \
  > "$home/daemon.log" 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT

assert-our-daemon() {
  local lock="$home/data/daemon.lock"
  local lock_pid
  if [ ! -f "$lock" ]; then
    echo "FAIL: no daemon lock at $lock" >&2
    return 1
  fi
  lock_pid="$(sed -nE 's/^\{:pid[[:space:]]+([0-9]+)[,}].*/\1/p' "$lock")"
  if [ "$lock_pid" != "$pid" ]; then
    echo "FAIL: $lock names pid ${lock_pid:-none}, not the daemon we started (pid $pid)" >&2
    return 1
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "FAIL: the daemon we started (pid $pid) is no longer running" >&2
    return 1
  fi
}

ready=false
for _ in $(seq 1 60); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$port/api/meta"; then ready=true; break; fi
  if ! kill -0 "$pid" 2>/dev/null; then
    cat "$home/daemon.log"
    echo "FAIL: the daemon (pid $pid) exited before answering on port $port" >&2
    exit 1
  fi
  sleep 1
done
if [ "$ready" != true ]; then
  cat "$home/daemon.log"
  echo "FAIL: the daemon did not answer on port $port within 60 s" >&2
  exit 1
fi
assert-our-daemon || { cat "$home/daemon.log"; exit 1; }

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
