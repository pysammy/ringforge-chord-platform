package com.ringforge.chord.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class LookupResult {
    private final int key;
    private final Optional<String> value;
    private final int startNodeId;
    private final int responsibleNodeId;
    private final List<Integer> path;
    private final long latencyNanos;

    public LookupResult(int key, Optional<String> value, int startNodeId, int responsibleNodeId,
                        List<Integer> path, long latencyNanos) {
        this.key = key;
        this.value = value;
        this.startNodeId = startNodeId;
        this.responsibleNodeId = responsibleNodeId;
        this.path = Collections.unmodifiableList(new ArrayList<>(path));
        this.latencyNanos = latencyNanos;
    }

    public int key() {
        return key;
    }

    public Optional<String> value() {
        return value;
    }

    public boolean found() {
        return value.isPresent();
    }

    public int startNodeId() {
        return startNodeId;
    }

    public int responsibleNodeId() {
        return responsibleNodeId;
    }

    public List<Integer> path() {
        return path;
    }

    public int hopCount() {
        return Math.max(0, path.size() - 1);
    }

    public long latencyNanos() {
        return latencyNanos;
    }

    public double latencyMillis() {
        return latencyNanos / 1_000_000.0;
    }
}
