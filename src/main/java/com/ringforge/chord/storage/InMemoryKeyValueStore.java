package com.ringforge.chord.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class InMemoryKeyValueStore implements KeyValueStore {
    private final Map<Integer, String> values = new TreeMap<>();

    @Override
    public void put(int key, String value) {
        values.put(key, value);
    }

    @Override
    public Optional<String> get(int key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public Optional<String> delete(int key) {
        return Optional.ofNullable(values.remove(key));
    }

    @Override
    public Map<Integer, String> drain() {
        Map<Integer, String> copy = new LinkedHashMap<>(values);
        values.clear();
        return copy;
    }

    @Override
    public Map<Integer, String> snapshot() {
        return new LinkedHashMap<>(values);
    }
}
