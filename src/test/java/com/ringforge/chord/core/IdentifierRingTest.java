package com.ringforge.chord.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentifierRingTest {
    @Test
    void openClosedRangeHandlesNormalIntervals() {
        IdentifierRing ring = new IdentifierRing(8);

        assertTrue(ring.inOpenClosed(45, 30, 65));
        assertTrue(ring.inOpenClosed(65, 30, 65));
        assertFalse(ring.inOpenClosed(30, 30, 65));
        assertFalse(ring.inOpenClosed(66, 30, 65));
    }

    @Test
    void openClosedRangeHandlesWraparoundIntervals() {
        IdentifierRing ring = new IdentifierRing(8);

        assertTrue(ring.inOpenClosed(240, 230, 0));
        assertTrue(ring.inOpenClosed(0, 230, 0));
        assertTrue(ring.inOpenClosed(3, 230, 30));
        assertFalse(ring.inOpenClosed(160, 230, 0));
    }

    @Test
    void openOpenRangeExcludesBothEnds() {
        IdentifierRing ring = new IdentifierRing(8);

        assertTrue(ring.inOpenOpen(100, 65, 110));
        assertFalse(ring.inOpenOpen(65, 65, 110));
        assertFalse(ring.inOpenOpen(110, 65, 110));
    }
}
