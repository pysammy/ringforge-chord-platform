package com.ringforge.chord.service;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
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

    @Test
    void serviceNodeCanJoinThroughBootstrapAndTakeOverKeyRange() throws Exception {
        ServiceChordNode node0 = new ServiceChordNode(0, 8);
        ServiceChordNode node65 = new ServiceChordNode(65, 8);
        ServiceChordNode node110 = new ServiceChordNode(110, 8);
        ServiceChordNode node100 = new ServiceChordNode(100, 8);

        try (ServiceChordNodeServer server0 = ServiceChordNodeServer.start(node0, 0);
             ServiceChordNodeServer server65 = ServiceChordNodeServer.start(node65, 0);
             ServiceChordNodeServer server110 = ServiceChordNodeServer.start(node110, 0);
             ServiceChordNodeServer server100 = ServiceChordNodeServer.start(node100, 0)) {

            List<NodeEndpoint> initialMembers = Arrays.asList(
                    endpoint(0, server0.port()),
                    endpoint(65, server65.port()),
                    endpoint(110, server110.port())
            );
            for (ServiceChordNode node : Arrays.asList(node0, node65, node110)) {
                node.configureCluster(initialMembers);
            }

            ServiceChordClient client0 = new ServiceChordClient(endpoint(0, server0.port()).baseUri());
            ServiceChordClient client100 = new ServiceChordClient(endpoint(100, server100.port()).baseUri());
            ServiceChordClient client110 = new ServiceChordClient(endpoint(110, server110.port()).baseUri());

            client0.put(99, "ninety-nine", Collections.emptyList());
            assertEquals("ninety-nine", client110.getLocal(99).orElseThrow(AssertionError::new));

            node100.joinVia(endpoint(100, server100.port()), endpoint(65, server65.port()));

            ServiceLookupResult result = client0.lookup(99, Collections.emptyList());
            assertTrue(result.found());
            assertEquals("ninety-nine", result.value().orElseThrow(AssertionError::new));
            assertEquals(100, result.responsibleNodeId());
            assertEquals("ninety-nine", client100.getLocal(99).orElseThrow(AssertionError::new));
            assertTrue(client110.getLocal(99).isEmpty());
        }
    }

    @Test
    void heartbeatRepairRemovesFailedNodeFromServiceRouting() throws Exception {
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
            for (ServiceChordNode node : Arrays.asList(node0, node30, node65)) {
                node.configureCluster(members);
            }

            ServiceChordClient client0 = new ServiceChordClient(endpoint(0, server0.port()).baseUri());
            ServiceChordClient client65 = new ServiceChordClient(endpoint(65, server65.port()).baseUri());
            client0.put(45, "survives-routing-repair", Collections.emptyList());
            assertEquals(Arrays.asList(0, 30, 65), client0.lookup(45, Collections.emptyList()).path());

            server30.close();

            List<NodeEndpoint> repairedMembers = client0.repairMembership();
            List<Integer> repairedNodeIds = repairedMembers.stream()
                    .map(NodeEndpoint::nodeId)
                    .collect(Collectors.toList());

            assertEquals(Arrays.asList(0, 65), repairedNodeIds);
            ServiceLookupResult repairedLookup = client0.lookup(45, Collections.emptyList());
            assertTrue(repairedLookup.found());
            assertEquals("survives-routing-repair", repairedLookup.value().orElseThrow(AssertionError::new));
            assertEquals(65, repairedLookup.responsibleNodeId());
            assertEquals(Arrays.asList(0, 65), repairedLookup.path());
            assertEquals(Arrays.asList(0, 65), client65.members().stream()
                    .map(NodeEndpoint::nodeId)
                    .collect(Collectors.toList()));
        }
    }

    @Test
    void serviceRuntimeReplicatesPrimaryWritesToSuccessors() throws Exception {
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
            for (ServiceChordNode node : Arrays.asList(node0, node30, node65)) {
                node.configureCluster(members);
            }

            ServiceChordClient client0 = new ServiceChordClient(endpoint(0, server0.port()).baseUri());
            ServiceChordClient client30 = new ServiceChordClient(endpoint(30, server30.port()).baseUri());
            ServiceChordClient client65 = new ServiceChordClient(endpoint(65, server65.port()).baseUri());

            client0.put(20, "replicated-primary", Collections.emptyList());

            assertEquals("replicated-primary", client30.getLocal(20).orElseThrow(AssertionError::new));
            assertEquals("replicated-primary", client65.getReplica(20).orElseThrow(AssertionError::new));
            assertEquals("replicated-primary", client0.getReplica(20).orElseThrow(AssertionError::new));
        }
    }

    @Test
    void heartbeatRepairPromotesReplicaWhenPrimaryOwnerFails() throws Exception {
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
            for (ServiceChordNode node : Arrays.asList(node0, node30, node65)) {
                node.configureCluster(members);
            }

            ServiceChordClient client0 = new ServiceChordClient(endpoint(0, server0.port()).baseUri());
            ServiceChordClient client65 = new ServiceChordClient(endpoint(65, server65.port()).baseUri());

            client0.put(20, "promoted-after-failure", Collections.emptyList());
            assertEquals(30, client0.lookup(20, Collections.emptyList()).responsibleNodeId());
            assertEquals("promoted-after-failure", client65.getReplica(20).orElseThrow(AssertionError::new));

            server30.close();
            client0.repairMembership();

            ServiceLookupResult repairedLookup = client0.lookup(20, Collections.emptyList());
            assertTrue(repairedLookup.found());
            assertEquals(65, repairedLookup.responsibleNodeId());
            assertEquals("promoted-after-failure", repairedLookup.value().orElseThrow(AssertionError::new));
            assertEquals("promoted-after-failure", client65.getLocal(20).orElseThrow(AssertionError::new));
            assertEquals(Arrays.asList(0, 65), repairedLookup.path());
        }
    }

    @Test
    void backgroundHeartbeatRepairsMembershipAndPromotesReplica() throws Exception {
        ServiceChordNode node0 = new ServiceChordNode(0, 8);
        ServiceChordNode node30 = new ServiceChordNode(30, 8);
        ServiceChordNode node65 = new ServiceChordNode(65, 8);

        try (ServiceChordNodeServer server0 = ServiceChordNodeServer.start(node0, 0, 50);
             ServiceChordNodeServer server30 = ServiceChordNodeServer.start(node30, 0, 50);
             ServiceChordNodeServer server65 = ServiceChordNodeServer.start(node65, 0, 50)) {

            List<NodeEndpoint> members = Arrays.asList(
                    endpoint(0, server0.port()),
                    endpoint(30, server30.port()),
                    endpoint(65, server65.port())
            );
            for (ServiceChordNode node : Arrays.asList(node0, node30, node65)) {
                node.configureCluster(members);
            }

            ServiceChordClient client0 = new ServiceChordClient(endpoint(0, server0.port()).baseUri());
            ServiceChordClient client65 = new ServiceChordClient(endpoint(65, server65.port()).baseUri());

            client0.put(20, "background-promoted", Collections.emptyList());
            assertEquals("background-promoted", client65.getReplica(20).orElseThrow(AssertionError::new));

            server30.close();

            await(() -> memberIds(client0).equals(Arrays.asList(0, 65)));
            await(() -> client65.getLocal(20).isPresent());

            ServiceLookupResult lookup = client0.lookup(20, Collections.emptyList());
            assertTrue(lookup.found());
            assertEquals(65, lookup.responsibleNodeId());
            assertEquals("background-promoted", lookup.value().orElseThrow(AssertionError::new));
            assertEquals(Arrays.asList(0, 65), lookup.path());
        }
    }

    @Test
    void heartbeatStatusEndpointReportsSchedulerActivity() throws Exception {
        ServiceChordNode node0 = new ServiceChordNode(0, 8);

        try (ServiceChordNodeServer server0 = ServiceChordNodeServer.start(node0, 0, 30)) {
            node0.configureCluster(Collections.singletonList(endpoint(0, server0.port())));

            await(() -> {
                String status = read(endpoint(0, server0.port()).baseUri().resolve("/node/heartbeat-status"));
                return status.contains("\"enabled\":true") && !status.contains("\"runCount\":0");
            });

            String status = read(endpoint(0, server0.port()).baseUri().resolve("/node/heartbeat-status"));
            assertTrue(status.contains("\"enabled\":true"));
            assertTrue(status.contains("\"intervalMillis\":30"));
        }
    }

    private static NodeEndpoint endpoint(int nodeId, int port) {
        return new NodeEndpoint(nodeId, URI.create("http://localhost:" + port));
    }

    private static List<Integer> memberIds(ServiceChordClient client) {
        try {
            return client.members().stream()
                    .map(NodeEndpoint::nodeId)
                    .collect(Collectors.toList());
        } catch (RuntimeException error) {
            return Collections.emptyList();
        }
    }

    private static String read(URI uri) {
        try {
            return new String(uri.toURL().openStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "";
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        fail("condition was not met before timeout");
    }
}
