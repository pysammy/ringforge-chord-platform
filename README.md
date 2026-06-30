# RingForge: Java Chord DHT Platform

RingForge is a Java distributed key-placement and routing diagnostics platform based on Chord-style consistent hashing. The project focuses on a practical infrastructure problem: helping engineers understand where data belongs, how lookups are routed, and whether a cluster remains healthy while nodes join, leave, or fail.

The first milestone provides an in-memory Chord ring with node joins, node leaves, finger tables, key insertion, lookup, key migration, health checks, a local API, and an engineer-facing browser console. Later milestones will evolve the system into a real multi-process service with networking, replication, failure detection, observability, and automated correctness testing.

## Why This Project Exists

Distributed key-value systems need more than a hash function. Engineers need to reason about ownership, routing, repair, and failure behavior:

- nodes that communicate over the network
- APIs for storing and retrieving data
- fault tolerance when machines crash
- replication so data is not lost
- metrics, logs, and debugging tools
- repeatable tests under node churn

RingForge is intended to bridge that gap.

## Project Vision

Build a Java-based distributed key-value store that uses Chord DHT routing to locate the node responsible for each key.

At maturity, the project should support:

- in-memory and optional persistent storage
- dynamic node join and leave
- finger-table based lookup
- background stabilization
- successor-based replication
- heartbeat-based failure detection
- HTTP or gRPC APIs
- CLI tools for operating a local cluster
- Prometheus-style metrics
- randomized simulation tests
- optional LLM-powered operations assistant

## Intended Users

This project is designed for learning and demonstration across:

- backend engineering
- distributed systems
- infrastructure engineering
- platform engineering
- data systems
- SDE interview preparation

## Non-Goals For The First Version

The first Java version will not try to be production-ready. It will focus on correctness and clean design before distributed networking is introduced.

Initial non-goals:

- no Kubernetes deployment
- no real persistence engine
- no cross-datacenter replication
- no Byzantine fault tolerance
- no consensus protocol such as Raft or Paxos

Those can be explored later if the core system becomes stable.

## Repository Layout

```text
ringforge-chord-platform/
  README.md
  pom.xml
  docs/
    EXECUTION_PLAN.md
    ARCHITECTURE.md
    LLM_INTEGRATION.md
  src/
    main/java/com/ringforge/chord/
    test/java/com/ringforge/chord/
```

## Development Approach

This project will be built in phases:

1. Implement the Chord simulation as clean Java domain objects.
2. Add automated tests for ring correctness.
3. Separate algorithm code from demo code.
4. Add an API layer.
5. Run multiple nodes as separate local processes.
6. Add replication and failure detection.
7. Add observability and operational tooling.
8. Add optional LLM-powered analysis and assistant features.

See [docs/EXECUTION_PLAN.md](docs/EXECUTION_PLAN.md) for the full step-by-step plan.

## Suggested Initial Commands

After implementation begins:

```bash
mvn test
mvn package
java -jar target/ringforge-chord-platform.jar
```

## Current Status

Status: Phase 1 implementation started.

Implemented so far:

- Java in-memory Chord ring simulation
- finger-table construction
- key insertion and lookup
- key migration on join and leave
- successor replication with replication factor 3
- simulated node crash with replica promotion
- invariant-based health checks
- structured event log for joins, leaves, lookups, key storage, key deletion, migrations, and repairs
- deterministic diagnostics and ops advice
- benchmark endpoint for lookup-hop accuracy checks
- HTTP-backed remote `KeyValueStore` client
- standalone node storage server for multi-process storage experiments
- independent Chord node service with HTTP lookup forwarding
- service-level integration tests that route requests across multiple node processes
- local HTTP API
- browser-based RingForge Console
- JUnit tests for range handling, lookup behavior, migration, and stale finger-table prevention

## Problem Focus

RingForge is not intended to be a technology collage. The project is centered on one distributed systems problem:

> How can an engineer verify and repair key placement and routing behavior while a distributed key-value cluster changes shape?

That means the project prioritizes:

- knowing which node owns each key
- proving that active routing tables do not point to departed nodes
- tracing why a lookup took a specific path
- detecting key placement mistakes
- preparing clean extension points for durable storage, event streams, deployment, and LLM-assisted operations

## Run The Current Java Version

Run tests:

```bash
mvn test
```

Run the console server:

```bash
mvn package
java -cp target/classes com.ringforge.chord.app.RingForgeServer 8080
```

Open:

```text
http://localhost:8080
```

Useful API endpoints:

```text
GET  /api/snapshot
GET  /api/events?limit=100
GET  /api/diagnostics
GET  /api/advice
GET  /api/lookup?start=0&key=99
POST /api/benchmark
POST /api/leave?node=65
POST /api/crash?node=65
POST /api/join?node=65
POST /api/repair
POST /api/reset
POST /api/put?key=77&value=manual
```

Run a standalone storage node:

```bash
java -cp target/classes com.ringforge.chord.transport.NodeStorageServer 9001
```

Storage node endpoints:

```text
GET  /node/health
POST /store/put?key=42&value=hello
GET  /store/get?key=42
GET  /store/snapshot
POST /store/delete?key=42
POST /store/drain
```

The first service-node runtime is also available through:

```text
ServiceChordNode
ServiceChordNodeServer
ServiceChordClient
```

This runtime supports independent HTTP Chord nodes with:

```text
GET  /node/state
GET  /node/health
GET  /lookup?key=...
POST /keys/put?key=...&value=...
GET  /keys/local?key=...
```

The current service-node implementation assumes a deterministic member list is configured before routing. Dynamic join, stabilization, and heartbeat repair are the next service-runtime steps.

## Current Reliability Behavior

RingForge currently uses in-memory storage, but it already models reliability behavior that production systems care about:

- every primary key is replicated to successor nodes
- a graceful leave migrates primary keys to the next owner
- a crash does not use graceful handoff; surviving replicas are promoted
- diagnostics verify routing links, key ownership, and replica count
- benchmark checks lookup behavior across every active node and primary key

External technologies should attach to those proven seams:

- Redis can implement `KeyValueStore`.
- Kafka can persist the structured event log.
- Kubernetes can run multi-process nodes after the full node-to-node routing phase.
- LLMs can summarize diagnostics, event logs, benchmarks, and repair recommendations.
