package com.ringforge.chord.events;

import java.util.Map;

public interface ServiceEventPublisher extends AutoCloseable {
    void publish(String type, Map<String, String> details);

    @Override
    default void close() {
    }
}
