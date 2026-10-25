#!/bin/bash
# Restart the skeleton daemon only (matched by its pid from the lock file, never by pattern).
cd /Users/saqib/Development/trywe/clojure/cloisters/skeleton
pid=$(sed -E 's/.*:pid ([0-9]+).*/\1/' .cloisters-skel/data/daemon.lock 2>/dev/null)
[ -n "$pid" ] && kill "$pid" 2>/dev/null && while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
CLOISTERS_HOME=.cloisters-skel nohup clojure -M:run > /tmp/cloisters-skel-daemon.log 2>&1 &
for i in $(seq 1 60); do curl -sf -H 'Host: localhost:7228' http://127.0.0.1:7228/api/meta >/dev/null && { echo up; exit 0; }; sleep 0.5; done
tail -30 /tmp/cloisters-skel-daemon.log; exit 1
