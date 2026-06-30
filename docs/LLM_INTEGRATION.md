# LLM Integration Plan

This project can use LLMs, but they should be used carefully.

The core Chord system must remain deterministic. Routing, key ownership, replication, and failure recovery should be implemented using normal code and verified with tests.

LLMs are best used as an operations and analysis layer on top of system state.

## Good LLM Use Cases

### 1. Cluster Health Explanation

Input:

- ring state
- node list
- finger tables
- key distribution
- failed node list
- recent logs

Output:

- plain-English summary of cluster health
- suspicious nodes
- stale finger-table candidates
- under-replicated keys

Example prompt target:

```text
Explain why node 0 is routing through node 65 even though node 65 has left the ring.
```

### 2. Failed Lookup Analysis

Input:

- lookup key
- lookup path
- responsible node
- actual storage location
- node status
- recent stabilization events

Output:

- probable cause
- affected nodes
- recommended repair action

Example:

```text
Lookup for key 99 from node 0 traversed [0, 65, 100].
Node 65 is marked inactive.
Explain the likely stale finger-table entry and suggest a repair.
```

### 3. Benchmark Report Summaries

Input:

- benchmark metrics
- lookup hop counts
- latency percentiles
- key movement
- failure scenarios

Output:

- short engineering report
- regression summary
- comparison between runs

### 4. Incident Report Drafting

Input:

- time window
- logs
- metrics
- failed operations
- topology changes

Output:

- incident timeline
- suspected root cause
- blast radius
- follow-up action items

### 5. Natural Language CLI Assistant

Example user commands:

```text
Why are lookups slow?
Which node has the most keys?
Do any finger tables point to inactive nodes?
What changed after node 100 joined?
```

The assistant should convert those questions into deterministic inspections of cluster state, then explain the result.

## Bad LLM Use Cases

Avoid using LLMs for:

- choosing the responsible node for a key
- deciding whether a node is alive
- modifying finger tables directly
- deciding replica placement
- accepting writes without deterministic validation
- replacing automated tests

Those tasks must remain deterministic and code-driven.

## Proposed LLM Module

Future package:

```text
com.ringforge.chord.llm
```

Potential classes:

```text
ClusterSnapshot
LookupTrace
HealthFinding
LlmAnalysisRequest
LlmAnalysisResponse
OperationsAssistant
```

## Data Boundary

The LLM layer should receive sanitized, structured system state.

Good input:

```json
{
  "activeNodes": [0, 30, 100, 110, 160, 230],
  "inactiveNodes": [65],
  "lookup": {
    "key": 99,
    "path": [0, 65, 100],
    "resultNode": 100
  },
  "fingerTables": {
    "0": {
      "6": 65,
      "7": 100
    }
  }
}
```

Bad input:

```text
raw secrets, credentials, private user data, unbounded logs
```

## Implementation Strategy

1. First, build deterministic diagnostics without an LLM.
2. Represent diagnostics as structured JSON.
3. Add an optional LLM summarizer for those diagnostics.
4. Keep the system fully usable when the LLM feature is disabled.

## Resume-Relevant Framing

This is the strongest way to include LLMs:

> Added an optional LLM operations assistant that explains lookup failures, stale routing entries, replication gaps, and benchmark regressions from structured cluster snapshots, while keeping routing and replication correctness fully deterministic.
