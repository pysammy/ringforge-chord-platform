package com.ringforge.chord.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FingerTable {
    private final List<FingerEntry> entries = new ArrayList<>();

    public void replaceAll(List<FingerEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
    }

    public List<FingerEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public ChordNode getSuccessor(int index) {
        if (index < 1 || index > entries.size()) {
            throw new IllegalArgumentException("finger index out of range: " + index);
        }
        return entries.get(index - 1).successor();
    }
}
