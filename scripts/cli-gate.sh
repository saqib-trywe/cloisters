#!/usr/bin/env bash
# The CLI gate: `cloisters search` against a live daemon must take under 100 ms at the median (hyperfine), then the
# daemon subcommands are exercised against the uberjar: run, status, export, stop, restore, and the service unit a
# dry-run install would write. The daemon runs under a scratch CLOISTERS_HOME on a spare port (never 7227 or 7117) and
# is stopped by its own pid. Usage: scripts/cli-gate.sh [port]
set -euo pipefail
cd "$(dirname "$0")/.."
repo="$(pwd)"
cli="$repo/cli/cloisters.clj"
jar="$repo/target/cloisters-daemon.jar"

port="${1:-7292}"
home="$(mktemp -d)"
mkdir -p "$home/config" "$home/repo"
echo "{:port $port}" > "$home/config/config.edn"
export CLOISTERS_HOME="$home"
unset VISUAL EDITOR

start() {
  "$cli" daemon run --jar "$jar" >> "$home/daemon.log" 2>&1 &
  pid=$!
  for _ in $(seq 1 60); do
    if "$cli" daemon status > /dev/null 2>&1; then return 0; fi
    if ! kill -0 "$pid" 2>/dev/null; then cat "$home/daemon.log"; exit 1; fi
    sleep 1
  done
  cat "$home/daemon.log"
  exit 1
}
pid=""
trap 'if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi' EXIT

echo "=== daemon run, init and seed"
start
cd "$home/repo"
"$cli" init CLO --new "Cloisters"
for n in $(seq 1 30); do "$cli" new "Issue $n" --label "l$((n % 5))" > /dev/null; done
"$cli" page new "Glossary" --body-file - -m first <<< "Terms. See CLO-1." > /dev/null
"$cli" search ready | head -3

echo "=== the 100 ms gate: cloisters search, median"
hyperfine --warmup 5 --runs 50 --export-json "$home/search.json" "$cli search ready"
# hyperfine 2's export: results[0].summary.time_wall_clock.median, in seconds
bb -e "(let [m (-> (slurp \"$home/search.json\") (cheshire.core/parse-string true)
                  :results first :summary :time_wall_clock :median)]
         (println (str \"median \" (some-> m (* 1000) Math/round) \" ms\"))
         (when-not (and (number? m) (< m 0.1))
           (println \"FAIL: the median is not under 100 ms\")
           (System/exit 1)))"

echo "=== daemon status, export, stop, restore"
"$cli" daemon status
"$cli" daemon export "$home/export.jsonl" --jar "$jar"
head -1 "$home/export.jsonl" | grep -q '^{"type":"project","key":"CLO",'
"$cli" daemon stop
wait "$pid" 2>/dev/null || true
pid=""
if "$cli" search ready > /dev/null 2>&1; then echo "FAIL: the daemon still answers"; exit 1; fi
snapshot="$(ls "$home/data/backups" | grep '^daily-' | head -1)"
"$cli" daemon restore "$snapshot" --jar "$jar"
ls "$home/data/backups" | grep -q '^pre-restore-'
start
# the startup snapshot predates project CLO, so the restored store has none
test "$("$cli" project list)" = "(no projects)"
"$cli" daemon stop
wait "$pid" 2>/dev/null || true
pid=""

echo "=== daemon install --dry-run"
"$cli" daemon install --jar "$jar" --dry-run
echo "CLI gate passed"
