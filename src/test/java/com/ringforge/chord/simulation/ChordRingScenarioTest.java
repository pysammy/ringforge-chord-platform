package com.ringforge.chord.simulation;

import com.ringforge.chord.core.ChordNode;
import com.ringforge.chord.core.FingerEntry;
import com.ringforge.chord.core.LookupResult;
import com.ringforge.chord.events.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChordRingScenarioTest {
    @Test
    void sampleScenarioMigratesKeysToJoiningNode100() {
        ChordRing ring = SampleScenario.build();

        Map<Integer, Map<Integer, String>> distribution = ring.keyDistribution();

        assertEquals("None", distribution.get(100).get(99));
        assertEquals("5", distribution.get(100).get(100));
        assertFalse(distribution.get(110).containsKey(99));
        assertFalse(distribution.get(110).containsKey(100));
        assertTrue(ring.healthReport().healthy());
    }

    @Test
    void lookupReturnsStructuredPathAndResponsibleNode() {
        ChordRing ring = SampleScenario.build();

        LookupResult result = ring.lookup(65, 100);

        assertTrue(result.found());
        assertEquals("5", result.value().orElseThrow(AssertionError::new));
        assertEquals(100, result.responsibleNodeId());
        assertEquals(65, result.startNodeId());
        assertTrue(result.hopCount() <= 2, "lookup should not loop through the ring");
    }

    @Test
    void leavingNodeDoesNotRemainInAnyActiveFingerTable() {
        ChordRing ring = SampleScenario.build();

        ring.leave(65);

        assertTrue(ring.healthReport().healthy());
        assertFalse(ring.activeNodeIds().contains(65));
        assertEquals("3", ring.keyDistribution().get(100).get(45));
        assertEquals("8", ring.keyDistribution().get(100).get(50));
        assertEquals("10", ring.keyDistribution().get(100).get(60));

        for (ChordNode node : ring.nodes()) {
            for (FingerEntry entry : node.fingerTable().entries()) {
                assertFalse(entry.successor().id() == 65,
                        "node " + node.id() + " finger " + entry.index() + " still points to 65");
            }
        }

        LookupResult lookup = ring.lookup(0, 99);
        assertFalse(lookup.path().contains(65));
        assertEquals(100, lookup.responsibleNodeId());
    }

    @Test
    void duplicateNodeIdsAreRejected() {
        ChordRing ring = new ChordRing(8);
        ring.join(30);

        assertThrows(IllegalArgumentException.class, () -> ring.join(30));
    }

    @Test
    void ringOperationsProduceStructuredEvents() {
        ChordRing ring = SampleScenario.build();

        ring.lookup(0, 99);
        ring.leave(65);

        List<EventType> eventTypes = ring.events().stream()
                .map(event -> event.type())
                .collect(java.util.stream.Collectors.toList());

        assertTrue(eventTypes.contains(EventType.RING_CREATED));
        assertTrue(eventTypes.contains(EventType.NODE_JOINED));
        assertTrue(eventTypes.contains(EventType.KEY_STORED));
        assertTrue(eventTypes.contains(EventType.KEY_MIGRATED));
        assertTrue(eventTypes.contains(EventType.LOOKUP_COMPLETED));
        assertTrue(eventTypes.contains(EventType.NODE_LEFT));
    }
}
