#!/usr/bin/env bash
set -euo pipefail

CONTEXT="${KUBE_CONTEXT:-docker-desktop}"
NAMESPACE="${RINGFORGE_NAMESPACE:-ringforge-demo}"

if ! kubectl config get-contexts "$CONTEXT" >/dev/null 2>&1; then
  echo "Kubernetes context '$CONTEXT' was not found. Nothing to clean up for Docker Desktop." >&2
  exit 0
fi

kubectl --context "$CONTEXT" delete namespace "$NAMESPACE" --ignore-not-found=true
echo "Deleted namespace ${NAMESPACE} from context ${CONTEXT}."
