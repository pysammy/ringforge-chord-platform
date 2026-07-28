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
curl -X POST 'http://localhost:18081/api/dht/delete?key=45'
curl http://localhost:18081/api/audit/events?limit=20
curl http://localhost:18081/metrics
curl http://localhost:18081/api/cluster/ops-report
```

Open the service console:

```text
http://localhost:18081
```

Verify Redis storage for key `45`.

Key `45` belongs to node `65` in the default three-node ring, so its primary copy should be in `redis-65`. Its replicas should be in successor replica namespaces on nodes `0` and `30`. Redis stores versioned internal records, so values start with `rfv1|`; the public gateway API still returns the plain user value.

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

## Kubernetes With Docker Desktop And kind

This is the recommended local path for development. It uses Docker Desktop as the container engine and creates or reuses a dedicated `kind-ringforge` Kubernetes cluster. It does not use unrelated clusters such as `kind-hamsaz`.

The script-first path is:

```bash
scripts/deploy-docker-desktop-k8s.sh
scripts/smoke-test-k8s.sh
```

The smoke test verifies membership, write/read routing, Redis primary and replica records, Kafka audit events, failover promotion, restore, and distributed delete.

Expected context after the deploy script creates the cluster:

```bash
kubectl config current-context
```

Expected:

```text
kind-ringforge
```

The remaining commands in this section show the manual flow. Use `kind-ringforge` as the context unless you intentionally set `KUBE_CONTEXT` to another RingForge-specific cluster.

```bash
docker build -t ringforge-chord-platform:redis-kafka-local .
```

Create a clean namespace:

```bash
kubectl --context kind-ringforge delete namespace ringforge-demo --ignore-not-found=true
kubectl --context kind-ringforge create namespace ringforge-demo --dry-run=client -o yaml \
  | kubectl --context kind-ringforge apply -f -
```

Load the local image into the kind cluster.

The manifest uses `imagePullPolicy: Never`, so the image must exist inside the Kubernetes node runtime.

```bash
kind load docker-image --name ringforge ringforge-chord-platform:redis-kafka-local
```

Deploy:

```bash
kubectl --context kind-ringforge -n ringforge-demo apply -f deploy/kubernetes/ringforge-demo.yaml
```

Wait for every deployment:

```bash
kubectl --context kind-ringforge -n ringforge-demo rollout status deployment/ringforge-kafka --timeout=180s
kubectl --context kind-ringforge -n ringforge-demo rollout status deployment/ringforge-redis-0 --timeout=90s
kubectl --context kind-ringforge -n ringforge-demo rollout status deployment/ringforge-redis-30 --timeout=90s
kubectl --context kind-ringforge -n ringforge-demo rollout status deployment/ringforge-redis-65 --timeout=90s
kubectl --context kind-ringforge -n ringforge-demo rollout status deployment/ringforge-node-0 --timeout=120s
kubectl --context kind-ringforge -n ringforge-demo rollout status deployment/ringforge-node-30 --timeout=120s
kubectl --context kind-ringforge -n ringforge-demo rollout status deployment/ringforge-node-65 --timeout=120s
kubectl --context kind-ringforge -n ringforge-demo rollout status deployment/ringforge-gateway --timeout=120s
```

Expose the gateway locally:

```bash
kubectl --context kind-ringforge -n ringforge-demo port-forward service/ringforge-gateway 18082:8081
```

Smoke test from another terminal:

```bash
curl http://localhost:18082/api/cluster/members
curl -X POST 'http://localhost:18082/api/dht/put?key=45&value=k8s-redis-kafka-check'
curl 'http://localhost:18082/api/dht/get?key=45'
curl http://localhost:18082/api/audit/events?limit=20
curl http://localhost:18082/metrics
curl http://localhost:18082/api/cluster/ops-report
```

Open the deployed service console:

```text
http://localhost:18082
```

Verify Redis primary and replica placement:

```bash
kubectl --context kind-ringforge -n ringforge-demo exec deployment/ringforge-redis-65 -- \
  redis-cli GET ringforge:node:65:primary:key:45
kubectl --context kind-ringforge -n ringforge-demo exec deployment/ringforge-redis-0 -- \
  redis-cli GET ringforge:node:0:replica:key:45
kubectl --context kind-ringforge -n ringforge-demo exec deployment/ringforge-redis-30 -- \
  redis-cli GET ringforge:node:30:replica:key:45
```

Verify Kafka captured service events:

```bash
kubectl --context kind-ringforge -n ringforge-demo exec deployment/ringforge-kafka -- \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server ringforge-kafka:9092 \
  --topic ringforge.events \
  --from-beginning \
  --timeout-ms 5000
```

Failure test:

```bash
kubectl --context kind-ringforge -n ringforge-demo scale deployment/ringforge-node-65 --replicas=0
sleep 4
curl http://localhost:18082/api/cluster/members
curl 'http://localhost:18082/api/dht/get?key=45'
curl http://localhost:18082/metrics
```

Expected result:

- membership removes node `65`
- key `45` remains readable from a promoted replica
- metrics still report reachable service nodes and key counts

Delete test:

```bash
curl -X POST 'http://localhost:18082/api/dht/delete?key=45'
curl 'http://localhost:18082/api/dht/get?key=45'
```

Expected result:

- delete reports `deleted:true`
- the follow-up lookup reports `found:false`

Restore:

```bash
kubectl --context kind-ringforge -n ringforge-demo scale deployment/ringforge-node-65 --replicas=1
kubectl --context kind-ringforge -n ringforge-demo rollout status deployment/ringforge-node-65 --timeout=90s
```

Cleanup:

```bash
scripts/cleanup-k8s.sh
```

## Cloud Kubernetes Deployment

This is the public deployment path. Unlike the local `kind-ringforge` deployment, this path assumes the application image is available in a registry and the gateway service can receive an external address from the Kubernetes provider.

Build and publish image through GitHub Actions:

```text
push to main -> CI -> publish ghcr.io/pysammy/ringforge-chord-platform:<commit-sha>
```

Render the cloud manifest locally:

```bash
RINGFORGE_IMAGE=ghcr.io/pysammy/ringforge-chord-platform:<commit-sha> \
RINGFORGE_NAMESPACE=ringforge-demo \
scripts/render-cloud-manifest.sh
```

Deploy to a cloud cluster:

```bash
KUBE_CONTEXT=<your-cloud-context> \
RINGFORGE_IMAGE=ghcr.io/pysammy/ringforge-chord-platform:<commit-sha> \
scripts/deploy-cloud-k8s.sh
```

The cloud manifest includes:

- `LoadBalancer` gateway service
- resource requests and limits
- node health probes
- gateway health probe at `/api/health`
- Redis, Kafka, three Chord service nodes, and the gateway
- registry image usage with `imagePullPolicy: IfNotPresent`

After the gateway receives a public address, run:

```bash
RINGFORGE_BASE_URL=http://<gateway-address> scripts/smoke-test-public.sh
```

The public smoke test verifies:

- gateway health
- three-node membership
- DHT write/read/delete
- key `45` ownership on node `65`
- Prometheus metrics
- Kafka audit events

### GitHub Actions Deployment

The workflow can also deploy manually from GitHub Actions.

Required repository secret:

```text
KUBE_CONFIG_B64
```

This must be a base64-encoded kubeconfig for the target cloud cluster.

Create it from a configured machine:

```bash
base64 -i ~/.kube/config | pbcopy
```

Then add it in GitHub:

```text
Repository -> Settings -> Secrets and variables -> Actions -> New repository secret
```

Manual deploy flow:

```text
Actions -> CI -> Run workflow -> deploy=true
```

If the gateway URL is already known, pass it as `public_base_url` to make the workflow run the public smoke test after deployment.
