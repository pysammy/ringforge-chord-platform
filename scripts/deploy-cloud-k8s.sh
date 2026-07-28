#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTEXT="${KUBE_CONTEXT:-}"
NAMESPACE="${RINGFORGE_NAMESPACE:-ringforge-demo}"
RENDERED="$(mktemp /tmp/ringforge-cloud.XXXXXX.yaml)"

cleanup() {
  rm -f "$RENDERED"
}
trap cleanup EXIT

if [[ -z "$CONTEXT" ]]; then
  echo "KUBE_CONTEXT is required for cloud deployment." >&2
  echo "Example: KUBE_CONTEXT=my-eks-cluster RINGFORGE_IMAGE=ghcr.io/pysammy/ringforge-chord-platform:<tag> scripts/deploy-cloud-k8s.sh" >&2
  exit 1
fi

if kubectl config get-contexts "$CONTEXT" --no-headers 2>/dev/null | grep -q 'kind-hamsaz'; then
  echo "Refusing to deploy to kind-hamsaz. Use a RingForge-specific cloud or demo context." >&2
  exit 1
fi

if ! kubectl --context "$CONTEXT" get nodes >/dev/null; then
  echo "Unable to reach Kubernetes context '$CONTEXT'." >&2
  exit 1
fi

"${ROOT_DIR}/scripts/render-cloud-manifest.sh" "$RENDERED" >/dev/null

echo "Deploying RingForge to context '$CONTEXT' namespace '$NAMESPACE'"
kubectl --context "$CONTEXT" apply -f "$RENDERED"

for deployment in \
  ringforge-kafka \
  ringforge-redis-0 \
  ringforge-redis-30 \
  ringforge-redis-65 \
  ringforge-node-0 \
  ringforge-node-30 \
  ringforge-node-65 \
  ringforge-gateway; do
  kubectl --context "$CONTEXT" -n "$NAMESPACE" rollout status "deployment/${deployment}" --timeout=240s
done

kubectl --context "$CONTEXT" -n "$NAMESPACE" get pods -o wide
kubectl --context "$CONTEXT" -n "$NAMESPACE" get service ringforge-gateway

echo "Waiting for gateway external address"
for _ in {1..60}; do
  host="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" get service ringforge-gateway -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
  ip="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" get service ringforge-gateway -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
  if [[ -n "$host" ]]; then
    echo "Gateway URL: http://${host}"
    exit 0
  fi
  if [[ -n "$ip" ]]; then
    echo "Gateway URL: http://${ip}"
    exit 0
  fi
  sleep 5
done

echo "Gateway LoadBalancer address is still pending."
echo "Check it with: kubectl --context ${CONTEXT} -n ${NAMESPACE} get service ringforge-gateway"
