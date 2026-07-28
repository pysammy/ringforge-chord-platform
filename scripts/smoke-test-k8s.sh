#!/usr/bin/env bash
set -euo pipefail

CONTEXT="${KUBE_CONTEXT:-docker-desktop}"
NAMESPACE="${RINGFORGE_NAMESPACE:-ringforge-demo}"
PORT="${RINGFORGE_GATEWAY_PORT:-18082}"
BASE_URL="http://localhost:${PORT}"
KEY="${RINGFORGE_TEST_KEY:-45}"
VALUE="${RINGFORGE_TEST_VALUE:-smoke-redis-kafka-check}"

cleanup() {
  if [[ -n "${PORT_FORWARD_PID:-}" ]]; then
    kill "$PORT_FORWARD_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

require_contains() {
  local text="$1"
  local expected="$2"
  local label="$3"
  if [[ "$text" != *"$expected"* ]]; then
    echo "FAILED: $label" >&2
    echo "Expected to find: $expected" >&2
    echo "Actual: $text" >&2
    exit 1
  fi
}

echo "Starting gateway port-forward on ${BASE_URL}"
if ! kubectl config get-contexts "$CONTEXT" >/dev/null 2>&1; then
  echo "Kubernetes context '$CONTEXT' was not found. Start Docker Desktop and enable Kubernetes, then rerun this script." >&2
  exit 1
fi
kubectl --context "$CONTEXT" -n "$NAMESPACE" port-forward service/ringforge-gateway "${PORT}:8081" >/tmp/ringforge-port-forward.log 2>&1 &
PORT_FORWARD_PID="$!"
sleep 3

members="$(curl -fsS "${BASE_URL}/api/cluster/members")"
require_contains "$members" '"nodeId":0' "node 0 membership"
require_contains "$members" '"nodeId":30' "node 30 membership"
require_contains "$members" '"nodeId":65' "node 65 membership"

curl -fsS -X POST "${BASE_URL}/api/dht/put?key=${KEY}&value=${VALUE}" >/dev/null
lookup="$(curl -fsS "${BASE_URL}/api/dht/get?key=${KEY}")"
require_contains "$lookup" '"found":true' "lookup found"
require_contains "$lookup" "\"value\":\"${VALUE}\"" "lookup value"
require_contains "$lookup" '"responsibleNodeId":65' "lookup owner"
require_contains "$lookup" '"path":[0,30,65]' "lookup path"

metrics="$(curl -fsS "${BASE_URL}/metrics")"
require_contains "$metrics" 'ringforge_service_members 3' "member metric"
require_contains "$metrics" 'ringforge_service_primary_keys 1' "primary metric"
require_contains "$metrics" 'ringforge_service_replica_keys 2' "replica metric"

primary="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" exec deployment/ringforge-redis-65 -- redis-cli GET "ringforge:node:65:primary:key:${KEY}")"
replica0="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" exec deployment/ringforge-redis-0 -- redis-cli GET "ringforge:node:0:replica:key:${KEY}")"
replica30="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" exec deployment/ringforge-redis-30 -- redis-cli GET "ringforge:node:30:replica:key:${KEY}")"
require_contains "$primary" 'rfv1|' "Redis primary versioned record"
require_contains "$replica0" 'rfv1|' "Redis node 0 replica versioned record"
require_contains "$replica30" 'rfv1|' "Redis node 30 replica versioned record"

audit="$(curl -fsS "${BASE_URL}/api/audit/events?limit=20")"
require_contains "$audit" '"KEY_STORED"' "Kafka key stored event"
require_contains "$audit" '"REPLICA_WRITTEN"' "Kafka replica event"

echo "Scaling node 65 down to test replica promotion"
kubectl --context "$CONTEXT" -n "$NAMESPACE" scale deployment/ringforge-node-65 --replicas=0 >/dev/null
sleep 6

failed_members="$(curl -fsS "${BASE_URL}/api/cluster/members")"
require_contains "$failed_members" '"nodeId":0' "post-failure node 0 membership"
require_contains "$failed_members" '"nodeId":30' "post-failure node 30 membership"
if [[ "$failed_members" == *'"nodeId":65'* ]]; then
  echo "FAILED: node 65 should have been removed after heartbeat repair" >&2
  echo "$failed_members" >&2
  exit 1
fi

failover_lookup="$(curl -fsS "${BASE_URL}/api/dht/get?key=${KEY}")"
require_contains "$failover_lookup" '"found":true' "failover lookup found"
require_contains "$failover_lookup" "\"value\":\"${VALUE}\"" "failover lookup value"
require_contains "$failover_lookup" '"responsibleNodeId":0' "promoted owner"

echo "Restoring node 65"
kubectl --context "$CONTEXT" -n "$NAMESPACE" scale deployment/ringforge-node-65 --replicas=1 >/dev/null
kubectl --context "$CONTEXT" -n "$NAMESPACE" rollout status deployment/ringforge-node-65 --timeout=120s >/dev/null
sleep 6

restored_lookup="$(curl -fsS "${BASE_URL}/api/dht/get?key=${KEY}")"
require_contains "$restored_lookup" '"found":true' "restored lookup found"
require_contains "$restored_lookup" "\"value\":\"${VALUE}\"" "restored lookup value"
require_contains "$restored_lookup" '"responsibleNodeId":65' "restored owner"

echo "Testing distributed delete"
delete_result="$(curl -fsS -X POST "${BASE_URL}/api/dht/delete?key=${KEY}")"
require_contains "$delete_result" '"deleted":true' "delete result"
missing_lookup="$(curl -fsS "${BASE_URL}/api/dht/get?key=${KEY}")"
require_contains "$missing_lookup" '"found":false' "missing after delete"

echo "Smoke test passed."
