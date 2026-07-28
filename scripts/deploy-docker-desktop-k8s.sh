#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTEXT="${KUBE_CONTEXT:-docker-desktop}"
NAMESPACE="${RINGFORGE_NAMESPACE:-ringforge-demo}"
IMAGE="${RINGFORGE_IMAGE:-ringforge-chord-platform:redis-kafka-local}"
IMAGE_TAR="/tmp/ringforge-chord-platform-redis-kafka-local.tar"

cd "$ROOT_DIR"

echo "Using Kubernetes context: $CONTEXT"
if ! kubectl config get-contexts "$CONTEXT" >/dev/null 2>&1; then
  echo "Kubernetes context '$CONTEXT' was not found. Start Docker Desktop and enable Kubernetes, then rerun this script." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker is not reachable. Start Docker Desktop, wait until it is running, then rerun this script." >&2
  exit 1
fi
kubectl --context "$CONTEXT" get nodes >/dev/null

echo "Building application image: $IMAGE"
docker build -t "$IMAGE" .

echo "Preparing namespace: $NAMESPACE"
kubectl --context "$CONTEXT" delete namespace "$NAMESPACE" --ignore-not-found=true
kubectl --context "$CONTEXT" create namespace "$NAMESPACE" --dry-run=client -o yaml \
  | kubectl --context "$CONTEXT" apply -f -

echo "Importing local image into Docker Desktop Kubernetes containerd"
docker save "$IMAGE" -o "$IMAGE_TAR"
K8S_NODE="$(kubectl --context "$CONTEXT" get nodes -o jsonpath='{.items[0].metadata.name}')"
kubectl --context "$CONTEXT" -n "$NAMESPACE" delete pod -l app.kubernetes.io/managed-by=kubectl-debug --ignore-not-found=true
kubectl --context "$CONTEXT" debug "node/${K8S_NODE}" -n "$NAMESPACE" --image=busybox -- sleep 300 >/dev/null
LOADER_POD="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" get pods --sort-by=.metadata.creationTimestamp --no-headers \
  | awk '/node-debugger/ { pod=$1 } END { print pod }')"

if [[ -z "$LOADER_POD" ]]; then
  echo "Unable to find Docker Desktop node debug pod" >&2
  exit 1
fi

kubectl --context "$CONTEXT" -n "$NAMESPACE" wait --for=condition=Ready "pod/${LOADER_POD}" --timeout=45s
kubectl --context "$CONTEXT" -n "$NAMESPACE" cp "$IMAGE_TAR" "${LOADER_POD}:/host${IMAGE_TAR}"
kubectl --context "$CONTEXT" -n "$NAMESPACE" exec "$LOADER_POD" -- \
  chroot /host ctr -n k8s.io images import "$IMAGE_TAR"
kubectl --context "$CONTEXT" -n "$NAMESPACE" exec "$LOADER_POD" -- \
  chroot /host rm -f "$IMAGE_TAR"
kubectl --context "$CONTEXT" -n "$NAMESPACE" delete "pod/${LOADER_POD}" --ignore-not-found=true
rm -f "$IMAGE_TAR"

echo "Applying Kubernetes manifests"
kubectl --context "$CONTEXT" -n "$NAMESPACE" apply -f deploy/kubernetes/ringforge-demo.yaml

for deployment in \
  ringforge-kafka \
  ringforge-redis-0 \
  ringforge-redis-30 \
  ringforge-redis-65 \
  ringforge-node-0 \
  ringforge-node-30 \
  ringforge-node-65 \
  ringforge-gateway; do
  kubectl --context "$CONTEXT" -n "$NAMESPACE" rollout status "deployment/${deployment}" --timeout=180s
done

kubectl --context "$CONTEXT" -n "$NAMESPACE" get pods -o wide
echo "Deployment complete. Run scripts/smoke-test-k8s.sh to validate behavior."
