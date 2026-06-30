package com.ringforge.chord.events;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RingEvent {
    private final long sequence;
    private final Instant timestamp;
    private final EventType type;
    private final String message;
    private final Map<String, String> details;

    public RingEvent(long sequence, Instant timestamp, EventType type, String message, Map<String, String> details) {
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.type = type;
        this.message = message;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public long sequence() {
        return sequence;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public EventType type() {
        return type;
    }

    public String message() {
        return message;
    }

    public Map<String, String> details() {
        return details;
    }
}
