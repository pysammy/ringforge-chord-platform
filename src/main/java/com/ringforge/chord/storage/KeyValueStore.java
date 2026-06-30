package com.ringforge.chord.storage;

import java.util.Map;
import java.util.Optional;

public interface KeyValueStore {
    void put(int key, String value);

    Optional<String> get(int key);

    Optional<String> delete(int key);

    Map<Integer, String> drain();

    Map<Integer, String> snapshot();
}
