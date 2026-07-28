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
        return userValues(store.snapshot(), nodeId);
    }

    public Map<Integer, String> replicaKeys() {
        return userValues(replicaStore.snapshot(), nodeId);
    }

    public Map<Integer, ServiceStoredValue> localRecords() {
        return records(store.snapshot(), nodeId);
    }

    public Map<Integer, ServiceStoredValue> replicaRecords() {
        return records(replicaStore.snapshot(), nodeId);
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

    public void gossipMembership() {
        List<NodeEndpoint> localMembers = members();
        for (NodeEndpoint member : localMembers) {
            if (member.nodeId() == nodeId) {
                continue;
            }
            try {
                ServiceChordClient client = new ServiceChordClient(member.baseUri());
                List<NodeEndpoint> merged = mergeMembers(localMembers, client.members());
                if (!sameMembers(merged, localMembers)) {
                    configureCluster(merged, true);
                    localMembers = members();
                }
                if (!sameMembers(merged, client.members())) {
                    client.refreshMembers(merged);
                    publish("MEMBERSHIP_GOSSIPED", details("targetNodeId", String.valueOf(member.nodeId()),
                            "memberCount", String.valueOf(merged.size())));
                }
            } catch (RuntimeException ignored) {
                // Heartbeat repair handles nodes that cannot receive gossip.
            }
        }
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
            ServiceStoredValue record = ServiceStoredValue.create(value, nextVersion(key), nodeId);
            store.put(key, record.encode());
            replicatePrimary(key, record);
            publish("KEY_STORED", details("key", String.valueOf(key), "role", "primary",
                    "version", String.valueOf(record.version()), "ownerNodeId", String.valueOf(record.ownerNodeId())));
            return;
        }
        forwardPut(nextHop(key, nextPath), key, value, nextPath);
    }

    public ServiceLookupResult lookup(int rawKey, List<Integer> path) {
        int key = ring.normalize(rawKey);
        List<Integer> nextPath = appendPath(path);
        if (isResponsibleFor(key)) {
            Optional<ServiceStoredValue> value = record(store.get(key), nodeId);
            if (value.isPresent()) {
                repairReplicas(key, value.get());
            }
            publish("LOOKUP_COMPLETED", details("key", String.valueOf(key), "found", String.valueOf(value.isPresent()),
                    "responsibleNodeId", String.valueOf(nodeId), "path", nextPath.toString()));
            return new ServiceLookupResult(key, value.isPresent(), value.map(ServiceStoredValue::value).orElse(null), nodeId, nextPath);
        }
        return forwardLookup(nextHop(key, nextPath), key, nextPath);
    }

    public Optional<String> delete(int rawKey, List<Integer> path) {
        int key = ring.normalize(rawKey);
        List<Integer> nextPath = appendPath(path);
        if (isResponsibleFor(key)) {
            Optional<ServiceStoredValue> deleted = record(store.delete(key), nodeId);
            deleteReplicas(key);
            publish("KEY_DELETED", details("key", String.valueOf(key), "found", String.valueOf(deleted.isPresent()),
                    "responsibleNodeId", String.valueOf(nodeId), "path", nextPath.toString()));
            return deleted.map(ServiceStoredValue::value);
        }
        return forwardDelete(nextHop(key, nextPath), key, nextPath);
    }

    public Optional<String> getLocal(int rawKey) {
        return record(store.get(ring.normalize(rawKey)), nodeId).map(ServiceStoredValue::value);
    }

    public void putReplica(int rawKey, String value) {
        putReplicaRecord(rawKey, ServiceStoredValue.create(value, 1L, nodeId).encode());
    }

    public void putReplicaRecord(int rawKey, String encodedRecord) {
        int key = ring.normalize(rawKey);
        ServiceStoredValue record = record(Optional.ofNullable(encodedRecord), nodeId)
                .orElseGet(() -> ServiceStoredValue.create("", 1L, nodeId));
        replicaStore.put(key, record.encode());
        publish("REPLICA_WRITTEN", details("key", String.valueOf(key), "role", "replica",
                "version", String.valueOf(record.version()), "ownerNodeId", String.valueOf(record.ownerNodeId())));
    }

    public Optional<String> getReplica(int rawKey) {
        return record(replicaStore.get(ring.normalize(rawKey)), nodeId).map(ServiceStoredValue::value);
    }

    public Optional<String> getReplicaRecord(int rawKey) {
        return replicaStore.get(ring.normalize(rawKey));
    }

    public Optional<String> deleteReplica(int rawKey) {
        int key = ring.normalize(rawKey);
        Optional<ServiceStoredValue> deleted = record(replicaStore.delete(key), nodeId);
        publish("REPLICA_DELETED", details("key", String.valueOf(key), "found", String.valueOf(deleted.isPresent())));
        return deleted.map(ServiceStoredValue::value);
    }

    public void acceptPrimaryRecord(int rawKey, String encodedRecord) {
        int key = ring.normalize(rawKey);
        if (!isResponsibleFor(key)) {
            throw new IllegalStateException("Node " + nodeId + " is not responsible for key " + key);
        }
        ServiceStoredValue incoming = record(Optional.ofNullable(encodedRecord), nodeId)
                .orElseGet(() -> ServiceStoredValue.create("", 1L, nodeId));
        long currentVersion = record(store.get(key), nodeId)
                .map(ServiceStoredValue::version)
                .orElse(0L);
        ServiceStoredValue accepted = ServiceStoredValue.create(incoming.value(),
                Math.max(currentVersion, incoming.version()) + 1, nodeId);
        store.put(key, accepted.encode());
        replicaStore.delete(key);
        replicatePrimary(key, accepted);
        publish("KEY_TRANSFERRED_IN", details("key", String.valueOf(key), "ownerNodeId", String.valueOf(nodeId),
                "version", String.valueOf(accepted.version())));
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
                ServiceStoredValue movedRecord = ServiceStoredValue.parse(entry.getValue(), nodeId)
                        .orElseGet(() -> ServiceStoredValue.legacy(entry.getValue(), nodeId));
                NodeEndpoint newOwner = successorOf(key, new ArrayList<>(endpoints.values()));
                try {
                    new ServiceChordClient(newOwner.baseUri()).putPrimaryRecord(key, movedRecord.encode());
                    store.delete(key);
                    publish("KEY_TRANSFERRED_OUT", details("key", String.valueOf(key),
                            "fromNodeId", String.valueOf(nodeId), "toNodeId", String.valueOf(newOwner.nodeId())));
                } catch (RuntimeException ignored) {
                    // Keep the local primary until the new owner confirms transfer.
                }
            }
        }
    }

    private void promoteOwnedReplicas() {
        Map<Integer, String> snapshot = replicaStore.snapshot();
        for (Map.Entry<Integer, String> entry : snapshot.entrySet()) {
            int key = ring.normalize(entry.getKey());
            if (isResponsibleFor(key) && store.get(key).isEmpty()) {
                Optional<ServiceStoredValue> replica = record(replicaStore.delete(key), nodeId);
                if (replica.isPresent()) {
                    ServiceStoredValue promoted = ServiceStoredValue.create(replica.get().value(),
                            replica.get().version() + 1, nodeId);
                    store.put(key, promoted.encode());
                    replicatePrimary(key, promoted);
                    publish("REPLICA_PROMOTED", details("key", String.valueOf(key), "newOwnerId", String.valueOf(nodeId),
                            "version", String.valueOf(promoted.version())));
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

    private Optional<String> forwardDelete(NodeEndpoint endpoint, int key, List<Integer> path) {
        return new ServiceChordClient(endpoint.baseUri()).delete(key, path);
    }

    private ServiceLookupResult forwardLookup(NodeEndpoint endpoint, int key, List<Integer> path) {
        return new ServiceChordClient(endpoint.baseUri()).lookup(key, path);
    }

    private void replicatePrimary(int key, ServiceStoredValue record) {
        for (NodeEndpoint successor : successorsAfter(nodeId, replicaCount())) {
            if (successor.nodeId() != nodeId) {
                new ServiceChordClient(successor.baseUri()).putReplicaRecord(key, record.encode());
            }
        }
    }

    private void repairReplicas(int key, ServiceStoredValue record) {
        for (NodeEndpoint successor : successorsAfter(nodeId, replicaCount())) {
            if (successor.nodeId() == nodeId) {
                continue;
            }
            try {
                Optional<ServiceStoredValue> replica = record(new ServiceChordClient(successor.baseUri()).getReplicaRecord(key), successor.nodeId());
                if (replica.isEmpty() || replica.get().version() < record.version()) {
                    new ServiceChordClient(successor.baseUri()).putReplicaRecord(key, record.encode());
                    publish("READ_REPAIR_APPLIED", details("key", String.valueOf(key), "replicaNodeId",
                            String.valueOf(successor.nodeId()), "version", String.valueOf(record.version())));
                }
            } catch (RuntimeException ignored) {
                // Heartbeat repair handles failed replica nodes.
            }
        }
    }

    private void deleteReplicas(int key) {
        for (NodeEndpoint successor : successorsAfter(nodeId, replicaCount())) {
            if (successor.nodeId() != nodeId) {
                try {
                    new ServiceChordClient(successor.baseUri()).deleteReplica(key);
                } catch (RuntimeException ignored) {
                    // Delete remains best-effort for unreachable replicas.
                }
            }
        }
    }

    private long nextVersion(int key) {
        return record(store.get(key), nodeId)
                .map(value -> value.version() + 1)
                .orElse(1L);
    }

    private static Optional<ServiceStoredValue> record(Optional<String> stored, int fallbackOwnerNodeId) {
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        return ServiceStoredValue.parse(stored.get(), fallbackOwnerNodeId);
    }

    private static Map<Integer, String> userValues(Map<Integer, String> snapshot, int fallbackOwnerNodeId) {
        Map<Integer, String> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : snapshot.entrySet()) {
            ServiceStoredValue.parse(entry.getValue(), fallbackOwnerNodeId)
                    .ifPresent(record -> values.put(entry.getKey(), record.value()));
        }
        return values;
    }

    private static Map<Integer, ServiceStoredValue> records(Map<Integer, String> snapshot, int fallbackOwnerNodeId) {
        Map<Integer, ServiceStoredValue> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : snapshot.entrySet()) {
            ServiceStoredValue.parse(entry.getValue(), fallbackOwnerNodeId)
                    .ifPresent(record -> values.put(entry.getKey(), record));
        }
        return values;
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

    private static List<NodeEndpoint> mergeMembers(List<NodeEndpoint> first, List<NodeEndpoint> second) {
        Map<Integer, NodeEndpoint> merged = new LinkedHashMap<>();
        for (NodeEndpoint member : second) {
            merged.put(member.nodeId(), member);
        }
        for (NodeEndpoint member : first) {
            merged.put(member.nodeId(), member);
        }
        List<NodeEndpoint> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator.comparingInt(NodeEndpoint::nodeId));
        return ordered;
    }

    private static boolean sameMembers(List<NodeEndpoint> first, List<NodeEndpoint> second) {
        if (first.size() != second.size()) {
            return false;
        }
        List<NodeEndpoint> orderedFirst = new ArrayList<>(first);
        List<NodeEndpoint> orderedSecond = new ArrayList<>(second);
        orderedFirst.sort(Comparator.comparingInt(NodeEndpoint::nodeId));
        orderedSecond.sort(Comparator.comparingInt(NodeEndpoint::nodeId));
        for (int i = 0; i < orderedFirst.size(); i++) {
            NodeEndpoint a = orderedFirst.get(i);
            NodeEndpoint b = orderedSecond.get(i);
            if (a.nodeId() != b.nodeId() || !a.baseUri().equals(b.baseUri())) {
                return false;
            }
        }
        return true;
    }

    public static NodeEndpoint endpoint(int nodeId, String uri) {
        return new NodeEndpoint(nodeId, URI.create(uri));
    }
}
