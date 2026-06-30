package com.ringforge.chord.simulation;

import com.ringforge.chord.core.ChordNode;
import com.ringforge.chord.core.FingerEntry;
import com.ringforge.chord.core.IdentifierRing;
import com.ringforge.chord.core.LookupResult;

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

    public ChordRing(int bitLength) {
        this.identifierRing = new IdentifierRing(bitLength);
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
        for (Map.Entry<Integer, String> entry : movedKeys.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
        rebalanceKeys();
        return Optional.of(leaving);
    }

    public void put(int rawKey, String value) {
        ensureNotEmpty();
        int key = identifierRing.normalize(rawKey);
        findSuccessor(key).store().put(key, value);
    }

    public Optional<String> delete(int rawKey) {
        ensureNotEmpty();
        int key = identifierRing.normalize(rawKey);
        return findSuccessor(key).store().delete(key);
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
                return new LookupResult(key, value, startNodeId, current.id(), path, System.nanoTime() - startedAt);
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
        return new LookupResult(key, value, startNodeId, responsible.id(), path, System.nanoTime() - startedAt);
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

    public void repair() {
        rebuildTopology();
        rebalanceKeys();
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

        Map<Integer, String> allKeys = new TreeMap<>();
        for (ChordNode node : nodes.values()) {
            allKeys.putAll(node.store().drain());
        }
        for (Map.Entry<Integer, String> entry : allKeys.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    private void ensureNotEmpty() {
        if (nodes.isEmpty()) {
            throw new IllegalStateException("Ring has no active nodes");
        }
    }
}
