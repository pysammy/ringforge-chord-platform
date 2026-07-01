package com.ringforge.chord.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationsPromptBuilderTest {
    @Test
    void promptKeepsLlmOutsideCorrectnessPath() {
        String prompt = OperationsPromptBuilder.buildIncidentPrompt("{\"summary\":[\"healthy\"],\"llmContext\":{}}");

        assertTrue(prompt.contains("Use only the supplied deterministic JSON context."));
        assertTrue(prompt.contains("Do not decide key ownership, routing, liveness, or replica placement."));
        assertTrue(prompt.contains("\"summary\""));
    }

    @Test
    void emptyContextIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OperationsPromptBuilder.buildIncidentPrompt(""));
    }
}
