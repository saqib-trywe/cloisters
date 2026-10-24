#!/usr/bin/env bash
# Prints the frontier: open, unclaimed tickets whose blockers are all closed.
set -euo pipefail
cd "$(dirname "$0")/tickets"

field() { sed -n "s/^$2: //p" "$1" | head -1; }

declare -A status
for f in T*.md; do status[$(field "$f" id)]=$(field "$f" status); done

for f in T*.md; do
  [[ $(field "$f" status) == open ]] || continue
  [[ $(field "$f" assignee) == null ]] || continue
  blockers=$(field "$f" blocked-by | tr -d '[],' )
  takeable=yes
  for b in $blockers; do
    [[ ${status[$b]:-open} == closed ]] || takeable=no
  done
  if [[ $takeable == yes ]]; then
    echo "$(field "$f" id)  $(field "$f" type)  $(field "$f" title)"
  fi
done
