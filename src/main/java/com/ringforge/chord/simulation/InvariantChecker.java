package com.ringforge.chord.simulation;

import com.ringforge.chord.core.ChordNode;
import com.ringforge.chord.core.FingerEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class InvariantChecker {
    private final ChordRing ring;

    public InvariantChecker(ChordRing ring) {
        this.ring = ring;
    }

    public HealthReport check() {
        List<HealthFinding> findings = new ArrayList<>();
        Set<Integer> activeIds = ring.activeNodeIds();

        if (activeIds.isEmpty()) {
            findings.add(new HealthFinding("WARN", "Ring has no active nodes."));
            return new HealthReport(findings);
        }

        for (ChordNode node : ring.nodes()) {
            if (node.successor() == null || !activeIds.contains(node.successor().id())) {
                findings.add(new HealthFinding("ERROR", "Node " + node.id() + " has invalid successor."));
            }
            if (node.predecessor() == null || !activeIds.contains(node.predecessor().id())) {
                findings.add(new HealthFinding("ERROR", "Node " + node.id() + " has invalid predecessor."));
            }
            if (node.successor() != null && node.successor().predecessor() != node) {
                findings.add(new HealthFinding("ERROR", "Node " + node.id() + " successor/predecessor links disagree."));
            }
            for (FingerEntry entry : node.fingerTable().entries()) {
                if (entry.successor() == null || !activeIds.contains(entry.successor().id())) {
                    findings.add(new HealthFinding("ERROR", "Node " + node.id() + " finger " + entry.index()
                            + " points to an inactive node."));
                }
            }
            for (Integer key : node.snapshotKeys().keySet()) {
                ChordNode responsible = ring.findSuccessor(key);
                if (responsible == null || responsible.id() != node.id()) {
                    findings.add(new HealthFinding("ERROR", "Key " + key + " is stored on node " + node.id()
                            + " but belongs on node " + (responsible == null ? "none" : responsible.id()) + "."));
                }
            }
        }

        return new HealthReport(findings);
    }
}
