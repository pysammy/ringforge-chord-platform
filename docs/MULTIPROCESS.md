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

- each node is configured with a deterministic member list before routing starts

This means the runtime now proves node-to-node forwarding, but does not yet implement dynamic membership.

## What Is Not Done Yet

The full Chord node is not yet independently responsible for:

- background stabilization
- heartbeat checks
- membership gossip

Those are the next pieces required for a true multi-process Chord cluster.

## Next Implementation Slice

The next slice should introduce a `ChordNodeService` process with endpoints such as:
The next slice should add dynamic membership and stabilization endpoints such as:

```text
POST /node/join
POST /node/notify
POST /node/stabilize
```

The storage server and service-node runtime can then be combined into one independently deployable node process.

## Why This Matters

This avoids pretending Kubernetes or Redis makes the system distributed. The project first proves the boundary:

```text
Chord routing logic -> KeyValueStore interface -> HTTP-backed node storage
```

Once that boundary is stable, Redis can replace the HTTP storage server, and Kubernetes can run each node process independently.
