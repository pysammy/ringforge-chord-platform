package com.ringforge.chord.simulation;

import com.ringforge.chord.core.ChordNode;
import com.ringforge.chord.core.FingerEntry;
import com.ringforge.chord.core.LookupResult;

import java.util.Map;

public final class DemoRunner {
    private DemoRunner() {
    }

    public static void main(String[] args) {
        ChordRing ring = SampleScenario.build();
        printRing(ring);
        for (int key : new int[]{3, 200, 123, 45, 99, 60, 50, 100, 101, 102, 240, 250}) {
            LookupResult result = ring.lookup(0, key);
            System.out.println("lookup key=" + key + " start=0 path=" + result.path()
                    + " responsible=" + result.responsibleNodeId()
                    + " value=" + result.value().orElse("<missing>"));
        }

        ring.leave(65);
        System.out.println("\nAfter node 65 leaves:");
        printRing(ring);
        System.out.println("healthy=" + ring.healthReport().healthy());
    }

    private static void printRing(ChordRing ring) {
        System.out.println("Nodes: " + ring.activeNodeIds());
        for (ChordNode node : ring.nodes()) {
            System.out.println("Node " + node.id() + " pred=" + node.predecessor().id()
                    + " succ=" + node.successor().id() + " keys=" + node.snapshotKeys());
            for (FingerEntry entry : node.fingerTable().entries()) {
                System.out.println("  k=" + entry.index() + " [" + entry.start() + ", "
                        + entry.endExclusive() + ") -> " + entry.successor().id());
            }
        }
        for (Map.Entry<Integer, Map<Integer, String>> entry : ring.keyDistribution().entrySet()) {
            System.out.println("keys@" + entry.getKey() + "=" + entry.getValue());
        }
    }
}
