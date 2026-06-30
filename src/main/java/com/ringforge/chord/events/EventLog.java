package com.ringforge.chord.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EventLog {
    private final List<RingEvent> events = new ArrayList<>();
    private long nextSequence = 1;

    public synchronized RingEvent record(EventType type, String message) {
        return record(type, message, Collections.emptyMap());
    }

    public synchronized RingEvent record(EventType type, String message, Map<String, String> details) {
        RingEvent event = new RingEvent(nextSequence++, Instant.now(), type, message, details);
        events.add(event);
        return event;
    }

    public synchronized List<RingEvent> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized List<RingEvent> latest(int limit) {
        int boundedLimit = Math.max(0, limit);
        int start = Math.max(0, events.size() - boundedLimit);
        return Collections.unmodifiableList(new ArrayList<>(events.subList(start, events.size())));
    }

    public static Map<String, String> details(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("details must be key/value pairs");
        }
        Map<String, String> details = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            details.put(pairs[i], pairs[i + 1]);
        }
        return details;
    }
}
