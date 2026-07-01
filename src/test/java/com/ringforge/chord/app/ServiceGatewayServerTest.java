package com.ringforge.chord.app;

import com.ringforge.chord.service.NodeEndpoint;
import com.ringforge.chord.service.ServiceChordNode;
import com.ringforge.chord.service.ServiceChordNodeServer;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceGatewayServerTest {
    @Test
    void gatewayProvidesDhtApiSnapshotsMetricsAndOpsReport() throws Exception {
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

            try (ServiceGatewayServer gateway = ServiceGatewayServer.start(endpoint(0, server0.port()).baseUri(), 0)) {
                URI base = URI.create("http://localhost:" + gateway.port());

                String put = request("POST", base.resolve("/api/dht/put?key=45&value=gateway-value"));
                assertTrue(put.contains("\"status\":\"ok\""));

                String get = request("GET", base.resolve("/api/dht/get?key=45"));
                assertTrue(get.contains("\"found\":true"));
                assertTrue(get.contains("\"value\":\"gateway-value\""));
                assertTrue(get.contains("\"responsibleNodeId\":65"));

                String snapshot = request("GET", base.resolve("/api/cluster/snapshot"));
                assertTrue(snapshot.contains("\"memberCount\":3"));
                assertTrue(snapshot.contains("\"reachableCount\":3"));
                assertTrue(snapshot.contains("\"primaryKeyCount\":1"));
                assertTrue(snapshot.contains("\"replicaKeyCount\":2"));

                String metrics = request("GET", base.resolve("/metrics"));
                assertTrue(metrics.contains("ringforge_service_members 3"));
                assertTrue(metrics.contains("ringforge_service_reachable_nodes 3"));
                assertTrue(metrics.contains("ringforge_service_primary_keys 1"));
                assertTrue(metrics.contains("ringforge_service_replica_keys 2"));

                String opsReport = request("GET", base.resolve("/api/cluster/ops-report"));
                assertTrue(opsReport.contains("\"summary\""));
                assertTrue(opsReport.contains("\"llmContext\""));
            }
        }
    }

    private static NodeEndpoint endpoint(int nodeId, int port) {
        return new NodeEndpoint(nodeId, URI.create("http://localhost:" + port));
    }

    private static String request(String method, URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod(method);
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.getOutputStream().close();
        }
        return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
