package com.ringforge.chord.diagnostics;

public final class DiagnosticFinding {
    private final String severity;
    private final String category;
    private final String message;
    private final String recommendation;

    public DiagnosticFinding(String severity, String category, String message, String recommendation) {
        this.severity = severity;
        this.category = category;
        this.message = message;
        this.recommendation = recommendation;
    }

    public String severity() {
        return severity;
    }

    public String category() {
        return category;
    }

    public String message() {
        return message;
    }

    public String recommendation() {
        return recommendation;
    }
}
