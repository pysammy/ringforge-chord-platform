# Execution Plan

This document defines the step-by-step execution plan for building RingForge into a strong Java backend and infrastructure project.

## Phase 0: Project Foundation

Goal: create a clean Java project with documentation, source layout, and Git tracking.

Status: complete.

Tasks:

1. Create the separate Java project folder.
2. Add Maven project metadata.
3. Add documentation for architecture, milestones, and LLM usage.
4. Initialize a nested Git repository for independent commits.
5. Keep the implementation independent and focused on RingForge's own architecture.

Deliverable:

- documented Java project scaffold

## Phase 1: Core Java Simulation

Goal: implement the core Chord behavior in Java with clean domain objects and testable results.

Status: in progress.

Core classes:

- `NodeId`
- `ChordNode`
- `FingerTable`
- `KeyValueStore`
- `ChordRing`
- `LookupResult`

Features:

- 8-bit identifier space initially for deterministic local scenarios
- node join
- node leave
- insert key
- remove key
- find key
- print/debug finger tables
- deterministic demo equivalent to `main.cpp`

Success criteria:

- Java demo produces deterministic key ownership behavior.
- Each key maps to the expected responsible node.
- Node `100` joining migrates keys `99` and `100`.
- Node `65` leaving migrates its keys to node `100`.
- No active finger table points to node `65` after it leaves.

Current capabilities:

- structured lookup results
- invariant checker
- local HTTP API
- browser console for ring visualization

## Phase 2: Correctness And Test Harness

Goal: make the project testable and trustworthy.

Status: in progress.

Tasks:

1. Add JUnit tests for range handling, especially wraparound intervals.
2. Test finger table construction.
3. Test key ownership before and after joins.
4. Test key migration after leave.
5. Add property-style randomized tests.
6. Add invariants:
   - every active node has a valid successor
   - every active node has a valid predecessor
   - every key is stored on its responsible node
   - no active finger table points to a departed node
   - lookup terminates

Deliverable:

- reliable Java Chord simulation with automated tests

Current additions:

- invariant checker for active links, finger tables, and key ownership
- structured event log that can later be backed by Kafka or another event stream
- browser timeline for explaining cluster changes

## Phase 3: Library/Demo Separation

Goal: separate reusable implementation from scripted output.

Packages:

```text
com.ringforge.chord.core
com.ringforge.chord.storage
com.ringforge.chord.simulation
com.ringforge.chord.cli
```

Tasks:

1. Move core Chord logic into reusable classes.
2. Keep demos in separate runner classes.
3. Make lookup return structured results instead of only printing.
4. Add JSON-friendly data models for future APIs.

Deliverable:

- clean Java library plus CLI demo

## Phase 4: Local Service API

Goal: expose the DHT as a backend service.

API options:

- HTTP with Spring Boot
- HTTP with lightweight JDK server
- gRPC

Initial endpoints:

```text
PUT    /keys/{key}
GET    /keys/{key}
DELETE /keys/{key}
POST   /nodes/join
POST   /nodes/leave
GET    /nodes/{id}/finger-table
GET    /ring
GET    /metrics
```

Deliverable:

- one-process service API around the Chord simulation

## Phase 5: Multi-Process Local Cluster

Goal: make nodes communicate like real distributed services.

Status: storage transport and first node-to-node routing foundation complete; dynamic membership remains future work.

Example local usage:

```bash
java -jar ringforge.jar node --id 30 --port 5001
java -jar ringforge.jar node --id 65 --port 5002 --join localhost:5001
java -jar ringforge.jar node --id 110 --port 5003 --join localhost:5001
```

Tasks:

1. Replace direct object pointers with node addresses.
2. Add network client interfaces.
3. Add request timeouts.
4. Add retry behavior where appropriate.
5. Add local cluster scripts.

Deliverable:

- multiple Java processes forming a Chord ring locally

Current implementation:

- standalone HTTP node storage server
- HTTP-backed `KeyValueStore`
- tests proving Chord can place and look up keys through remote stores
- independent Chord node HTTP service
- node-to-node lookup forwarding
- routed key writes over HTTP
- service tests verifying forwarded paths and finger shortcuts

Remaining work:

- add membership gossip or a control-plane bootstrap service
- add dynamic join/stabilization endpoints
- add heartbeat-based failure detection
- add process supervision scripts

## Phase 6: Replication

Goal: prevent data loss when a node fails.

Status: local simulation complete.

Approach:

- store each key on the responsible node
- replicate each key to the next `N - 1` successors
- default replication factor: `3`

Tasks:

1. Add replica placement.
2. Add read repair.
3. Add replica promotion after failure.
4. Track primary vs replica ownership.
5. Test data availability after node crash.

Deliverable:

- fault-tolerant replicated key-value behavior

Current implementation:

- replication factor 3
- primary key placement on responsible node
- successor replica placement
- replica rebuild after put, delete, join, leave, and repair
- replica promotion after simulated crash

## Phase 7: Failure Detection And Recovery

Goal: handle nodes that crash without calling `leave()`.

Status: crash simulation complete; heartbeat detection is future multi-process work.

Tasks:

1. Add heartbeat checks.
2. Add suspected-failed state.
3. Repair successor/predecessor links.
4. Remove dead nodes from finger tables.
5. Re-replicate under-replicated keys.

Deliverable:

- self-healing local cluster under basic failures

Current implementation:

- `POST /api/crash?node={id}` removes a node without graceful handoff
- promoted replicas preserve crashed-node primary keys when replicas exist
- failed node history appears in diagnostics and ops advice

## Phase 8: Observability

Goal: make the system debuggable like real infrastructure.

Status: local API and UI observability in progress.

Metrics:

```text
ringforge_lookup_total
ringforge_lookup_latency_ms
ringforge_lookup_hops
ringforge_node_key_count
ringforge_node_replica_count
ringforge_failed_node_count
ringforge_stabilization_runs_total
```

Logs:

- structured JSON logs
- request IDs
- node IDs
- lookup path
- join/leave/failure events

Deliverable:

- metrics and logs suitable for local analysis

Current implementation:

- structured event log
- diagnostics endpoint
- ops advice endpoint
- browser timeline
- lookup benchmark summary

## Phase 9: Simulation And Benchmarking

Goal: analyze behavior under scale and churn.

Status: first benchmark path complete.

Scenarios:

- 10, 100, 1000 virtual nodes
- random key insertions
- skewed key distributions
- random joins and leaves
- random crashes
- different replication factors

Measurements:

- average lookup hops
- p95 lookup hops
- lookup latency
- key movement after joins
- unavailable keys after failures
- load distribution variance

Deliverable:

- repeatable benchmark reports

Current implementation:

- benchmark runs lookups from every active node to every primary key
- reports lookup count, node count, key count, average hops, max hops, and health

## Phase 10: LLM-Assisted Operations

Goal: use LLMs in a practical, backend-relevant way without making them part of the core correctness path.

Status: deterministic ops-advice layer complete; external LLM integration is future work.

Possible features:

- explain why a lookup failed using logs and ring state
- summarize cluster health
- suggest likely stale finger-table entries
- generate human-readable incident reports
- translate metrics into operational recommendations
- help compare benchmark runs

Important rule:

The LLM should assist humans. It should not decide key ownership, replication, or routing correctness.

Deliverable:

- optional operations assistant layered on top of deterministic system state

## Final Target Resume Version

Target project description:

> Built RingForge, a Java distributed key-value platform based on Chord DHT routing. Implemented dynamic node joins/leaves, finger-table stabilization, successor replication, heartbeat-based failure detection, multi-process local clustering, Prometheus-style metrics, and randomized simulation tests for churn and lookup correctness. Added an optional LLM operations assistant for explaining cluster health, failed lookups, and benchmark results.
