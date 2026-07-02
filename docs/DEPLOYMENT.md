# Deployment Guide

This guide covers the validated local deployment path for RingForge service nodes, Redis, Kafka, and the DHT gateway.

## Prerequisites

Required:

```bash
docker --version
docker compose version
kubectl version --client=true
```

On macOS with Colima:

```bash
colima start
docker info
```

Docker must be running before Docker Compose or Kubernetes can start containers.

## Docker Compose

Build and start Kafka, Redis, the service cluster, and the gateway:

```bash
NODE0_PORT=15100 \
NODE30_PORT=15101 \
NODE65_PORT=15102 \
GATEWAY_PORT=18081 \
docker compose -f deploy/docker-compose.yml up --build -d
```

Smoke test:

```bash
curl http://localhost:18081/api/cluster/members
curl -X POST 'http://localhost:18081/api/dht/put?key=45&value=compose-check'
curl 'http://localhost:18081/api/dht/get?key=45'
curl http://localhost:18081/metrics
curl http://localhost:18081/api/cluster/ops-report
```

Open the service console:

```text
http://localhost:18081
```

Verify Redis storage for key `45`.

Key `45` belongs to node `65` in the default three-node ring, so its primary copy should be in `redis-65`. Its replicas should be in successor replica namespaces on nodes `0` and `30`.

```bash
docker compose -f deploy/docker-compose.yml exec redis-65 redis-cli GET ringforge:node:65:primary:key:45
docker compose -f deploy/docker-compose.yml exec redis-0 redis-cli GET ringforge:node:0:replica:key:45
docker compose -f deploy/docker-compose.yml exec redis-30 redis-cli GET ringforge:node:30:replica:key:45
```

Verify Kafka service events:

```bash
docker compose -f deploy/docker-compose.yml exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic ringforge.events \
  --from-beginning \
  --timeout-ms 5000
```

Failure test:

```bash
docker stop deploy-node-65-1
sleep 3
curl http://localhost:18081/api/cluster/members
curl 'http://localhost:18081/api/dht/get?key=45'
```

Expected result:

- membership drops from `0,30,65` to `0,30`
- key `45` remains readable from a promoted replica

Stop:

```bash
NODE0_PORT=15100 \
NODE30_PORT=15101 \
NODE65_PORT=15102 \
GATEWAY_PORT=18081 \
docker compose -f deploy/docker-compose.yml down
```

## Kubernetes With kind

Use this section only if you want kind instead of Docker Desktop Kubernetes.

Build the local image:

```bash
docker build -t ringforge-chord-platform:redis-kafka-local .
```

Create a kind cluster if you do not already have one:

```bash
kind create cluster --name ringforge
```

If you already have a kind cluster, use its name instead. Load the local image:

```bash
kind load docker-image --name ringforge ringforge-chord-platform:redis-kafka-local
```

Create an isolated namespace and deploy:

```bash
kubectl create namespace ringforge-demo --dry-run=client -o yaml | kubectl apply -f -
kubectl -n ringforge-demo apply -f deploy/kubernetes/ringforge-demo.yaml
```

Wait for rollout:

```bash
kubectl -n ringforge-demo rollout status deployment/ringforge-node-0
kubectl -n ringforge-demo rollout status deployment/ringforge-node-30
kubectl -n ringforge-demo rollout status deployment/ringforge-node-65
kubectl -n ringforge-demo rollout status deployment/ringforge-gateway
kubectl -n ringforge-demo get pods
```

Port-forward the gateway:

```bash
kubectl -n ringforge-demo port-forward service/ringforge-gateway 18082:8081
```

Smoke test:

```bash
curl http://localhost:18082/api/cluster/members
curl -X POST 'http://localhost:18082/api/dht/put?key=45&value=k8s-check'
curl 'http://localhost:18082/api/dht/get?key=45'
curl http://localhost:18082/metrics
curl http://localhost:18082/api/cluster/ops-report
```

Failure test:

```bash
kubectl -n ringforge-demo scale deployment/ringforge-node-65 --replicas=0
sleep 4
curl http://localhost:18082/api/cluster/members
curl 'http://localhost:18082/api/dht/get?key=45'
```

Expected result:

- membership drops from `0,30,65` to `0,30`
- key `45` remains readable from a promoted replica

Restore:

```bash
kubectl -n ringforge-demo scale deployment/ringforge-node-65 --replicas=1
kubectl -n ringforge-demo rollout status deployment/ringforge-node-65
```

Cleanup:

```bash
kubectl delete namespace ringforge-demo
kind delete cluster --name ringforge
```

If using an existing cluster, do not delete the cluster unless it was created only for RingForge.

## Kubernetes With Docker Desktop

This is the recommended local path when Docker Desktop Kubernetes is enabled.

Make sure the active Kubernetes context is Docker Desktop:

```bash
kubectl config current-context
```

Expected:

```text
docker-desktop
```

Build the local application image from the current code:

```bash
docker build -t ringforge-chord-platform:redis-kafka-local .
```

Create a clean namespace:

```bash
kubectl --context docker-desktop delete namespace ringforge-demo --ignore-not-found=true
kubectl --context docker-desktop create namespace ringforge-demo --dry-run=client -o yaml \
  | kubectl --context docker-desktop apply -f -
```

Import the local image into the Docker Desktop Kubernetes node.

Docker Desktop Kubernetes may not automatically see every image tag from the Docker image list. The manifest uses `imagePullPolicy: Never`, so the image must exist inside the Kubernetes node runtime.

```bash
docker save ringforge-chord-platform:redis-kafka-local \
  -o /tmp/ringforge-chord-platform-redis-kafka-local.tar

K8S_NODE=$(kubectl --context docker-desktop get nodes -o jsonpath='{.items[0].metadata.name}')

kubectl --context docker-desktop debug node/${K8S_NODE} \
  -n ringforge-demo \
  --image=busybox \
  -- sleep 300

LOADER_POD=$(kubectl --context docker-desktop -n ringforge-demo get pods \
  --sort-by=.metadata.creationTimestamp \
  --no-headers \
  | awk '/node-debugger/ { pod=$1 } END { print pod }')

kubectl --context docker-desktop -n ringforge-demo wait \
  --for=condition=Ready pod/${LOADER_POD} \
  --timeout=45s

kubectl --context docker-desktop -n ringforge-demo cp \
  /tmp/ringforge-chord-platform-redis-kafka-local.tar \
  ${LOADER_POD}:/host/tmp/ringforge-chord-platform-redis-kafka-local.tar

kubectl --context docker-desktop -n ringforge-demo exec ${LOADER_POD} -- \
  chroot /host ctr -n k8s.io images import \
  /tmp/ringforge-chord-platform-redis-kafka-local.tar

kubectl --context docker-desktop -n ringforge-demo exec ${LOADER_POD} -- \
  chroot /host crictl images | grep ringforge-chord-platform

kubectl --context docker-desktop -n ringforge-demo exec ${LOADER_POD} -- \
  chroot /host rm -f /tmp/ringforge-chord-platform-redis-kafka-local.tar

kubectl --context docker-desktop -n ringforge-demo delete pod/${LOADER_POD}
rm -f /tmp/ringforge-chord-platform-redis-kafka-local.tar
```

Deploy:

```bash
kubectl --context docker-desktop -n ringforge-demo apply -f deploy/kubernetes/ringforge-demo.yaml
```

Wait for every deployment:

```bash
kubectl --context docker-desktop -n ringforge-demo rollout status deployment/ringforge-kafka --timeout=180s
kubectl --context docker-desktop -n ringforge-demo rollout status deployment/ringforge-redis-0 --timeout=90s
kubectl --context docker-desktop -n ringforge-demo rollout status deployment/ringforge-redis-30 --timeout=90s
kubectl --context docker-desktop -n ringforge-demo rollout status deployment/ringforge-redis-65 --timeout=90s
kubectl --context docker-desktop -n ringforge-demo rollout status deployment/ringforge-node-0 --timeout=120s
kubectl --context docker-desktop -n ringforge-demo rollout status deployment/ringforge-node-30 --timeout=120s
kubectl --context docker-desktop -n ringforge-demo rollout status deployment/ringforge-node-65 --timeout=120s
kubectl --context docker-desktop -n ringforge-demo rollout status deployment/ringforge-gateway --timeout=120s
```

Expose the gateway locally:

```bash
kubectl --context docker-desktop -n ringforge-demo port-forward service/ringforge-gateway 18082:8081
```

Smoke test from another terminal:

```bash
curl http://localhost:18082/api/cluster/members
curl -X POST 'http://localhost:18082/api/dht/put?key=45&value=k8s-redis-kafka-check'
curl 'http://localhost:18082/api/dht/get?key=45'
curl http://localhost:18082/metrics
curl http://localhost:18082/api/cluster/ops-report
```

Open the deployed service console:

```text
http://localhost:18082
```

Verify Redis primary and replica placement:

```bash
kubectl --context docker-desktop -n ringforge-demo exec deployment/ringforge-redis-65 -- \
  redis-cli GET ringforge:node:65:primary:key:45
kubectl --context docker-desktop -n ringforge-demo exec deployment/ringforge-redis-0 -- \
  redis-cli GET ringforge:node:0:replica:key:45
kubectl --context docker-desktop -n ringforge-demo exec deployment/ringforge-redis-30 -- \
  redis-cli GET ringforge:node:30:replica:key:45
```

Verify Kafka captured service events:

```bash
kubectl --context docker-desktop -n ringforge-demo exec deployment/ringforge-kafka -- \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server ringforge-kafka:9092 \
  --topic ringforge.events \
  --from-beginning \
  --timeout-ms 5000
```

Failure test:

```bash
kubectl --context docker-desktop -n ringforge-demo scale deployment/ringforge-node-65 --replicas=0
sleep 4
curl http://localhost:18082/api/cluster/members
curl 'http://localhost:18082/api/dht/get?key=45'
curl http://localhost:18082/metrics
```

Expected result:

- membership removes node `65`
- key `45` remains readable from a promoted replica
- metrics still report reachable service nodes and key counts

Restore:

```bash
kubectl --context docker-desktop -n ringforge-demo scale deployment/ringforge-node-65 --replicas=1
kubectl --context docker-desktop -n ringforge-demo rollout status deployment/ringforge-node-65 --timeout=90s
```

Cleanup:

```bash
kubectl --context docker-desktop delete namespace ringforge-demo
```
