package com.ringforge.chord.service;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceChordNodeServerTest {
    @Test
    void independentNodeServicesRoutePutAndLookupOverHttp() throws Exception {
        ServiceChordNode node0 = new ServiceChordNode(0, 8);
        ServiceChordNode node30 = new ServiceChordNode(30, 8);
        ServiceChordNode node65 = new ServiceChordNode(65, 8);

        try (ServiceChordNodeServer server0 = ServiceChordNodeServer.start(node0, 0);
             ServiceChordNodeServer server30 = ServiceChordNodeServer.start(node30, 0);
             ServiceChordNodeServer server65 = ServiceChordNodeServer.start(node65, 0)) {

            List<NodeEndpoint> members = Arrays.asList(
                    endpoint(0, server0.port()),
                    endpoint(30, server30.port()),
                    endpoint(65, server65.port())
            );
            node0.configureCluster(members);
            node30.configureCluster(members);
            node65.configureCluster(members);

            ServiceChordClient client0 = new ServiceChordClient(endpoint(0, server0.port()).baseUri());
            ServiceChordClient client65 = new ServiceChordClient(endpoint(65, server65.port()).baseUri());

            client0.put(45, "forty-five", Collections.emptyList());
            ServiceLookupResult result = client0.lookup(45, Collections.emptyList());

            assertTrue(result.found());
            assertEquals("forty-five", result.value().orElseThrow(AssertionError::new));
            assertEquals(65, result.responsibleNodeId());
            assertEquals(Arrays.asList(0, 30, 65), result.path());
            assertEquals("forty-five", client65.getLocal(45).orElseThrow(AssertionError::new));
        }
    }

    @Test
    void independentNodeServicesUseFingerShortcutForFarLookup() throws Exception {
        ServiceChordNode node0 = new ServiceChordNode(0, 8);
        ServiceChordNode node30 = new ServiceChordNode(30, 8);
        ServiceChordNode node65 = new ServiceChordNode(65, 8);
        ServiceChordNode node110 = new ServiceChordNode(110, 8);
        ServiceChordNode node160 = new ServiceChordNode(160, 8);
        ServiceChordNode node230 = new ServiceChordNode(230, 8);

        try (ServiceChordNodeServer server0 = ServiceChordNodeServer.start(node0, 0);
             ServiceChordNodeServer server30 = ServiceChordNodeServer.start(node30, 0);
             ServiceChordNodeServer server65 = ServiceChordNodeServer.start(node65, 0);
             ServiceChordNodeServer server110 = ServiceChordNodeServer.start(node110, 0);
             ServiceChordNodeServer server160 = ServiceChordNodeServer.start(node160, 0);
             ServiceChordNodeServer server230 = ServiceChordNodeServer.start(node230, 0)) {

            List<NodeEndpoint> members = Arrays.asList(
                    endpoint(0, server0.port()),
                    endpoint(30, server30.port()),
                    endpoint(65, server65.port()),
                    endpoint(110, server110.port()),
                    endpoint(160, server160.port()),
                    endpoint(230, server230.port())
            );
            for (ServiceChordNode node : Arrays.asList(node0, node30, node65, node110, node160, node230)) {
                node.configureCluster(members);
            }

            ServiceChordClient client0 = new ServiceChordClient(endpoint(0, server0.port()).baseUri());
            ServiceChordClient client230 = new ServiceChordClient(endpoint(230, server230.port()).baseUri());

            client0.put(200, "two-hundred", Collections.emptyList());
            ServiceLookupResult result = client0.lookup(200, Collections.emptyList());

            assertTrue(result.found());
            assertEquals("two-hundred", result.value().orElseThrow(AssertionError::new));
            assertEquals(230, result.responsibleNodeId());
            assertEquals(Arrays.asList(0, 160, 230), result.path());
            assertEquals("two-hundred", client230.getLocal(200).orElseThrow(AssertionError::new));
        }
    }

    private static NodeEndpoint endpoint(int nodeId, int port) {
        return new NodeEndpoint(nodeId, URI.create("http://localhost:" + port));
    }
}
