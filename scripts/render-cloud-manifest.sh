#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="${ROOT_DIR}/deploy/kubernetes/cloud/ringforge-cloud.yaml.template"
OUTPUT="${1:-${ROOT_DIR}/deploy/kubernetes/cloud/ringforge-cloud.rendered.yaml}"

export RINGFORGE_NAMESPACE="${RINGFORGE_NAMESPACE:-ringforge-demo}"
export RINGFORGE_IMAGE="${RINGFORGE_IMAGE:-ghcr.io/pysammy/ringforge-chord-platform:latest}"
export RINGFORGE_GATEWAY_SERVICE_TYPE="${RINGFORGE_GATEWAY_SERVICE_TYPE:-LoadBalancer}"

if ! command -v envsubst >/dev/null 2>&1; then
  echo "envsubst is required to render the cloud manifest." >&2
  exit 1
fi

envsubst < "$TEMPLATE" > "$OUTPUT"
echo "$OUTPUT"
