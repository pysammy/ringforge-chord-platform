package com.ringforge.chord.core;

public final class FingerEntry {
    private final int index;
    private final int start;
    private final int endExclusive;
    private final ChordNode successor;

    public FingerEntry(int index, int start, int endExclusive, ChordNode successor) {
        this.index = index;
        this.start = start;
        this.endExclusive = endExclusive;
        this.successor = successor;
    }

    public int index() {
        return index;
    }

    public int start() {
        return start;
    }

    public int endExclusive() {
        return endExclusive;
    }

    public ChordNode successor() {
        return successor;
    }
}
