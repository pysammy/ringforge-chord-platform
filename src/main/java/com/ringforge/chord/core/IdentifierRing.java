package com.ringforge.chord.core;

/**
 * Utility for circular identifier arithmetic used by Chord.
 */
public final class IdentifierRing {
    private final int bitLength;
    private final int size;

    public IdentifierRing(int bitLength) {
        if (bitLength <= 0 || bitLength > 30) {
            throw new IllegalArgumentException("bitLength must be between 1 and 30");
        }
        this.bitLength = bitLength;
        this.size = 1 << bitLength;
    }

    public int bitLength() {
        return bitLength;
    }

    public int size() {
        return size;
    }

    public int normalize(int id) {
        int normalized = id % size;
        return normalized < 0 ? normalized + size : normalized;
    }

    public int fingerStart(int nodeId, int fingerIndex) {
        if (fingerIndex < 1 || fingerIndex > bitLength) {
            throw new IllegalArgumentException("fingerIndex must be in [1, bitLength]");
        }
        return normalize(nodeId + (1 << (fingerIndex - 1)));
    }

    public int fingerEndExclusive(int nodeId, int fingerIndex) {
        if (fingerIndex < 1 || fingerIndex > bitLength) {
            throw new IllegalArgumentException("fingerIndex must be in [1, bitLength]");
        }
        if (fingerIndex == bitLength) {
            return normalize(nodeId);
        }
        return normalize(nodeId + (1 << fingerIndex));
    }

    /**
     * Returns true when id is in the circular interval (start, end].
     */
    public boolean inOpenClosed(int id, int start, int end) {
        int normalizedId = normalize(id);
        int normalizedStart = normalize(start);
        int normalizedEnd = normalize(end);

        if (normalizedStart == normalizedEnd) {
            return true;
        }
        if (normalizedStart < normalizedEnd) {
            return normalizedId > normalizedStart && normalizedId <= normalizedEnd;
        }
        return normalizedId > normalizedStart || normalizedId <= normalizedEnd;
    }

    /**
     * Returns true when id is in the circular interval (start, end).
     */
    public boolean inOpenOpen(int id, int start, int end) {
        int normalizedId = normalize(id);
        int normalizedStart = normalize(start);
        int normalizedEnd = normalize(end);

        if (normalizedStart == normalizedEnd) {
            return normalizedId != normalizedStart;
        }
        if (normalizedStart < normalizedEnd) {
            return normalizedId > normalizedStart && normalizedId < normalizedEnd;
        }
        return normalizedId > normalizedStart || normalizedId < normalizedEnd;
    }
}
