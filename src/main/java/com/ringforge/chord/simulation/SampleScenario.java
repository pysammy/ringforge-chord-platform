package com.ringforge.chord.simulation;

public final class SampleScenario {
    private SampleScenario() {
    }

    public static ChordRing build() {
        ChordRing ring = new ChordRing(8);

        for (int nodeId : new int[]{0, 30, 65, 110, 160, 230}) {
            ring.join(nodeId);
        }

        ring.put(3, "3");
        ring.put(200, "None");
        ring.put(123, "None");
        ring.put(45, "3");
        ring.put(99, "None");
        ring.put(60, "10");
        ring.put(50, "8");
        ring.put(100, "5");
        ring.put(101, "4");
        ring.put(102, "6");
        ring.put(240, "8");
        ring.put(250, "10");

        ring.join(100);
        return ring;
    }
}
