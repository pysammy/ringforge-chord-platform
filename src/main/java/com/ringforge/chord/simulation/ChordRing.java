package com.ringforge.chord.simulation;

import com.ringforge.chord.core.ChordNode;
import com.ringforge.chord.core.FingerEntry;
import com.ringforge.chord.core.IdentifierRing;
import com.ringforge.chord.core.LookupResult;
import com.ringforge.chord.events.EventLog;
import com.ringforge.chord.events.EventType;
import com.ringforge.chord.events.RingEvent;

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
    private final IdentifierRing identifierRing;
    private final NavigableMap<Integer, ChordNode> nodes = new TreeMap<>();
    private final EventLog eventLog = new EventLog();

    public ChordRing(int bitLength) {
        this.identifierRing = new IdentifierRing(bitLength);
        eventLog.record(EventType.RING_CREATED, "Ring created with " + identifierRing.size() + " slots.",
                EventLog.details("bitLength", String.valueOf(bitLength), "ringSize", String.valueOf(identifierRing.size())));
    }

    public IdentifierRing identifierRing() {
        return identifierRing;
    }

    public ChordNode join(int rawNodeId) {
        int nodeId = identifierRing.normalize(rawNodeId);
        if (nodes.containsKey(nodeId)) {
            throw new IllegalArgumentException("Node already exists: " + nodeId);
        }
        ChordNode node = new ChordNode(nodeId);
        nodes.put(nodeId, node);
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

    public void put(int rawKey, String value) {
        ensureNotEmpty();
        int key = identifierRing.normalize(rawKey);
        ChordNode owner = internalPut(key, value);
        eventLog.record(EventType.KEY_STORED, "Key " + key + " stored on node " + owner.id() + ".",
                EventLog.details("key", String.valueOf(key), "nodeId", String.valueOf(owner.id())));
    }

    public Optional<String> delete(int rawKey) {
        ensureNotEmpty();
        int key = identifierRing.normalize(rawKey);
        ChordNode owner = findSuccessor(key);
        Optional<String> deleted = owner.store().delete(key);
        if (deleted.isPresent()) {
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

    public Map<Integer, Map<Integer, String>> keyDistribution() {
        Map<Integer, Map<Integer, String>> distribution = new LinkedHashMap<>();
        for (ChordNode node : nodes.values()) {
            distribution.put(node.id(), node.snapshotKeys());
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
    }

    private ChordNode internalPut(int rawKey, String value) {
        int key = identifierRing.normalize(rawKey);
        ChordNode owner = findSuccessor(key);
        owner.store().put(key, value);
        return owner;
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
