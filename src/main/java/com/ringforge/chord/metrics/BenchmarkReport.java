package com.ringforge.chord.metrics;

public final class BenchmarkReport {
    private final int lookupCount;
    private final int nodeCount;
    private final int keyCount;
    private final double averageHops;
    private final int maxHops;
    private final boolean healthy;

    public BenchmarkReport(int lookupCount, int nodeCount, int keyCount, double averageHops, int maxHops, boolean healthy) {
        this.lookupCount = lookupCount;
        this.nodeCount = nodeCount;
        this.keyCount = keyCount;
        this.averageHops = averageHops;
        this.maxHops = maxHops;
        this.healthy = healthy;
    }

    public int lookupCount() {
        return lookupCount;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int keyCount() {
        return keyCount;
    }

    public double averageHops() {
        return averageHops;
    }

    public int maxHops() {
        return maxHops;
    }

    public boolean healthy() {
        return healthy;
    }
}
