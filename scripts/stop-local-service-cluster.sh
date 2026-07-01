#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT_DIR/.ringforge/service-cluster.pids"

if [[ ! -f "$PID_FILE" ]]; then
  echo "No RingForge service cluster pid file found."
  exit 0
fi

while read -r pid node_id port; do
  if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    echo "Stopped node $node_id on port $port."
  fi
done < "$PID_FILE"

rm -f "$PID_FILE"
