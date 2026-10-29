#!/usr/bin/env bash
# Starts one storage node in the background.
# Usage: scripts/start-node.sh <node-number>    e.g. "scripts/start-node.sh 2" -> port 8082
set -euo pipefail
cd "$(dirname "$0")/.."

n=${1:?usage: scripts/start-node.sh <node-number>}
port=$((8080 + n))
mkdir -p logs

PORT=$port DATA_DIR=data/node$n nohup java -jar target/distributed-kv-store.jar node >> "logs/node$n.log" 2>&1 &
echo $! > "logs/node$n.pid"
echo "node$n started on port $port (pid $!)"
