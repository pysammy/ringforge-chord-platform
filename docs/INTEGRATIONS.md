# Integration Roadmap

RingForge should use infrastructure technologies only where they solve a real system problem.

## Redis

Problem solved:

- durable or externalized per-node key-value storage

Current seam:

- `KeyValueStore`
- `InMemoryKeyValueStore`
- `HttpKeyValueStore`
- `NodeStorageServer`

Future adapter:

```text
RedisKeyValueStore
```

Chord remains responsible for ownership and routing. Redis only stores the local node's primary and replica data.

## Kafka

Problem solved:

- durable, replayable cluster event history
- incident reconstruction
- benchmark comparison
- LLM-ready operational context

Current seam:

- `EventLog`
- `RingEvent`
- `EventType`

Future adapter:

```text
KafkaEventPublisher
```

The in-memory event log should remain useful for tests. Kafka should be an additional publisher, not a replacement for deterministic correctness.

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

Future artifacts:

```text
Dockerfile
k8s/statefulset.yaml
k8s/service.yaml
k8s/configmap.yaml
```

Kubernetes should come after nodes communicate over the network. Until then, it would only package a simulation.

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

Future flow:

```text
cluster snapshot + diagnostics + events + benchmark
  -> deterministic preprocessing
  -> LLM explanation
  -> suggested commands
  -> deterministic validation before execution
```

The LLM must not decide key ownership, replica placement, or routing. Those remain deterministic system logic.
