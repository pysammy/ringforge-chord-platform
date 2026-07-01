package com.ringforge.chord.simulation;

import com.ringforge.chord.core.ChordNode;
import com.ringforge.chord.core.FingerEntry;
import com.ringforge.chord.core.LookupResult;
import com.ringforge.chord.events.EventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

    @Test
    void everyPrimaryKeyHasExpectedSuccessorReplicas() {
        ChordRing ring = SampleScenario.build();
        int expectedReplicas = 2;

        for (Map.Entry<Integer, Map<Integer, String>> ownerEntry : ring.keyDistribution().entrySet()) {
            for (Integer key : ownerEntry.getValue().keySet()) {
                int actualReplicas = 0;
                for (Map<Integer, String> replicas : ring.replicaDistribution().values()) {
                    if (replicas.containsKey(key)) {
                        actualReplicas++;
                    }
                }
                assertEquals(expectedReplicas, actualReplicas, "replica count for key " + key);
            }
        }

        assertTrue(ring.diagnostics().healthy());
    }

    @Test
    void crashPromotesReplicasAndPreservesLookups() {
        ChordRing ring = SampleScenario.build();

        ring.crash(65);

        assertFalse(ring.activeNodeIds().contains(65));
        assertTrue(ring.failedNodeIds().contains(65));
        assertEquals("3", ring.keyDistribution().get(100).get(45));
        assertEquals("8", ring.keyDistribution().get(100).get(50));
        assertEquals("10", ring.keyDistribution().get(100).get(60));

        LookupResult lookup = ring.lookup(0, 45);
        assertTrue(lookup.found());
        assertEquals("3", lookup.value().orElseThrow(AssertionError::new));
        assertEquals(100, lookup.responsibleNodeId());
        assertFalse(lookup.path().contains(65));
        assertTrue(ring.diagnostics().healthy());
    }

    @Test
    void benchmarkMeasuresExpectedLookupPerformance() {
        ChordRing ring = SampleScenario.build();

        com.ringforge.chord.metrics.BenchmarkReport report = ring.benchmark();

        assertEquals(84, report.lookupCount());
        assertEquals(7, report.nodeCount());
        assertEquals(12, report.keyCount());
        assertTrue(report.maxHops() <= 4, "unexpectedly high max hop count: " + report.maxHops());
        assertTrue(report.healthy());
    }

    @Test
    void deterministicChurnPreservesOwnershipRoutingAndReads() {
        ChordRing ring = new ChordRing(8);
        for (Integer nodeId : List.of(0, 30, 65, 100, 160, 230)) {
            ring.join(nodeId);
        }

        Map<Integer, String> expected = new HashMap<>();
        for (int key = 0; key < 40; key++) {
            int normalizedKey = (key * 7) % 256;
            String value = "value-" + normalizedKey;
            ring.put(normalizedKey, value);
            expected.put(normalizedKey, value);
        }

        Random random = new Random(42);
        List<Integer> candidateNodes = List.of(0, 30, 65, 90, 100, 130, 160, 200, 230);
        for (int step = 0; step < 30; step++) {
            int action = random.nextInt(3);
            if (action == 0 && ring.activeNodeIds().size() < 8) {
                int candidate = candidateNodes.get(random.nextInt(candidateNodes.size()));
                if (!ring.activeNodeIds().contains(candidate)) {
                    ring.join(candidate);
                }
            } else if (action == 1 && ring.activeNodeIds().size() > 3) {
                List<Integer> active = new ArrayList<>(ring.activeNodeIds());
                ring.leave(active.get(random.nextInt(active.size())));
            } else {
                int key = random.nextInt(256);
                String value = "step-" + step + "-key-" + key;
                ring.put(key, value);
                expected.put(key, value);
            }
            ring.repair();
            assertTrue(ring.healthReport().healthy(), "health failed at churn step " + step);
            assertTrue(ring.diagnostics().healthy(), "diagnostics failed at churn step " + step);
        }

        int startNode = ring.activeNodeIds().iterator().next();
        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            LookupResult result = ring.lookup(startNode, entry.getKey());
            assertTrue(result.found(), "missing key " + entry.getKey());
            assertEquals(entry.getValue(), result.value().orElseThrow(AssertionError::new));
        }
    }
}
