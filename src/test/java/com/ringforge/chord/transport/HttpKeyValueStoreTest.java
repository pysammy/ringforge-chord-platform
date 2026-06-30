package com.ringforge.chord.transport;

import com.ringforge.chord.core.LookupResult;
import com.ringforge.chord.simulation.ChordRing;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpKeyValueStoreTest {
    @Test
    void remoteStoreSupportsKeyValueOperations() throws Exception {
        try (NodeStorageServer server = NodeStorageServer.start(0)) {
            HttpKeyValueStore store = new HttpKeyValueStore(URI.create("http://localhost:" + server.port()));

            store.put(42, "remote-value");

            assertEquals("remote-value", store.get(42).orElseThrow(AssertionError::new));
            assertTrue(store.snapshot().containsKey(42));
            assertEquals("remote-value", store.delete(42).orElseThrow(AssertionError::new));
            assertFalse(store.get(42).isPresent());
        }
    }

    @Test
    void remoteStoreDrainReturnsValuesAndClearsServer() throws Exception {
        try (NodeStorageServer server = NodeStorageServer.start(0)) {
            HttpKeyValueStore store = new HttpKeyValueStore(URI.create("http://localhost:" + server.port()));
            store.put(1, "one");
            store.put(2, "two");

            Map<Integer, String> drained = store.drain();

            assertEquals("one", drained.get(1));
            assertEquals("two", drained.get(2));
            assertTrue(store.snapshot().isEmpty());
        }
    }

    @Test
    void chordRingCanPlaceAndLookupKeysUsingRemoteNodeStores() throws Exception {
        try (NodeStorageServer node0 = NodeStorageServer.start(0);
             NodeStorageServer node30 = NodeStorageServer.start(0);
             NodeStorageServer node65 = NodeStorageServer.start(0)) {

            ChordRing ring = new ChordRing(8);
            ring.join(0, new HttpKeyValueStore(URI.create("http://localhost:" + node0.port())));
            ring.join(30, new HttpKeyValueStore(URI.create("http://localhost:" + node30.port())));
            ring.join(65, new HttpKeyValueStore(URI.create("http://localhost:" + node65.port())));

            ring.put(45, "stored-remotely");

            LookupResult result = ring.lookup(0, 45);
            assertTrue(result.found());
            assertEquals("stored-remotely", result.value().orElseThrow(AssertionError::new));
            assertEquals(65, result.responsibleNodeId());
            assertEquals("stored-remotely", new HttpKeyValueStore(URI.create("http://localhost:" + node65.port()))
                    .get(45).orElseThrow(AssertionError::new));
            assertTrue(ring.diagnostics().healthy());
        }
    }
}
