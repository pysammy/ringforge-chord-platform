# Integration Roadmap

RingForge should use infrastructure technologies only where they solve a real system problem.

## Redis

Problem solved:

- durable or externalized per-node key-value storage

Implemented seam:

- `KeyValueStore`
- `InMemoryKeyValueStore`
- `HttpKeyValueStore`
- `NodeStorageServer`
- `RedisKeyValueStore`

Chord remains responsible for ownership and routing. Redis stores the local node's primary and replica data under separate namespaces:

```text
ringforge:node:<id>:primary:keys
ringforge:node:<id>:primary:key:<key>
ringforge:node:<id>:replica:keys
ringforge:node:<id>:replica:key:<key>
```

## Kafka

Problem solved:

- durable, replayable cluster event history
- incident reconstruction
- benchmark comparison
- LLM-ready operational context

Implemented seam:

- `EventLog`
- `RingEvent`
- `EventType`
- `ServiceEventPublisher`
- `KafkaServiceEventPublisher`
- `NoopServiceEventPublisher`

The in-memory event log should remain useful for tests. Kafka should be an additional publisher, not a replacement for deterministic correctness.

The service runtime publishes Kafka events for joins, primary writes, lookups, replica writes, repairs, and replica promotions. Event publishing is intentionally best-effort: a Kafka outage must not break key placement or lookup correctness.

## Kubernetes

Problem solved:

- running each Chord node as an independently scheduled service
- restart behavior
- service discovery
- health checks
- resource isolation

Prerequisite:

- multi-process node runtime
- HTTP or gRPC node-to-node transport

Current progress:

- standalone HTTP storage node
- HTTP storage client
- integration tests for remote store behavior
- independent service-node process
- service gateway process
- Dockerfile
- Docker Compose demo
- Kubernetes demo manifests

Artifacts:

```text
Dockerfile
deploy/docker-compose.yml
deploy/kubernetes/ringforge-demo.yaml
```

Kubernetes is now attached to the service-node runtime and gateway, not to the simulation.

## LLM / Agentic Operations

Problem solved:

- explaining system behavior to engineers
- summarizing incidents
- interpreting diagnostics and event timelines
- recommending repair commands

Current seam:

- `DiagnosticReport`
- `OpsAdvice`
- `BenchmarkReport`
- `RingEvent`
- service gateway `/api/cluster/ops-report`
- `OperationsPromptBuilder`

Flow:

```text
cluster snapshot + diagnostics + events + benchmark
  -> deterministic preprocessing
  -> LLM explanation
  -> suggested commands
  -> deterministic validation before execution
```

The LLM must not decide key ownership, replica placement, or routing. Those remain deterministic system logic.
