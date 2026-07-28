#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${RINGFORGE_BASE_URL:-}"
KEY="${RINGFORGE_TEST_KEY:-45}"
VALUE="${RINGFORGE_TEST_VALUE:-public-smoke-check}"

if [[ -z "$BASE_URL" ]]; then
  echo "RINGFORGE_BASE_URL is required." >&2
  echo "Example: RINGFORGE_BASE_URL=http://example.com scripts/smoke-test-public.sh" >&2
  exit 1
fi

BASE_URL="${BASE_URL%/}"

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
  for _ in {1..60}; do
    response="$(curl --max-time 8 -fsS "$url" 2>/dev/null || true)"
    if [[ "$response" == *"$expected"* ]]; then
      printf '%s' "$response"
      return 0
    fi
    sleep 3
  done
  echo "FAILED: $label" >&2
  echo "Expected to find: $expected" >&2
  echo "Actual: $response" >&2
  exit 1
}

echo "Testing public RingForge gateway at ${BASE_URL}"
health="$(wait_until_contains "${BASE_URL}/api/health" '"status":"ok"' "gateway health")"
require_contains "$health" '"memberCount":3' "health member count"

members="$(curl --max-time 8 -fsS "${BASE_URL}/api/cluster/members")"
require_contains "$members" '"nodeId":0' "node 0 membership"
require_contains "$members" '"nodeId":30' "node 30 membership"
require_contains "$members" '"nodeId":65' "node 65 membership"

curl --max-time 8 -fsS -X POST "${BASE_URL}/api/dht/put?key=${KEY}&value=${VALUE}" >/dev/null
lookup="$(curl --max-time 8 -fsS "${BASE_URL}/api/dht/get?key=${KEY}")"
require_contains "$lookup" '"found":true' "lookup found"
require_contains "$lookup" "\"value\":\"${VALUE}\"" "lookup value"
require_contains "$lookup" '"responsibleNodeId":65' "lookup owner"

metrics="$(curl --max-time 8 -fsS "${BASE_URL}/metrics")"
require_contains "$metrics" 'ringforge_service_members 3' "member metric"
require_contains "$metrics" 'ringforge_service_primary_keys 1' "primary metric"

audit="$(curl --max-time 8 -fsS "${BASE_URL}/api/audit/events?limit=20")"
require_contains "$audit" '"enabled":true' "Kafka audit enabled"
require_contains "$audit" '"KEY_STORED"' "Kafka key stored event"

delete_result="$(curl --max-time 8 -fsS -X POST "${BASE_URL}/api/dht/delete?key=${KEY}")"
require_contains "$delete_result" '"deleted":true' "delete result"
missing_lookup="$(curl --max-time 8 -fsS "${BASE_URL}/api/dht/get?key=${KEY}")"
require_contains "$missing_lookup" '"found":false' "missing after delete"

echo "Public smoke test passed."
