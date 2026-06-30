package com.ringforge.chord.service;

import com.ringforge.chord.core.ChordNode;
import com.ringforge.chord.core.FingerEntry;
import com.ringforge.chord.core.FingerTable;
import com.ringforge.chord.core.IdentifierRing;
import com.ringforge.chord.storage.InMemoryKeyValueStore;
import com.ringforge.chord.storage.KeyValueStore;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ServiceChordNode {
    private final int nodeId;
    private final IdentifierRing ring;
    private final KeyValueStore store;
    private final FingerTable fingerTable = new FingerTable();
    private final Map<Integer, NodeEndpoint> endpoints = new LinkedHashMap<>();
    private int predecessorId;
    private int successorId;

    public ServiceChordNode(int nodeId, int bitLength) {
        this(nodeId, bitLength, new InMemoryKeyValueStore());
    }

    public ServiceChordNode(int nodeId, int bitLength, KeyValueStore store) {
        this.nodeId = nodeId;
        this.ring = new IdentifierRing(bitLength);
        this.store = store;
        this.predecessorId = nodeId;
        this.successorId = nodeId;
    }

    public int nodeId() {
        return nodeId;
    }

    public int predecessorId() {
        return predecessorId;
    }

    public int successorId() {
        return successorId;
    }

    public FingerTable fingerTable() {
        return fingerTable;
    }

    public Map<Integer, String> localKeys() {
        return store.snapshot();
    }

    public void configureCluster(List<NodeEndpoint> members) {
        if (members.stream().noneMatch(member -> member.nodeId() == nodeId)) {
            throw new IllegalArgumentException("Member list does not contain this node: " + nodeId);
        }

        endpoints.clear();
        List<NodeEndpoint> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparingInt(NodeEndpoint::nodeId));
        for (NodeEndpoint member : ordered) {
            endpoints.put(member.nodeId(), member);
        }

        int selfIndex = 0;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).nodeId() == nodeId) {
                selfIndex = i;
                break;
            }
        }

        predecessorId = ordered.get((selfIndex - 1 + ordered.size()) % ordered.size()).nodeId();
        successorId = ordered.get((selfIndex + 1) % ordered.size()).nodeId();

        List<FingerEntry> entries = new ArrayList<>();
        for (int finger = 1; finger <= ring.bitLength(); finger++) {
            int start = ring.fingerStart(nodeId, finger);
            int end = ring.fingerEndExclusive(nodeId, finger);
            int successor = successorOf(start, ordered).nodeId();
            entries.add(new FingerEntry(finger, start, end, new ChordNode(successor)));
        }
        fingerTable.replaceAll(entries);
    }

    public void put(int rawKey, String value, List<Integer> path) {
        int key = ring.normalize(rawKey);
        List<Integer> nextPath = appendPath(path);
        if (isResponsibleFor(key)) {
            store.put(key, value);
            return;
        }
        forwardPut(nextHop(key, nextPath), key, value, nextPath);
    }

    public ServiceLookupResult lookup(int rawKey, List<Integer> path) {
        int key = ring.normalize(rawKey);
        List<Integer> nextPath = appendPath(path);
        if (isResponsibleFor(key)) {
            Optional<String> value = store.get(key);
            return new ServiceLookupResult(key, value.isPresent(), value.orElse(null), nodeId, nextPath);
        }
        return forwardLookup(nextHop(key, nextPath), key, nextPath);
    }

    public Optional<String> getLocal(int rawKey) {
        return store.get(ring.normalize(rawKey));
    }

    private boolean isResponsibleFor(int key) {
        return ring.inOpenClosed(key, predecessorId, nodeId);
    }

    private NodeEndpoint nextHop(int key, List<Integer> path) {
        Set<Integer> visited = new LinkedHashSet<>(path);
        List<FingerEntry> fingers = fingerTable.entries();
        for (int i = fingers.size() - 1; i >= 0; i--) {
            int candidateId = fingers.get(i).successor().id();
            if (candidateId != nodeId && !visited.contains(candidateId) && ring.inOpenOpen(candidateId, nodeId, key)) {
                return endpoint(candidateId);
            }
        }
        if (!visited.contains(successorId)) {
            return endpoint(successorId);
        }
        return endpoint(successorOf(key, new ArrayList<>(endpoints.values())).nodeId());
    }

    private List<Integer> appendPath(List<Integer> path) {
        List<Integer> nextPath = new ArrayList<>(path == null ? Collections.emptyList() : path);
        if (nextPath.isEmpty() || nextPath.get(nextPath.size() - 1) != nodeId) {
            nextPath.add(nodeId);
        }
        if (nextPath.size() > Math.max(4, endpoints.size() + ring.bitLength() + 2)) {
            throw new IllegalStateException("Lookup exceeded maximum path length: " + nextPath);
        }
        return nextPath;
    }

    private void forwardPut(NodeEndpoint endpoint, int key, String value, List<Integer> path) {
        new ServiceChordClient(endpoint.baseUri()).put(key, value, path);
    }

    private ServiceLookupResult forwardLookup(NodeEndpoint endpoint, int key, List<Integer> path) {
        return new ServiceChordClient(endpoint.baseUri()).lookup(key, path);
    }

    private NodeEndpoint endpoint(int id) {
        NodeEndpoint endpoint = endpoints.get(id);
        if (endpoint == null) {
            throw new IllegalStateException("Unknown endpoint for node " + id);
        }
        return endpoint;
    }

    private NodeEndpoint successorOf(int id, List<NodeEndpoint> ordered) {
        for (NodeEndpoint member : ordered) {
            if (member.nodeId() >= id) {
                return member;
            }
        }
        return ordered.get(0);
    }

    public static NodeEndpoint endpoint(int nodeId, String uri) {
        return new NodeEndpoint(nodeId, URI.create(uri));
    }
}
