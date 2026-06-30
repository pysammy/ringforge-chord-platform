package com.ringforge.chord.core;

import com.ringforge.chord.storage.InMemoryKeyValueStore;
import com.ringforge.chord.storage.KeyValueStore;

import java.util.Map;

public final class ChordNode {
    private final int id;
    private final FingerTable fingerTable = new FingerTable();
    private final KeyValueStore store;
    private ChordNode predecessor;
    private ChordNode successor;

    public ChordNode(int id) {
        this(id, new InMemoryKeyValueStore());
    }

    public ChordNode(int id, KeyValueStore store) {
        this.id = id;
        this.store = store;
    }

    public int id() {
        return id;
    }

    public ChordNode predecessor() {
        return predecessor;
    }

    public void setPredecessor(ChordNode predecessor) {
        this.predecessor = predecessor;
    }

    public ChordNode successor() {
        return successor;
    }

    public void setSuccessor(ChordNode successor) {
        this.successor = successor;
    }

    public FingerTable fingerTable() {
        return fingerTable;
    }

    public KeyValueStore store() {
        return store;
    }

    public boolean isResponsibleFor(int key, IdentifierRing ring) {
        if (predecessor == null) {
            return true;
        }
        return ring.inOpenClosed(key, predecessor.id(), id);
    }

    public ChordNode closestPrecedingFinger(int key, IdentifierRing ring) {
        for (int i = fingerTable.entries().size() - 1; i >= 0; i--) {
            ChordNode candidate = fingerTable.entries().get(i).successor();
            if (candidate != null && candidate.id() != id && ring.inOpenOpen(candidate.id(), id, key)) {
                return candidate;
            }
        }
        return this;
    }

    public Map<Integer, String> snapshotKeys() {
        return store.snapshot();
    }
}
