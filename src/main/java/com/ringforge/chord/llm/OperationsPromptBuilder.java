package com.ringforge.chord.llm;

public final class OperationsPromptBuilder {
    private OperationsPromptBuilder() {
    }

    public static String buildIncidentPrompt(String gatewayOpsReportJson) {
        if (gatewayOpsReportJson == null || gatewayOpsReportJson.trim().isEmpty()) {
            throw new IllegalArgumentException("gatewayOpsReportJson must not be empty");
        }
        return String.join("\n",
                "You are assisting an engineer operating RingForge, a Java Chord DHT service.",
                "Use only the supplied deterministic JSON context.",
                "Do not decide key ownership, routing, liveness, or replica placement.",
                "Explain the current health, likely risks, and concrete commands or endpoints to inspect next.",
                "",
                "Context JSON:",
                gatewayOpsReportJson
        );
    }
}
