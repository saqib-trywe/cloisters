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

# A busy port would let this daemon exit while the check below answers a foreign store.
if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
  echo "FAIL: port $port is already in use; pass a spare port: $0 [port]" >&2
  exit 1
fi

CLOISTERS_HOME="$home" java --enable-native-access=ALL-UNNAMED -jar "$jar" >> "$home/daemon.log" 2>&1 &
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
  if curl -fs "http://127.0.0.1:$port/api/meta" > /dev/null; then ready=true; break; fi
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

node scripts/ui-test.js "http://127.0.0.1:$port"
