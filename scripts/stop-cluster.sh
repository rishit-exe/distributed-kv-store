#!/usr/bin/env bash
# Stops every process started by start-cluster.sh / start-node.sh.
set -uo pipefail
cd "$(dirname "$0")/.."

for pidfile in logs/*.pid; do
  [[ -e "$pidfile" ]] || continue
  name=$(basename "$pidfile" .pid)
  if kill "$(cat "$pidfile")" 2>/dev/null; then
    echo "stopped $name"
  fi
  rm -f "$pidfile"
done
