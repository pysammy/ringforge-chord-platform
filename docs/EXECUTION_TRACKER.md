# RingForge Execution Tracker

This is the active build tracker for RingForge. Use this file before starting new work so the project stays focused on the original goal: a backend/infra-heavy distributed DHT service, not a technology collage.

## Current Summary

Status: local productized platform is working; cloud deployment artifacts are ready; external deployment execution is blocked until a cloud Kubernetes context or kubeconfig is provided.

Latest pushed commits:

- `3d3fbd5` - smoke test avoids occupied local port
- `00960dd` - Kubernetes rejoin and ownership handoff validation
- `c4f4eda` - service hardening and deployment automation

Validated locally:

- Java unit/integration tests: `30` passing
- Docker-backed Kubernetes deployment: passing on `kind-ringforge`
- Kubernetes smoke test: passing
- Redis primary/replica records: validated
- Kafka audit events: validated
- node failure, replica promotion, node restore, ownership handoff, and delete: validated
- cloud manifest rendering: implemented
- public smoke-test script: implemented
- GHCR image publishing workflow: implemented

## What Is Already Built

### Core DHT

Status: complete for demo/platform milestone.

- Chord-style identifier ring.
- Finger-table based lookup routing.
- Deterministic key ownership.
- Node join behavior.
- Node removal/failure repair behavior.
- Lookup path visibility for demos and debugging.

### Service Runtime

Status: complete for local/Kubernetes milestone.

- Independent Java service nodes.
- HTTP node-to-node communication.
- Gateway API for DHT operations.
- Browser console served by the gateway.
- Cluster membership view.
- Cluster snapshot and metrics endpoint.
- Ops report endpoint.

### Storage

Status: complete for current milestone.

- In-memory storage for simple local tests.
- Redis-backed primary storage.
- Redis-backed replica storage.
- Versioned internal records using `rfv1|...`.
- Public API still returns plain user values.

### Replication And Recovery

Status: complete for current milestone.

- Replication factor defaults to `3`.
- Primary write replication to successors.
- Read repair for missing/stale replicas.
- Distributed delete across primary and replicas.
- Replica promotion when primary owner fails.
- Transfer-first ownership handoff when a recovered node rejoins.
- Membership gossip during heartbeat so restored nodes converge back into the ring.

### Events And Observability

Status: complete for current milestone.

- Kafka-backed service events.
- Gateway audit endpoint: `GET /api/audit/events?limit=...`.
- Prometheus-style metrics endpoint: `GET /metrics`.
- Frontend panels for ring state, node metadata, Kafka audit, and failure demo.

### Local Deployment

Status: complete and validated.

- Docker Compose manifest.
- Kubernetes manifest for Redis, Kafka, three nodes, and gateway.
- Dedicated Docker-backed `kind-ringforge` cluster path.
- `scripts/deploy-docker-desktop-k8s.sh`.
- `scripts/smoke-test-k8s.sh`.
- `scripts/cleanup-k8s.sh`.

## What Is Remaining

### 1. External Deployment Target

Status: blocked on cloud access.

Decision needed: choose where this should run publicly and provide access to that Kubernetes cluster.

Recommended first real deployment target:

- Render/Fly.io/Railway for fastest public demo, or
- AWS/GCP/Azure Kubernetes for strongest infra signal.

For backend/infra/SDE positioning, the stronger path is managed Kubernetes:

- AWS EKS, or
- GCP GKE, or
- Azure AKS.

Acceptance criteria:

- Public gateway URL is reachable.
- Browser console loads from the public URL.
- `PUT`, `GET`, `DELETE`, `/metrics`, and `/api/audit/events` work remotely.
- Cluster survives one node deployment scale-down and still returns the test key.

### 2. Production-Ready Container Publishing

Status: implemented through GitHub Actions.

Required work:

- GitHub Actions publishes commit-SHA and `latest` images to GitHub Container Registry on `main`.
- Cloud manifest uses the published image and `imagePullPolicy: IfNotPresent`.
- Manual deploy workflow publishes the image before deploying.

Acceptance criteria:

- A clean machine/cluster can deploy without building the image locally.
- Kubernetes pulls the image from the registry successfully.

### 3. Cloud Kubernetes Manifests

Status: implemented for first public deployment.

Required work:

- `deploy/kubernetes/cloud/ringforge-cloud.yaml.template` contains the cloud-ready manifest.
- `scripts/render-cloud-manifest.sh` renders namespace, image, and gateway service type.
- `scripts/deploy-cloud-k8s.sh` applies the manifest and waits for rollout.
- The gateway is exposed as `LoadBalancer` by default.
- Resource requests, limits, readiness probes, and liveness probes are included.

Acceptance criteria:

- `kubectl apply` or Helm install deploys the full stack to the cloud cluster.
- Pods become healthy without local image loading.
- Gateway has a public endpoint.

### 4. Managed Redis/Kafka Option

Status: not started.

Current local deployment runs Redis and Kafka inside Kubernetes. That is fine for demo, but production-style deployment should support external managed services.

Required work:

- Add environment/config support for external Redis endpoints.
- Add environment/config support for external Kafka bootstrap servers.
- Add Kubernetes Secrets/ConfigMaps.
- Document local vs managed service mode.

Acceptance criteria:

- Same application image runs with in-cluster Redis/Kafka or managed Redis/Kafka.
- No secrets are committed to Git.

### 5. CI/CD Deployment Pipeline

Status: implemented, pending cloud secret.

Current CI:

- Maven test/package.
- Docker image build.

Remaining work:

- Add `KUBE_CONFIG_B64` repository secret for the target cloud cluster.
- Run the manual GitHub Actions deploy workflow with `deploy=true`.
- Provide `public_base_url` to run post-deploy public smoke testing from CI.

Acceptance criteria:

- Push to `main` builds and publishes an image.
- Deployment can be triggered safely.
- Smoke test result is visible in GitHub Actions.

### 6. Public Demo Polish

Status: complete for first public deployment.

Remaining work:

- Optional future polish: add one-click failure orchestration from a secured admin endpoint.

Acceptance criteria:

- A recruiter/interviewer can understand the backend architecture within two minutes.
- An engineer can verify the system behavior without reading the source first.

### 7. Hardening After First Public Deployment

Status: future work.

Important but not required before first deploy:

- Tombstones for delete during long replica outages.
- Stronger anti-entropy repair loop.
- Auth/API keys for public write endpoints.
- Rate limiting.
- Structured logs.
- OpenTelemetry tracing.
- Helm chart.
- Horizontal gateway replicas.
- More than three Chord nodes.
- Load/performance test report.

## Immediate Next Steps

1. Provide or create a cloud Kubernetes cluster.
2. Add the cluster kubeconfig as GitHub Actions secret `KUBE_CONFIG_B64`, or set local `KUBE_CONTEXT` to the cloud cluster.
3. Push to `main` and confirm GHCR image publishing succeeds.
4. Run `scripts/deploy-cloud-k8s.sh` locally or run the manual GitHub Actions deploy workflow.
5. Run `scripts/smoke-test-public.sh` against the public gateway URL.
6. Update README with the public demo URL.

## Current Local Commands

Deploy locally:

```bash
scripts/deploy-docker-desktop-k8s.sh
```

Smoke test locally:

```bash
scripts/smoke-test-k8s.sh
```

Open frontend locally:

```bash
kubectl --context kind-ringforge -n ringforge-demo port-forward service/ringforge-gateway 18083:8081
```

Then open:

```text
http://localhost:18083
```

Clean up local Kubernetes resources:

```bash
scripts/cleanup-k8s.sh
```

Delete the dedicated local cluster:

```bash
kind delete cluster --name ringforge
```

## Tracking Rule

Before adding new features, update this tracker with:

- why the work is needed,
- which phase it belongs to,
- how it will be tested,
- whether it helps the deployment goal.

Work that does not improve correctness, deployment, observability, or demo clarity should wait.
