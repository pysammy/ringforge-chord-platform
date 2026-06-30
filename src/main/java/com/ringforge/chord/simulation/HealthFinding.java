package com.ringforge.chord.simulation;

public final class HealthFinding {
    private final String severity;
    private final String message;

    public HealthFinding(String severity, String message) {
        this.severity = severity;
        this.message = message;
    }

    public String severity() {
        return severity;
    }

    public String message() {
        return message;
    }
}
