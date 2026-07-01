#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.ringforge"
NODE_IDS="${NODE_IDS:-0,30,65,110}"
BASE_PORT="${BASE_PORT:-5100}"
HEARTBEAT_MS="${HEARTBEAT_MS:-750}"
BIT_LENGTH="${BIT_LENGTH:-8}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-3}"

mkdir -p "$RUN_DIR"
: > "$RUN_DIR/service-cluster.pids"

cd "$ROOT_DIR"
mvn -q -DskipTests package

IFS=',' read -r -a IDS <<< "$NODE_IDS"
BOOTSTRAP_URI="http://localhost:$BASE_PORT"

for index in "${!IDS[@]}"; do
  node_id="${IDS[$index]}"
  port=$((BASE_PORT + index))
  log_file="$RUN_DIR/node-$node_id.log"
  args=(
    -cp "$ROOT_DIR/target/classes"
    com.ringforge.chord.app.ServiceNodeMain
    --id "$node_id"
    --port "$port"
    --bit-length "$BIT_LENGTH"
    --replication-factor "$REPLICATION_FACTOR"
    --heartbeat-ms "$HEARTBEAT_MS"
  )
  if [[ "$index" -gt 0 ]]; then
    args+=(--join "$BOOTSTRAP_URI")
  fi

  nohup java "${args[@]}" > "$log_file" 2>&1 &
  pid=$!
  echo "$pid $node_id $port" >> "$RUN_DIR/service-cluster.pids"
  sleep 0.8
done

echo "RingForge service cluster started."
echo "PIDs: $RUN_DIR/service-cluster.pids"
echo "Bootstrap: $BOOTSTRAP_URI"
echo "State: curl $BOOTSTRAP_URI/node/state"
