package com.ringforge.chord.events;

import java.util.Map;

public final class NoopServiceEventPublisher implements ServiceEventPublisher {
    @Override
    public void publish(String type, Map<String, String> details) {
        // Events are optional in local/test mode.
    }
}
