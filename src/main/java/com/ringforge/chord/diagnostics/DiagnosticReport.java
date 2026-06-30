package com.ringforge.chord.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DiagnosticReport {
    private final List<DiagnosticFinding> findings;

    public DiagnosticReport(List<DiagnosticFinding> findings) {
        this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
    }

    public List<DiagnosticFinding> findings() {
        return findings;
    }

    public boolean healthy() {
        return findings.stream().noneMatch(finding -> "ERROR".equals(finding.severity()));
    }
}
