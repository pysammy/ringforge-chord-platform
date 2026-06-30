package com.ringforge.chord.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HealthReport {
    private final List<HealthFinding> findings;

    public HealthReport(List<HealthFinding> findings) {
        this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
    }

    public List<HealthFinding> findings() {
        return findings;
    }

    public boolean healthy() {
        return findings.isEmpty();
    }
}
