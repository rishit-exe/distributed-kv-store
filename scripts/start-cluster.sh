#!/usr/bin/env bash
# Starts 3 storage nodes (ports 8081-8083) and the router (port 8080) on this machine.
# Logs go to logs/, data to data/. Stop everything with scripts/stop-cluster.sh.
# Any router setting (e.g. REPLICATION_FACTOR) can be exported before running this.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f target/distributed-kv-store.jar ]]; then
  echo "Building jar..."
  ./mvnw -q -DskipTests package
fi

for n in 1 2 3; do
  scripts/start-node.sh "$n"
done

mkdir -p logs
PORT=8080 NODES=localhost:8081,localhost:8082,localhost:8083 \
  nohup java -jar target/distributed-kv-store.jar router >> logs/router.log 2>&1 &
echo $! > logs/router.pid
echo "router started on port 8080 (pid $!)"
