package com.ringforge.chord.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class ServiceLookupResult {
    private final int key;
    private final boolean found;
    private final String value;
    private final int responsibleNodeId;
    private final List<Integer> path;

    public ServiceLookupResult(int key, boolean found, String value, int responsibleNodeId, List<Integer> path) {
        this.key = key;
        this.found = found;
        this.value = value;
        this.responsibleNodeId = responsibleNodeId;
        this.path = Collections.unmodifiableList(new ArrayList<>(path));
    }

    public int key() {
        return key;
    }

    public boolean found() {
        return found;
    }

    public Optional<String> value() {
        return Optional.ofNullable(value);
    }

    public int responsibleNodeId() {
        return responsibleNodeId;
    }

    public List<Integer> path() {
        return path;
    }
}
