package com.ringforge.chord.service;

import com.ringforge.chord.core.ChordNode;
import com.ringforge.chord.core.FingerEntry;
import com.ringforge.chord.core.FingerTable;
import com.ringforge.chord.core.IdentifierRing;
import com.ringforge.chord.events.NoopServiceEventPublisher;
import com.ringforge.chord.events.ServiceEventPublisher;
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
    private static final int DEFAULT_REPLICATION_FACTOR = 3;

    private final int nodeId;
    private final IdentifierRing ring;
    private final KeyValueStore store;
    private final KeyValueStore replicaStore;
    private final FingerTable fingerTable = new FingerTable();
    private final Map<Integer, NodeEndpoint> endpoints = new LinkedHashMap<>();
    private final ServiceEventPublisher eventPublisher;
    private final int replicationFactor;
    private int predecessorId;
    private int successorId;

    public ServiceChordNode(int nodeId, int bitLength) {
        this(nodeId, bitLength, new InMemoryKeyValueStore(), DEFAULT_REPLICATION_FACTOR);
    }

    public ServiceChordNode(int nodeId, int bitLength, KeyValueStore store) {
        this(nodeId, bitLength, store, DEFAULT_REPLICATION_FACTOR);
    }

    public ServiceChordNode(int nodeId, int bitLength, KeyValueStore store, int replicationFactor) {
        this(nodeId, bitLength, store, new InMemoryKeyValueStore(), replicationFactor, new NoopServiceEventPublisher());
    }

    public ServiceChordNode(int nodeId, int bitLength, KeyValueStore store, int replicationFactor,
                            ServiceEventPublisher eventPublisher) {
        this(nodeId, bitLength, store, new InMemoryKeyValueStore(), replicationFactor, eventPublisher);
    }

    public ServiceChordNode(int nodeId, int bitLength, KeyValueStore store, KeyValueStore replicaStore,
                            int replicationFactor, ServiceEventPublisher eventPublisher) {
        this.nodeId = nodeId;
        this.ring = new IdentifierRing(bitLength);
        this.store = store;
        this.replicaStore = replicaStore == null ? new InMemoryKeyValueStore() : replicaStore;
        this.eventPublisher = eventPublisher == null ? new NoopServiceEventPublisher() : eventPublisher;
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("replicationFactor must be at least 1");
        }
        this.replicationFactor = replicationFactor;
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

    public Map<Integer, String> replicaKeys() {
        return replicaStore.snapshot();
    }

    public synchronized void configureCluster(List<NodeEndpoint> members) {
        configureCluster(members, true);
    }

    public void joinVia(NodeEndpoint selfEndpoint, NodeEndpoint bootstrapEndpoint) {
        List<NodeEndpoint> members = new ServiceChordClient(bootstrapEndpoint.baseUri()).addMember(selfEndpoint);
        configureCluster(members, false);
        propagateMembership(members);
        publish("NODE_JOINED", details("nodeId", String.valueOf(nodeId), "bootstrap", bootstrapEndpoint.baseUri().toString()));
    }

    public synchronized List<NodeEndpoint> members() {
        List<NodeEndpoint> members = new ArrayList<>(endpoints.values());
        members.sort(Comparator.comparingInt(NodeEndpoint::nodeId));
        return Collections.unmodifiableList(members);
    }

    public synchronized List<NodeEndpoint> addMember(NodeEndpoint endpoint) {
        List<NodeEndpoint> members = new ArrayList<>(endpoints.values());
        members.removeIf(member -> member.nodeId() == endpoint.nodeId());
        members.add(endpoint);
        configureCluster(members, false);
        publish("NODE_JOINED", details("nodeId", String.valueOf(endpoint.nodeId()), "uri", endpoint.baseUri().toString()));
        return members();
    }

    public synchronized void replaceMembers(List<NodeEndpoint> members) {
        configureCluster(members, true);
    }

    public synchronized void stabilize() {
        configureCluster(members(), true);
    }

    public synchronized void notify(NodeEndpoint endpoint) {
        List<NodeEndpoint> members = new ArrayList<>(endpoints.values());
        if (members.stream().noneMatch(member -> member.nodeId() == endpoint.nodeId())) {
            members.add(endpoint);
            configureCluster(members, true);
        }
    }

    public List<Integer> repairFailedMembers() {
        List<NodeEndpoint> survivors = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        for (NodeEndpoint member : members()) {
            if (member.nodeId() == nodeId) {
                survivors.add(member);
                continue;
            }
            if (new ServiceChordClient(member.baseUri()).isHealthy()) {
                survivors.add(member);
            } else {
                failed.add(member.nodeId());
            }
        }

        if (!failed.isEmpty()) {
            configureCluster(survivors, true);
            propagateMembership(survivors);
            publish("RING_REPAIRED", details("failedNodeIds", failed.toString(), "memberCount", String.valueOf(survivors.size())));
        }
        return Collections.unmodifiableList(failed);
    }

    private void configureCluster(List<NodeEndpoint> members, boolean rebalanceLocalKeys) {
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

        if (rebalanceLocalKeys) {
            promoteOwnedReplicas();
            rebalanceLocalKeys();
        }
    }

    public void put(int rawKey, String value, List<Integer> path) {
        int key = ring.normalize(rawKey);
        List<Integer> nextPath = appendPath(path);
        if (isResponsibleFor(key)) {
            store.put(key, value);
            replicatePrimary(key, value);
            publish("KEY_STORED", details("key", String.valueOf(key), "role", "primary"));
            return;
        }
        forwardPut(nextHop(key, nextPath), key, value, nextPath);
    }

    public ServiceLookupResult lookup(int rawKey, List<Integer> path) {
        int key = ring.normalize(rawKey);
        List<Integer> nextPath = appendPath(path);
        if (isResponsibleFor(key)) {
            Optional<String> value = store.get(key);
            publish("LOOKUP_COMPLETED", details("key", String.valueOf(key), "found", String.valueOf(value.isPresent()),
                    "responsibleNodeId", String.valueOf(nodeId), "path", nextPath.toString()));
            return new ServiceLookupResult(key, value.isPresent(), value.orElse(null), nodeId, nextPath);
        }
        return forwardLookup(nextHop(key, nextPath), key, nextPath);
    }

    public Optional<String> getLocal(int rawKey) {
        return store.get(ring.normalize(rawKey));
    }

    public void putReplica(int rawKey, String value) {
        int key = ring.normalize(rawKey);
        replicaStore.put(key, value);
        publish("REPLICA_WRITTEN", details("key", String.valueOf(key), "role", "replica"));
    }

    public Optional<String> getReplica(int rawKey) {
        return replicaStore.get(ring.normalize(rawKey));
    }

    private void propagateMembership(List<NodeEndpoint> members) {
        for (NodeEndpoint member : members) {
            try {
                new ServiceChordClient(member.baseUri()).refreshMembers(members);
            } catch (RuntimeException ignored) {
                // Heartbeat repair may race with a process that has just disappeared.
            }
        }
    }

    private void rebalanceLocalKeys() {
        Map<Integer, String> snapshot = store.snapshot();
        for (Map.Entry<Integer, String> entry : snapshot.entrySet()) {
            int key = ring.normalize(entry.getKey());
            if (!isResponsibleFor(key)) {
                Optional<String> moved = store.delete(key);
                if (moved.isPresent()) {
                    put(key, moved.get(), Collections.emptyList());
                }
            }
        }
    }

    private void promoteOwnedReplicas() {
        Map<Integer, String> snapshot = replicaStore.snapshot();
        for (Map.Entry<Integer, String> entry : snapshot.entrySet()) {
            int key = ring.normalize(entry.getKey());
            if (isResponsibleFor(key) && store.get(key).isEmpty()) {
                Optional<String> replica = replicaStore.delete(key);
                if (replica.isPresent()) {
                    store.put(key, replica.get());
                    replicatePrimary(key, replica.get());
                    publish("REPLICA_PROMOTED", details("key", String.valueOf(key), "newOwnerId", String.valueOf(nodeId)));
                }
            }
        }
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

    private void replicatePrimary(int key, String value) {
        for (NodeEndpoint successor : successorsAfter(nodeId, replicaCount())) {
            if (successor.nodeId() != nodeId) {
                new ServiceChordClient(successor.baseUri()).putReplica(key, value);
            }
        }
    }

    private void publish(String type, Map<String, String> details) {
        try {
            eventPublisher.publish(type, details);
        } catch (RuntimeException ignored) {
            // Event publishing must not affect DHT correctness.
        }
    }

    private static Map<String, String> details(String... pairs) {
        Map<String, String> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            details.put(pairs[i], pairs[i + 1]);
        }
        return details;
    }

    private int replicaCount() {
        return Math.min(Math.max(0, replicationFactor - 1), Math.max(0, endpoints.size() - 1));
    }

    private List<NodeEndpoint> successorsAfter(int startNodeId, int count) {
        List<NodeEndpoint> ordered = new ArrayList<>(endpoints.values());
        ordered.sort(Comparator.comparingInt(NodeEndpoint::nodeId));
        List<NodeEndpoint> successors = new ArrayList<>();
        if (ordered.size() <= 1 || count <= 0) {
            return successors;
        }
        int startIndex = 0;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).nodeId() == startNodeId) {
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
