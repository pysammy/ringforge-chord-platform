# Architecture

RingForge will start as a deterministic Java simulation and evolve into a local distributed system.

## High-Level Model

Chord places nodes and keys on the same identifier ring.

For the initial version:

```text
Identifier space: 0 to 255
Ring size: 2^8
```

A node owns all keys in the interval:

```text
(predecessorId, nodeId]
```

Example:

```text
0 -> 30 -> 65 -> 100 -> 110 -> 160 -> 230 -> 0
```

Node `100` owns:

```text
(65, 100]
```

So keys `99` and `100` belong to node `100`.

## Core Components

### `ChordNode`

Represents one node in the ring.

Responsibilities:

- maintain node ID
- store predecessor and successor
- maintain finger table
- route lookups
- own local keys
- join and leave the ring

### `FingerTable`

Stores routing shortcuts.

Instead of walking around the ring one node at a time, a node can jump closer to the destination key.

For an identifier space of size `2^m`, each node has `m` finger entries.

### `ChordRing`

Simulation-level manager used in the first implementation.

Responsibilities:

- create nodes
- track active nodes
- run stabilization rounds
- verify invariants
- support deterministic tests

This class should not exist in the same form once the project becomes multi-process. In a real distributed version, no single object should own the whole ring.

### `KeyValueStore`

Local storage abstraction.

Implementations:

- in-memory map
- HTTP-backed remote storage for transport-boundary tests
- Redis-backed primary and replica stores for the service runtime

### `LookupResult`

Structured response for key lookups.

Fields:

- key
- value
- found flag
- responsible node
- lookup path
- hop count
- latency

This is better than only printing output because tests, APIs, and dashboards can consume it.

## Package Plan

```text
com.ringforge.chord.core
  ChordNode
  FingerTable
  NodeId
  IdentifierRing
  LookupResult

com.ringforge.chord.storage
  KeyValueStore
  InMemoryKeyValueStore
  HttpKeyValueStore
  RedisKeyValueStore

com.ringforge.chord.simulation
  ChordRing
  SimulationRunner
  InvariantChecker

com.ringforge.chord.api
  JSON serialization for the simulation API

com.ringforge.chord.service
  independent HTTP service-node runtime
  node-to-node forwarding
  heartbeat repair
  successor replication

com.ringforge.chord.events
  service event publisher interface
  no-op test publisher
  Kafka-backed service event publisher

com.ringforge.chord.metrics
  benchmark report models

com.ringforge.chord.llm
  LLM-safe operations prompt boundary

com.ringforge.chord.app
  simulation console server
  service node process
  service gateway process
```

## Design Principles

### Keep Routing Deterministic

Chord ownership, routing, stabilization, and replication must be deterministic and testable.

LLMs must not decide correctness-critical behavior.

### Avoid Raw Object Coupling Long-Term

The Java simulation can begin with object references, but the architecture should move toward:

```text
NodeId + NodeAddress + NodeClient
```

That makes the later multi-process version cleaner.

### Return Structured Results

Methods should return objects rather than only printing.

Good:

```text
LookupResult
JoinResult
LeaveResult
ReplicationReport
```

This supports:

- tests
- APIs
- metrics
- LLM explanations

### Verify Invariants

The system should regularly verify correctness properties:

- successor ring is connected
- predecessor links are consistent
- finger tables point only to active nodes
- each key is on its responsible node
- replicas exist according to the configured replication factor
- lookups terminate

## Evolution Path

### Stage 1: In-Memory Simulation

One JVM process contains all nodes.

Useful for:

- fast algorithm iteration
- fast tests
- deterministic debugging

### Stage 2: API-Wrapped Simulation

One service exposes the ring over HTTP.

Useful for:

- backend API practice
- CLI and dashboard integration

### Stage 3: Multi-Process Nodes

Each node is its own Java process.

Useful for:

- real network behavior
- timeout handling
- failure detection
- infrastructure-style testing

### Stage 4: Replicated Fault-Tolerant Store

Keys are replicated across successors.

Useful for:

- high availability concepts
- failure recovery
- backend/infra interview depth

### Stage 5: DHT-As-A-Service Gateway

A gateway exposes client-facing operations above the service-node cluster:

```text
POST /api/dht/put
GET  /api/dht/get
GET  /api/cluster/snapshot
GET  /api/cluster/ops-report
GET  /metrics
```

The gateway does not decide ownership. It delegates reads and writes to the Chord service nodes, then aggregates deterministic state for engineers, dashboards, metrics, and optional LLM explanations.

### Stage 6: Externalized Storage And Event Stream

Each service node can store primary and replica copies in Redis while Kafka receives best-effort service events.

Useful for:

- proving ownership and replica placement outside JVM memory
- replaying joins, writes, lookups, repairs, and promotions
- demonstrating infrastructure behavior in Docker Compose and Kubernetes
- keeping routing correctness independent from storage and event vendors

## Major Technical Risks

### Stale Routing Entries

When a node leaves or fails, other nodes may still route through it.

Mitigation:

- active-node registry in simulation
- stabilization
- finger repair
- failure detection
- invariant tests

### Ambiguous Missing Values

Missing values and explicit stored values must remain distinct. Java should use:

```text
Optional<Value>
```

or a response object with:

```text
found: true/false
value: ...
```

### Overloading The First Version

The project should not start with networking, persistence, replication, and LLMs all at once.

The implemented order is:

1. correct simulation
2. strong tests
3. API
4. networking
5. replication
6. observability
7. Redis storage
8. Kafka event stream
9. Docker and Kubernetes deployment
10. optional LLM assistant
