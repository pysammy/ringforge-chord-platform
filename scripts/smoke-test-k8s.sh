#!/usr/bin/env bash
set -euo pipefail

KIND_CLUSTER="${RINGFORGE_KIND_CLUSTER:-ringforge}"
CONTEXT="${KUBE_CONTEXT:-kind-${KIND_CLUSTER}}"
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

wait_until_contains() {
  local url="$1"
  local expected="$2"
  local label="$3"
  local response=""
  for _ in {1..40}; do
    response="$(curl --max-time 5 -fsS "$url" 2>/dev/null || true)"
    if [[ "$response" == *"$expected"* ]]; then
      printf '%s' "$response"
      return 0
    fi
    sleep 2
  done
  echo "FAILED: $label" >&2
  echo "Expected to find: $expected" >&2
  echo "Actual: $response" >&2
  exit 1
}

wait_until_not_contains() {
  local url="$1"
  local unexpected="$2"
  local label="$3"
  local response=""
  for _ in {1..40}; do
    response="$(curl --max-time 5 -fsS "$url" 2>/dev/null || true)"
    if [[ "$response" != *"$unexpected"* && -n "$response" ]]; then
      printf '%s' "$response"
      return 0
    fi
    sleep 2
  done
  echo "FAILED: $label" >&2
  echo "Expected not to find: $unexpected" >&2
  echo "Actual: $response" >&2
  exit 1
}

echo "Starting gateway port-forward on ${BASE_URL}"
if ! kubectl config get-contexts "$CONTEXT" >/dev/null 2>&1; then
  echo "Kubernetes context '$CONTEXT' was not found. Run scripts/deploy-docker-desktop-k8s.sh first." >&2
  exit 1
fi
if kubectl config get-contexts "$CONTEXT" --no-headers | grep -q 'kind-hamsaz'; then
  echo "Refusing to smoke test kind-hamsaz. Set KUBE_CONTEXT to a RingForge-specific cluster." >&2
  exit 1
fi
kubectl --context "$CONTEXT" -n "$NAMESPACE" port-forward service/ringforge-gateway "${PORT}:8081" >/tmp/ringforge-port-forward.log 2>&1 &
PORT_FORWARD_PID="$!"
wait_until_contains "${BASE_URL}/api/cluster/members" '"nodeId":0' "gateway port-forward readiness" >/dev/null

members="$(curl --max-time 5 -fsS "${BASE_URL}/api/cluster/members")"
require_contains "$members" '"nodeId":0' "node 0 membership"
require_contains "$members" '"nodeId":30' "node 30 membership"
require_contains "$members" '"nodeId":65' "node 65 membership"

curl --max-time 5 -fsS -X POST "${BASE_URL}/api/dht/put?key=${KEY}&value=${VALUE}" >/dev/null
lookup="$(curl --max-time 5 -fsS "${BASE_URL}/api/dht/get?key=${KEY}")"
require_contains "$lookup" '"found":true' "lookup found"
require_contains "$lookup" "\"value\":\"${VALUE}\"" "lookup value"
require_contains "$lookup" '"responsibleNodeId":65' "lookup owner"
require_contains "$lookup" '"path":[0,30,65]' "lookup path"

metrics="$(curl --max-time 5 -fsS "${BASE_URL}/metrics")"
require_contains "$metrics" 'ringforge_service_members 3' "member metric"
require_contains "$metrics" 'ringforge_service_primary_keys 1' "primary metric"
require_contains "$metrics" 'ringforge_service_replica_keys 2' "replica metric"

primary="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" exec deployment/ringforge-redis-65 -- redis-cli GET "ringforge:node:65:primary:key:${KEY}")"
replica0="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" exec deployment/ringforge-redis-0 -- redis-cli GET "ringforge:node:0:replica:key:${KEY}")"
replica30="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" exec deployment/ringforge-redis-30 -- redis-cli GET "ringforge:node:30:replica:key:${KEY}")"
require_contains "$primary" 'rfv1|' "Redis primary versioned record"
require_contains "$replica0" 'rfv1|' "Redis node 0 replica versioned record"
require_contains "$replica30" 'rfv1|' "Redis node 30 replica versioned record"

audit="$(curl --max-time 5 -fsS "${BASE_URL}/api/audit/events?limit=20")"
require_contains "$audit" '"KEY_STORED"' "Kafka key stored event"
require_contains "$audit" '"REPLICA_WRITTEN"' "Kafka replica event"

echo "Scaling node 65 down to test replica promotion"
kubectl --context "$CONTEXT" -n "$NAMESPACE" scale deployment/ringforge-node-65 --replicas=0 >/dev/null

failed_members="$(wait_until_not_contains "${BASE_URL}/api/cluster/members" '"nodeId":65' "node 65 removal after heartbeat repair")"
require_contains "$failed_members" '"nodeId":0' "post-failure node 0 membership"
require_contains "$failed_members" '"nodeId":30' "post-failure node 30 membership"

failover_lookup="$(wait_until_contains "${BASE_URL}/api/dht/get?key=${KEY}" '"responsibleNodeId":0' "promoted owner after node 65 failure")"
require_contains "$failover_lookup" '"found":true' "failover lookup found"
require_contains "$failover_lookup" "\"value\":\"${VALUE}\"" "failover lookup value"
require_contains "$failover_lookup" '"responsibleNodeId":0' "promoted owner"

echo "Restoring node 65"
kubectl --context "$CONTEXT" -n "$NAMESPACE" scale deployment/ringforge-node-65 --replicas=1 >/dev/null
kubectl --context "$CONTEXT" -n "$NAMESPACE" rollout status deployment/ringforge-node-65 --timeout=120s >/dev/null
wait_until_contains "${BASE_URL}/api/cluster/members" '"nodeId":65' "node 65 rejoin after restore" >/dev/null

restored_lookup="$(wait_until_contains "${BASE_URL}/api/dht/get?key=${KEY}" '"responsibleNodeId":65' "restored owner")"
require_contains "$restored_lookup" '"found":true' "restored lookup found"
require_contains "$restored_lookup" "\"value\":\"${VALUE}\"" "restored lookup value"
require_contains "$restored_lookup" '"responsibleNodeId":65' "restored owner"

echo "Testing distributed delete"
delete_result="$(curl --max-time 5 -fsS -X POST "${BASE_URL}/api/dht/delete?key=${KEY}")"
require_contains "$delete_result" '"deleted":true' "delete result"
missing_lookup="$(curl --max-time 5 -fsS "${BASE_URL}/api/dht/get?key=${KEY}")"
require_contains "$missing_lookup" '"found":false' "missing after delete"

echo "Smoke test passed."
