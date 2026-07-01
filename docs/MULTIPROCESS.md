# Multi-Process Runtime Plan

RingForge now has a real transport boundary for storage. A Chord node can use an in-memory `KeyValueStore` or a remote HTTP-backed `KeyValueStore` without changing the routing algorithm.

This is the first step toward running each node as its own process.

## What Exists Now

### Standalone Storage Node

Run:

```bash
java -cp target/classes com.ringforge.chord.transport.NodeStorageServer 9001
```

Endpoints:

```text
GET  /node/health
POST /store/put?key=42&value=hello
GET  /store/get?key=42
GET  /store/snapshot
POST /store/delete?key=42
POST /store/drain
```

### HTTP Storage Client

Class:

```text
com.ringforge.chord.transport.HttpKeyValueStore
```

It implements:

```text
KeyValueStore
```

That means a Chord node can use remote storage through the same interface as in-memory storage.

## What The Tests Prove

The integration tests prove three things:

1. Remote storage supports put, get, delete, snapshot, and drain.
2. Remote drain returns values and clears the server.
3. A Chord ring can place and look up keys when node storage lives behind HTTP.
4. Independent Chord node services can route `put` and `lookup` requests over HTTP.
5. Finger-table shortcuts are used by the service-node runtime for forwarded lookups.

This validates the storage transport layer before moving routing itself across processes.

## Service Node Runtime

The first service-node runtime is implemented with:

```text
ServiceChordNode
ServiceChordNodeServer
ServiceChordClient
```

Each node owns:

- node ID
- predecessor ID
- successor ID
- finger table
- local key-value storage
- HTTP forwarding for lookup and put operations

Endpoints:

```text
GET  /node/state
GET  /node/health
GET  /lookup?key=...
POST /keys/put?key=...&value=...
GET  /keys/local?key=...
```

Current limitation:

- membership is deterministic and propagated through a bootstrap node
- full gossip-style membership is not implemented yet

This means the runtime now proves node-to-node forwarding and bootstrap joins, but does not yet implement heartbeat-driven membership repair.

## Bootstrap Join

A new service node can join through a known bootstrap node:

```text
newNode.joinVia(selfEndpoint, bootstrapEndpoint)
```

The flow is:

1. The joining node registers itself with the bootstrap node.
2. The bootstrap node returns the updated member list.
3. The joining node configures its predecessor, successor, and finger table.
4. The member list is pushed to all known nodes.
5. Each node rebalances local keys it no longer owns.

The integration test verifies this behavior with key `99`:

```text
Initial ring: 0 -> 65 -> 110
Key 99 belongs to node 110.
Node 100 joins through node 65.
Key 99 migrates from node 110 to node 100.
Future lookups resolve to node 100.
```

## What Is Not Done Yet

The full Chord node is not yet independently responsible for:

- background stabilization
- heartbeat checks
- membership gossip

Those are the next pieces required for a true multi-process Chord cluster.

## Next Implementation Slice

The next slice should introduce a `ChordNodeService` process with endpoints such as:
The runtime now exposes deterministic membership and stabilization endpoints:

```text
POST /node/join
POST /node/notify
POST /node/stabilize
POST /node/heartbeat-repair
GET  /node/members
POST /node/members
GET  /node/successor
GET  /node/predecessor
GET  /node/finger-table
```

## Heartbeat Repair

Service nodes now expose:

```text
POST /node/heartbeat-repair
```

The endpoint:

1. Probes every known peer through `/node/health`.
2. Removes unreachable peers from the member list.
3. Recomputes predecessor, successor, and finger table entries.
4. Propagates the repaired member list to surviving nodes.
5. Rebalances local keys that no longer belong to the current node.

The integration test verifies:

```text
Initial ring: 0 -> 30 -> 65
Key 45 is stored on node 65.
Lookup from node 0 routes [0, 30, 65].
Node 30 stops.
Node 0 runs heartbeat repair.
Membership becomes 0 -> 65.
Lookup from node 0 routes [0, 65].
```

Current limitation:

- service-runtime replication is not implemented yet, so keys whose primary owner fails are not recovered in this phase

The next slice should add service-runtime replication and replica promotion.

The storage server and service-node runtime can then be combined into one independently deployable node process.

## Why This Matters

This avoids pretending Kubernetes or Redis makes the system distributed. The project first proves the boundary:

```text
Chord routing logic -> KeyValueStore interface -> HTTP-backed node storage
```

Once that boundary is stable, Redis can replace the HTTP storage server, and Kubernetes can run each node process independently.
