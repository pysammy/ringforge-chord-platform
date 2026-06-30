package com.ringforge.chord.simulation;

import com.ringforge.chord.core.ChordNode;
import com.ringforge.chord.core.FingerEntry;
import com.ringforge.chord.core.IdentifierRing;
import com.ringforge.chord.core.LookupResult;
import com.ringforge.chord.diagnostics.DiagnosticFinding;
import com.ringforge.chord.diagnostics.DiagnosticReport;
import com.ringforge.chord.events.EventLog;
import com.ringforge.chord.events.EventType;
import com.ringforge.chord.events.RingEvent;
import com.ringforge.chord.metrics.BenchmarkReport;
import com.ringforge.chord.ops.OpsAdvice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class ChordRing {
    private static final int DEFAULT_REPLICATION_FACTOR = 3;

    private final IdentifierRing identifierRing;
    private final NavigableMap<Integer, ChordNode> nodes = new TreeMap<>();
    private final Set<Integer> failedNodeIds = new LinkedHashSet<>();
    private final EventLog eventLog = new EventLog();
    private final int replicationFactor;

    public ChordRing(int bitLength) {
        this(bitLength, DEFAULT_REPLICATION_FACTOR);
    }

    public ChordRing(int bitLength, int replicationFactor) {
        this.identifierRing = new IdentifierRing(bitLength);
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("replicationFactor must be at least 1");
        }
        this.replicationFactor = replicationFactor;
        eventLog.record(EventType.RING_CREATED, "Ring created with " + identifierRing.size() + " slots.",
                EventLog.details("bitLength", String.valueOf(bitLength), "ringSize", String.valueOf(identifierRing.size()),
                        "replicationFactor", String.valueOf(replicationFactor)));
    }

    public IdentifierRing identifierRing() {
        return identifierRing;
    }

    public int replicationFactor() {
        return replicationFactor;
    }

    public ChordNode join(int rawNodeId) {
        int nodeId = identifierRing.normalize(rawNodeId);
        if (nodes.containsKey(nodeId)) {
            throw new IllegalArgumentException("Node already exists: " + nodeId);
        }
        ChordNode node = new ChordNode(nodeId);
        nodes.put(nodeId, node);
        failedNodeIds.remove(nodeId);
        rebuildTopology();
        eventLog.record(EventType.NODE_JOINED, "Node " + nodeId + " joined the ring.",
                EventLog.details("nodeId", String.valueOf(nodeId), "activeNodeCount", String.valueOf(nodes.size())));
        rebalanceKeys();
        return node;
    }

    public Optional<ChordNode> leave(int rawNodeId) {
        int nodeId = identifierRing.normalize(rawNodeId);
        ChordNode leaving = nodes.remove(nodeId);
        if (leaving == null) {
            return Optional.empty();
        }
        Map<Integer, String> movedKeys = leaving.store().drain();
        leaving.clearReplicas();
        rebuildTopology();
        eventLog.record(EventType.NODE_LEFT, "Node " + nodeId + " left the ring.",
                EventLog.details("nodeId", String.valueOf(nodeId), "movedKeyCount", String.valueOf(movedKeys.size()),
                        "activeNodeCount", String.valueOf(nodes.size())));
        for (Map.Entry<Integer, String> entry : movedKeys.entrySet()) {
            ChordNode target = internalPut(entry.getKey(), entry.getValue());
            eventLog.record(EventType.KEY_MIGRATED, "Key " + entry.getKey() + " moved from node " + nodeId
                            + " to node " + target.id() + ".",
                    EventLog.details("key", String.valueOf(entry.getKey()), "fromNodeId", String.valueOf(nodeId),
                            "toNodeId", String.valueOf(target.id()), "reason", "node-left"));
        }
        rebalanceKeys();
        return Optional.of(leaving);
    }

    public Optional<ChordNode> crash(int rawNodeId) {
        int nodeId = identifierRing.normalize(rawNodeId);
        ChordNode failed = nodes.remove(nodeId);
        if (failed == null) {
            return Optional.empty();
        }

        Map<Integer, String> lostPrimaryKeys = failed.store().drain();
        failed.clearReplicas();
        failedNodeIds.add(nodeId);
        rebuildTopology();

        eventLog.record(EventType.NODE_FAILED, "Node " + nodeId + " failed without graceful handoff.",
                EventLog.details("nodeId", String.valueOf(nodeId), "lostPrimaryKeyCount", String.valueOf(lostPrimaryKeys.size()),
                        "activeNodeCount", String.valueOf(nodes.size())));

        promoteLostKeysFromReplicas(lostPrimaryKeys.keySet(), nodeId);
        rebalanceKeys();
        return Optional.of(failed);
    }

    public void put(int rawKey, String value) {
        ensureNotEmpty();
        int key = identifierRing.normalize(rawKey);
        ChordNode owner = internalPut(key, value);
        rebuildReplicas();
        eventLog.record(EventType.KEY_STORED, "Key " + key + " stored on node " + owner.id() + ".",
                EventLog.details("key", String.valueOf(key), "nodeId", String.valueOf(owner.id())));
    }

    public Optional<String> delete(int rawKey) {
        ensureNotEmpty();
        int key = identifierRing.normalize(rawKey);
        ChordNode owner = findSuccessor(key);
        Optional<String> deleted = owner.store().delete(key);
        if (deleted.isPresent()) {
            rebuildReplicas();
            eventLog.record(EventType.KEY_DELETED, "Key " + key + " deleted from node " + owner.id() + ".",
                    EventLog.details("key", String.valueOf(key), "nodeId", String.valueOf(owner.id())));
        }
        return deleted;
    }

    public LookupResult lookup(int rawStartNodeId, int rawKey) {
        ensureNotEmpty();
        long startedAt = System.nanoTime();
        int startNodeId = identifierRing.normalize(rawStartNodeId);
        int key = identifierRing.normalize(rawKey);
        ChordNode current = nodes.get(startNodeId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown start node: " + startNodeId);
        }

        List<Integer> path = new ArrayList<>();
        Set<Integer> visited = new LinkedHashSet<>();
        int maxHops = Math.max(2, nodes.size() + identifierRing.bitLength() + 2);

        for (int hop = 0; hop < maxHops; hop++) {
            path.add(current.id());
            if (current.isResponsibleFor(key, identifierRing)) {
                Optional<String> value = current.store().get(key);
                LookupResult result = new LookupResult(key, value, startNodeId, current.id(), path, System.nanoTime() - startedAt);
                recordLookup(result);
                return result;
            }

            if (!visited.add(current.id())) {
                break;
            }

            ChordNode next = current.closestPrecedingFinger(key, identifierRing);
            if (next == current || visited.contains(next.id())) {
                next = current.successor();
            }
            current = next;
        }

        ChordNode responsible = findSuccessor(key);
        if (path.isEmpty() || path.get(path.size() - 1) != responsible.id()) {
            path.add(responsible.id());
        }
        Optional<String> value = responsible.store().get(key);
        LookupResult result = new LookupResult(key, value, startNodeId, responsible.id(), path, System.nanoTime() - startedAt);
        recordLookup(result);
        return result;
    }

    public ChordNode findSuccessor(int rawId) {
        ensureNotEmpty();
        int id = identifierRing.normalize(rawId);
        Map.Entry<Integer, ChordNode> entry = nodes.ceilingEntry(id);
        return entry == null ? nodes.firstEntry().getValue() : entry.getValue();
    }

    public List<ChordNode> nodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes.values()));
    }

    public Set<Integer> activeNodeIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(nodes.keySet()));
    }

    public Set<Integer> failedNodeIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(failedNodeIds));
    }

    public Map<Integer, Map<Integer, String>> keyDistribution() {
        Map<Integer, Map<Integer, String>> distribution = new LinkedHashMap<>();
        for (ChordNode node : nodes.values()) {
            distribution.put(node.id(), node.snapshotKeys());
        }
        return distribution;
    }

    public Map<Integer, Map<Integer, String>> replicaDistribution() {
        Map<Integer, Map<Integer, String>> distribution = new LinkedHashMap<>();
        for (ChordNode node : nodes.values()) {
            distribution.put(node.id(), node.snapshotReplicas());
        }
        return distribution;
    }

    public HealthReport healthReport() {
        return new InvariantChecker(this).check();
    }

    public List<RingEvent> events() {
        return eventLog.snapshot();
    }

    public List<RingEvent> latestEvents(int limit) {
        return eventLog.latest(limit);
    }

    public DiagnosticReport diagnostics() {
        List<DiagnosticFinding> findings = new ArrayList<>();
        HealthReport health = healthReport();

        for (HealthFinding finding : health.findings()) {
            findings.add(new DiagnosticFinding(finding.severity(), "ring-invariant", finding.message(),
                    "Run repair and inspect the node's predecessor, successor, and finger table."));
        }

        if (!failedNodeIds.isEmpty()) {
            findings.add(new DiagnosticFinding("INFO", "failure-history",
                    "Failed nodes observed: " + failedNodeIds,
                    "Use crash scenarios to verify replica promotion and repair behavior."));
        }

        int expectedReplicaCount = expectedReplicaCount();
        for (ChordNode owner : nodes.values()) {
            for (Integer key : owner.snapshotKeys().keySet()) {
                int actualReplicas = replicaCount(key);
                if (actualReplicas < expectedReplicaCount) {
                    findings.add(new DiagnosticFinding("ERROR", "replication",
                            "Key " + key + " has " + actualReplicas + " replicas; expected " + expectedReplicaCount + ".",
                            "Run repair to rebuild successor replicas."));
                }
            }
        }

        int minKeys = nodes.isEmpty() ? 0 : Integer.MAX_VALUE;
        int maxKeys = 0;
        for (ChordNode node : nodes.values()) {
            int count = node.snapshotKeys().size();
            minKeys = Math.min(minKeys, count);
            maxKeys = Math.max(maxKeys, count);
        }
        if (nodes.size() > 1 && maxKeys - minKeys > 3) {
            findings.add(new DiagnosticFinding("WARN", "load-balance",
                    "Primary key distribution is skewed: min=" + minKeys + ", max=" + maxKeys + ".",
                    "Add virtual nodes or rebalance key ranges before production use."));
        }

        int highHopThreshold = Math.max(2, (int) Math.ceil(log2(Math.max(2, nodes.size()))) + 1);
        for (RingEvent event : latestEvents(25)) {
            if (event.type() == EventType.LOOKUP_COMPLETED) {
                String hopCount = event.details().get("hopCount");
                if (hopCount != null && Integer.parseInt(hopCount) > highHopThreshold) {
                    findings.add(new DiagnosticFinding("WARN", "lookup-routing",
                            "Lookup event #" + event.sequence() + " used " + hopCount + " hops.",
                            "Inspect finger tables for stale or suboptimal entries."));
                }
            }
        }

        return new DiagnosticReport(findings);
    }

    public BenchmarkReport benchmark() {
        List<Integer> keys = new ArrayList<>();
        for (ChordNode node : nodes.values()) {
            keys.addAll(node.snapshotKeys().keySet());
        }
        List<Integer> startNodes = new ArrayList<>(nodes.keySet());

        int lookupCount = 0;
        int totalHops = 0;
        int maxHops = 0;
        for (Integer startNode : startNodes) {
            for (Integer key : keys) {
                LookupResult result = lookup(startNode, key);
                lookupCount++;
                totalHops += result.hopCount();
                maxHops = Math.max(maxHops, result.hopCount());
            }
        }

        double averageHops = lookupCount == 0 ? 0.0 : (double) totalHops / lookupCount;
        BenchmarkReport report = new BenchmarkReport(lookupCount, nodes.size(), keys.size(), averageHops,
                maxHops, diagnostics().healthy());
        eventLog.record(EventType.BENCHMARK_COMPLETED, "Benchmark completed across active nodes and primary keys.",
                EventLog.details("lookupCount", String.valueOf(report.lookupCount()),
                        "averageHops", String.format(java.util.Locale.US, "%.3f", report.averageHops()),
                        "maxHops", String.valueOf(report.maxHops()), "healthy", String.valueOf(report.healthy())));
        return report;
    }

    public OpsAdvice opsAdvice() {
        DiagnosticReport report = diagnostics();
        List<String> summary = new ArrayList<>();
        List<String> actions = new ArrayList<>();

        if (report.findings().isEmpty()) {
            summary.add("Cluster invariants, key ownership, and successor replicas are healthy.");
            actions.add("Continue monitoring lookup hops, key distribution, and failure events.");
        } else {
            summary.add("Diagnostics found " + report.findings().size() + " item(s) needing attention.");
            for (DiagnosticFinding finding : report.findings()) {
                actions.add(finding.recommendation());
            }
        }

        if (!failedNodeIds.isEmpty()) {
            summary.add("The ring has observed failed nodes: " + failedNodeIds + ".");
            actions.add("Review replica promotion events and confirm replacement nodes rejoin cleanly.");
        }

        actions = new ArrayList<>(new LinkedHashSet<>(actions));
        return new OpsAdvice(summary, actions);
    }

    public void repair() {
        rebuildTopology();
        rebalanceKeys();
        HealthReport healthReport = healthReport();
        eventLog.record(EventType.RING_REPAIRED, "Ring repair completed.",
                EventLog.details("healthy", String.valueOf(healthReport.healthy()),
                        "findingCount", String.valueOf(healthReport.findings().size())));
    }

    private void rebuildTopology() {
        if (nodes.isEmpty()) {
            return;
        }

        List<ChordNode> ordered = new ArrayList<>(nodes.values());
        for (int i = 0; i < ordered.size(); i++) {
            ChordNode current = ordered.get(i);
            ChordNode predecessor = ordered.get((i - 1 + ordered.size()) % ordered.size());
            ChordNode successor = ordered.get((i + 1) % ordered.size());
            current.setPredecessor(predecessor);
            current.setSuccessor(successor);

            List<FingerEntry> entries = new ArrayList<>();
            for (int finger = 1; finger <= identifierRing.bitLength(); finger++) {
                int start = identifierRing.fingerStart(current.id(), finger);
                int end = identifierRing.fingerEndExclusive(current.id(), finger);
                entries.add(new FingerEntry(finger, start, end, successorOf(start)));
            }
            current.fingerTable().replaceAll(entries);
        }
    }

    private ChordNode successorOf(int rawId) {
        int id = identifierRing.normalize(rawId);
        Map.Entry<Integer, ChordNode> entry = nodes.ceilingEntry(id);
        return entry == null ? nodes.firstEntry().getValue() : entry.getValue();
    }

    private void rebalanceKeys() {
        if (nodes.isEmpty()) {
            return;
        }

        List<OwnedValue> allKeys = new ArrayList<>();
        for (ChordNode node : nodes.values()) {
            node.clearReplicas();
            int oldOwnerId = node.id();
            for (Map.Entry<Integer, String> entry : node.store().drain().entrySet()) {
                allKeys.add(new OwnedValue(entry.getKey(), entry.getValue(), oldOwnerId));
            }
        }
        for (OwnedValue value : allKeys) {
            ChordNode newOwner = internalPut(value.key, value.value);
            if (newOwner.id() != value.ownerId) {
                eventLog.record(EventType.KEY_MIGRATED, "Key " + value.key + " moved from node "
                                + value.ownerId + " to node " + newOwner.id() + ".",
                        EventLog.details("key", String.valueOf(value.key), "fromNodeId", String.valueOf(value.ownerId),
                                "toNodeId", String.valueOf(newOwner.id()), "reason", "ownership-rebalance"));
            }
        }
        rebuildReplicas();
    }

    private ChordNode internalPut(int rawKey, String value) {
        int key = identifierRing.normalize(rawKey);
        ChordNode owner = findSuccessor(key);
        owner.store().put(key, value);
        return owner;
    }

    private void promoteLostKeysFromReplicas(Set<Integer> lostKeys, int failedNodeId) {
        if (nodes.isEmpty()) {
            return;
        }
        for (Integer key : lostKeys) {
            Optional<String> replicaValue = findReplicaValue(key);
            if (replicaValue.isPresent()) {
                ChordNode newOwner = internalPut(key, replicaValue.get());
                eventLog.record(EventType.REPLICA_PROMOTED, "Replica for key " + key
                                + " promoted after node " + failedNodeId + " failed.",
                        EventLog.details("key", String.valueOf(key), "failedNodeId", String.valueOf(failedNodeId),
                                "newOwnerId", String.valueOf(newOwner.id())));
            }
        }
    }

    private Optional<String> findReplicaValue(int key) {
        for (ChordNode node : nodes.values()) {
            Optional<String> value = node.replicaStore().get(key);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private void rebuildReplicas() {
        for (ChordNode node : nodes.values()) {
            node.clearReplicas();
        }
        int replicaCount = expectedReplicaCount();
        if (replicaCount == 0) {
            return;
        }

        for (ChordNode owner : nodes.values()) {
            for (Map.Entry<Integer, String> entry : owner.snapshotKeys().entrySet()) {
                for (ChordNode replicaNode : successorsAfter(owner.id(), replicaCount)) {
                    replicaNode.replicaStore().put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private List<ChordNode> successorsAfter(int nodeId, int count) {
        List<ChordNode> ordered = new ArrayList<>(nodes.values());
        List<ChordNode> successors = new ArrayList<>();
        if (ordered.size() <= 1 || count <= 0) {
            return successors;
        }
        int startIndex = 0;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id() == nodeId) {
                startIndex = i;
                break;
            }
        }
        int boundedCount = Math.min(count, ordered.size() - 1);
        for (int offset = 1; offset <= boundedCount; offset++) {
            successors.add(ordered.get((startIndex + offset) % ordered.size()));
        }
        return successors;
    }

    private int expectedReplicaCount() {
        return Math.min(Math.max(0, replicationFactor - 1), Math.max(0, nodes.size() - 1));
    }

    private int replicaCount(int key) {
        int count = 0;
        for (ChordNode node : nodes.values()) {
            if (node.replicaStore().get(key).isPresent()) {
                count++;
            }
        }
        return count;
    }

    private double log2(int value) {
        return Math.log(value) / Math.log(2.0);
    }

    private void recordLookup(LookupResult result) {
        eventLog.record(EventType.LOOKUP_COMPLETED, "Lookup for key " + result.key() + " resolved at node "
                        + result.responsibleNodeId() + ".",
                EventLog.details("key", String.valueOf(result.key()), "startNodeId", String.valueOf(result.startNodeId()),
                        "responsibleNodeId", String.valueOf(result.responsibleNodeId()), "found", String.valueOf(result.found()),
                        "hopCount", String.valueOf(result.hopCount()), "path", result.path().toString()));
    }

    private void ensureNotEmpty() {
        if (nodes.isEmpty()) {
            throw new IllegalStateException("Ring has no active nodes");
        }
    }

    private static final class OwnedValue {
        private final int key;
        private final String value;
        private final int ownerId;

        private OwnedValue(int key, String value, int ownerId) {
            this.key = key;
            this.value = value;
            this.ownerId = ownerId;
        }
    }
}
