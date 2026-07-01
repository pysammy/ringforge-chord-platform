# Deployment Guide

This guide covers the validated local deployment path for RingForge service nodes and the DHT gateway.

## Prerequisites

Required:

```bash
docker --version
docker compose version
kubectl version --client=true
kind version
```

On macOS with Colima:

```bash
colima start
docker info
```

Docker must be running before Docker Compose or kind can start containers.

## Docker Compose

Build and start the service cluster plus gateway:

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

Build the local image:

```bash
docker build -t ringforge-chord-platform:local .
```

Create a kind cluster if you do not already have one:

```bash
kind create cluster --name ringforge
```

If you already have a kind cluster, use its name instead. Load the local image:

```bash
kind load docker-image --name ringforge ringforge-chord-platform:local
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
