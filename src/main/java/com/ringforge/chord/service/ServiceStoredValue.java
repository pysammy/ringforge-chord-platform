package com.ringforge.chord.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

public final class ServiceStoredValue {
    private static final String PREFIX = "rfv1";

    private final String value;
    private final long version;
    private final long timestampEpochMillis;
    private final int ownerNodeId;

    private ServiceStoredValue(String value, long version, long timestampEpochMillis, int ownerNodeId) {
        this.value = value == null ? "" : value;
        this.version = version;
        this.timestampEpochMillis = timestampEpochMillis;
        this.ownerNodeId = ownerNodeId;
    }

    public static ServiceStoredValue create(String value, long version, int ownerNodeId) {
        return new ServiceStoredValue(value, version, System.currentTimeMillis(), ownerNodeId);
    }

    public static ServiceStoredValue legacy(String value, int ownerNodeId) {
        return new ServiceStoredValue(value, 1L, 0L, ownerNodeId);
    }

    public static Optional<ServiceStoredValue> parse(String stored, int fallbackOwnerNodeId) {
        if (stored == null) {
            return Optional.empty();
        }
        if (!stored.startsWith(PREFIX + "|")) {
            return Optional.of(legacy(stored, fallbackOwnerNodeId));
        }

        String[] parts = stored.split("\\|", 5);
        if (parts.length != 5) {
            return Optional.of(legacy(stored, fallbackOwnerNodeId));
        }
        try {
            long version = Long.parseLong(parts[1]);
            long timestamp = Long.parseLong(parts[2]);
            int owner = Integer.parseInt(parts[3]);
            String value = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
            return Optional.of(new ServiceStoredValue(value, version, timestamp, owner));
        } catch (RuntimeException error) {
            return Optional.of(legacy(stored, fallbackOwnerNodeId));
        }
    }

    public String value() {
        return value;
    }

    public long version() {
        return version;
    }

    public long timestampEpochMillis() {
        return timestampEpochMillis;
    }

    public int ownerNodeId() {
        return ownerNodeId;
    }

    public String timestamp() {
        if (timestampEpochMillis <= 0) {
            return "legacy";
        }
        return Instant.ofEpochMilli(timestampEpochMillis).toString();
    }

    public String encode() {
        String encodedValue = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return PREFIX + "|" + version + "|" + timestampEpochMillis + "|" + ownerNodeId + "|" + encodedValue;
    }
}
