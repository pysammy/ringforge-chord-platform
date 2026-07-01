package com.ringforge.chord.app;

import com.ringforge.chord.service.NodeEndpoint;
import com.ringforge.chord.service.ServiceChordClient;
import com.ringforge.chord.service.ServiceLookupResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ServiceGatewayServer implements AutoCloseable {
    private final URI bootstrapUri;
    private final HttpServer server;

    private ServiceGatewayServer(URI bootstrapUri, HttpServer server) {
        this.bootstrapUri = bootstrapUri;
        this.server = server;
    }

    public static ServiceGatewayServer start(URI bootstrapUri, int port) throws IOException {
        ServiceGatewayServer gateway = new ServiceGatewayServer(
                bootstrapUri,
                HttpServer.create(new InetSocketAddress(port), 0)
        );
        gateway.server.createContext("/api/dht/put", gateway::put);
        gateway.server.createContext("/api/dht/get", gateway::get);
        gateway.server.createContext("/api/cluster/members", gateway::members);
        gateway.server.createContext("/api/cluster/snapshot", gateway::snapshot);
        gateway.server.createContext("/api/cluster/ops-report", gateway::opsReport);
        gateway.server.createContext("/metrics", gateway::metrics);
        gateway.server.setExecutor(Executors.newFixedThreadPool(8));
        gateway.server.start();
        return gateway;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = parseArgs(args);
        int port = Integer.parseInt(options.getOrDefault("port", "8081"));
        URI bootstrapUri = URI.create(options.getOrDefault("bootstrap", "http://localhost:5100"));
        ServiceGatewayServer.start(bootstrapUri, port);
        System.out.println("RingForge service gateway listening on http://localhost:" + port);
        System.out.println("bootstrap=" + bootstrapUri);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void put(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            Map<String, String> query = query(exchange.getRequestURI());
            int key = Integer.parseInt(query.getOrDefault("key", "0"));
            String value = query.getOrDefault("value", "");
            new ServiceChordClient(bootstrapUri).put(key, value, Collections.emptyList());
            sendJson(exchange, 200, "{\"status\":\"ok\",\"message\":\"stored\",\"bootstrap\":\""
                    + escape(bootstrapUri.toString()) + "\"}");
        } catch (RuntimeException error) {
            sendJson(exchange, 500, error(error.getMessage()));
        }
    }

    private void get(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        try {
            int key = Integer.parseInt(query(exchange.getRequestURI()).getOrDefault("key", "0"));
            ServiceLookupResult result = new ServiceChordClient(bootstrapUri).lookup(key, Collections.emptyList());
            sendJson(exchange, 200, lookup(result));
        } catch (RuntimeException error) {
            sendJson(exchange, 500, error(error.getMessage()));
        }
    }

    private void members(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        try {
            List<NodeEndpoint> members = new ServiceChordClient(bootstrapUri).members();
            StringBuilder json = new StringBuilder();
            json.append("{\"members\":[");
            for (int i = 0; i < members.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                endpoint(json, members.get(i));
            }
            json.append("]}");
            sendJson(exchange, 200, json.toString());
        } catch (RuntimeException error) {
            sendJson(exchange, 500, error(error.getMessage()));
        }
    }

    private void snapshot(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        try {
            sendJson(exchange, 200, collect().json());
        } catch (RuntimeException error) {
            sendJson(exchange, 500, error(error.getMessage()));
        }
    }

    private void opsReport(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        try {
            ClusterView view = collect();
            StringBuilder json = new StringBuilder();
            json.append('{');
            field(json, "generatedAt", Instant.now().toString()).append(',');
            field(json, "bootstrap", bootstrapUri.toString()).append(',');
            stringArray(json, "summary", view.summary());
            json.append(',');
            stringArray(json, "recommendedActions", view.recommendedActions());
            json.append(',');
            json.append("\"llmContext\":").append(view.json());
            json.append('}');
            sendJson(exchange, 200, json.toString());
        } catch (RuntimeException error) {
            sendJson(exchange, 500, error(error.getMessage()));
        }
    }

    private void metrics(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        try {
            ClusterView view = collect();
            StringBuilder text = new StringBuilder();
            metric(text, "ringforge_service_members", "Configured members visible from the bootstrap node.", view.memberCount);
            metric(text, "ringforge_service_reachable_nodes", "Members that responded to state requests.", view.reachableCount);
            metric(text, "ringforge_service_primary_keys", "Primary keys currently stored across reachable service nodes.", view.primaryKeyCount);
            metric(text, "ringforge_service_replica_keys", "Replica keys currently stored across reachable service nodes.", view.replicaKeyCount);
            metric(text, "ringforge_service_heartbeat_runs_total", "Total heartbeat scheduler runs reported by reachable nodes.", view.heartbeatRunCount);
            send(exchange, 200, text.toString().getBytes(StandardCharsets.UTF_8), "text/plain");
        } catch (RuntimeException error) {
            sendJson(exchange, 500, error(error.getMessage()));
        }
    }

    private ClusterView collect() {
        List<NodeEndpoint> members = new ServiceChordClient(bootstrapUri).members();
        StringBuilder json = new StringBuilder();
        json.append('{');
        field(json, "bootstrap", bootstrapUri.toString()).append(',');
        json.append("\"memberCount\":").append(members.size()).append(',');
        json.append("\"nodes\":[");

        int reachableCount = 0;
        int primaryKeyCount = 0;
        int replicaKeyCount = 0;
        int heartbeatRunCount = 0;
        List<Integer> unreachable = new ArrayList<>();

        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            NodeEndpoint member = members.get(i);
            json.append('{');
            json.append("\"nodeId\":").append(member.nodeId()).append(',');
            field(json, "uri", member.baseUri().toString()).append(',');
            try {
                ServiceChordClient client = new ServiceChordClient(member.baseUri(), 800);
                String state = client.nodeStateJson();
                String heartbeat = client.heartbeatStatusJson();
                reachableCount++;
                primaryKeyCount += countObjectFields(state, "keys");
                replicaKeyCount += countObjectFields(state, "replicas");
                heartbeatRunCount += extractInt(heartbeat, "runCount");
                json.append("\"reachable\":true,");
                json.append("\"state\":").append(state).append(',');
                json.append("\"heartbeat\":").append(heartbeat);
            } catch (RuntimeException error) {
                unreachable.add(member.nodeId());
                json.append("\"reachable\":false,");
                field(json, "error", error.getMessage());
            }
            json.append('}');
        }
        json.append("],");
        json.append("\"metrics\":{");
        json.append("\"reachableCount\":").append(reachableCount).append(',');
        json.append("\"primaryKeyCount\":").append(primaryKeyCount).append(',');
        json.append("\"replicaKeyCount\":").append(replicaKeyCount).append(',');
        json.append("\"heartbeatRunCount\":").append(heartbeatRunCount).append(',');
        intArray(json, "unreachableNodeIds", unreachable);
        json.append("}}");

        return new ClusterView(members.size(), reachableCount, primaryKeyCount, replicaKeyCount,
                heartbeatRunCount, unreachable, json.toString());
    }

    private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error("method not allowed"));
            return false;
        }
        return true;
    }

    private static void metric(StringBuilder text, String name, String help, int value) {
        text.append("# HELP ").append(name).append(' ').append(help).append('\n');
        text.append("# TYPE ").append(name).append(" gauge\n");
        text.append(name).append(' ').append(value).append('\n');
    }

    private static String lookup(ServiceLookupResult result) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"key\":").append(result.key()).append(',');
        json.append("\"found\":").append(result.found()).append(',');
        field(json, "value", result.value().orElse(null)).append(',');
        json.append("\"responsibleNodeId\":").append(result.responsibleNodeId()).append(',');
        intArray(json, "path", result.path());
        json.append('}');
        return json.toString();
    }

    private static void endpoint(StringBuilder json, NodeEndpoint endpoint) {
        json.append('{');
        json.append("\"nodeId\":").append(endpoint.nodeId()).append(',');
        field(json, "uri", endpoint.baseUri().toString());
        json.append('}');
    }

    private static int countObjectFields(String json, String name) {
        String body = objectBody(json, name);
        if (body.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char current = body.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (current == '{' || current == '[') {
                    depth++;
                } else if (current == '}' || current == ']') {
                    depth--;
                } else if (current == ':' && depth == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String objectBody(String json, String name) {
        String field = "\"" + name + "\":{";
        int start = json.indexOf(field);
        if (start < 0) {
            return "";
        }
        int bodyStart = start + field.length();
        int depth = 1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = bodyStart; i < json.length(); i++) {
            char current = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (current == '{') {
                    depth++;
                } else if (current == '}') {
                    depth--;
                    if (depth == 0) {
                        return json.substring(bodyStart, i);
                    }
                }
            }
        }
        return "";
    }

    private static int extractInt(String json, String name) {
        String field = "\"" + name + "\":";
        int start = json.indexOf(field);
        if (start < 0) {
            return 0;
        }
        int valueStart = start + field.length();
        int valueEnd = valueStart;
        while (valueEnd < json.length() && Character.isDigit(json.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            return 0;
        }
        return Integer.parseInt(json.substring(valueStart, valueEnd));
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --name value arguments");
            }
            options.put(arg.substring(2), args[++i]);
        }
        return options;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String value = equals >= 0 ? pair.substring(equals + 1) : "";
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void stringArray(StringBuilder json, String name, List<String> values) {
        json.append('"').append(escape(name)).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(values.get(i))).append('"');
        }
        json.append(']');
    }

    private static void intArray(StringBuilder json, String name, List<Integer> values) {
        json.append('"').append(escape(name)).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(values.get(i));
        }
        json.append(']');
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        json.append('"').append(escape(name)).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(escape(value)).append('"');
        }
        return json;
    }

    private static String error(String message) {
        return "{\"error\":\"" + escape(message == null ? "unknown error" : message) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void send(HttpExchange exchange, int status, byte[] data, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }

    private static final class ClusterView {
        private final int memberCount;
        private final int reachableCount;
        private final int primaryKeyCount;
        private final int replicaKeyCount;
        private final int heartbeatRunCount;
        private final List<Integer> unreachableNodeIds;
        private final String json;

        private ClusterView(int memberCount, int reachableCount, int primaryKeyCount, int replicaKeyCount,
                            int heartbeatRunCount, List<Integer> unreachableNodeIds, String json) {
            this.memberCount = memberCount;
            this.reachableCount = reachableCount;
            this.primaryKeyCount = primaryKeyCount;
            this.replicaKeyCount = replicaKeyCount;
            this.heartbeatRunCount = heartbeatRunCount;
            this.unreachableNodeIds = unreachableNodeIds;
            this.json = json;
        }

        private String json() {
            return json;
        }

        private List<String> summary() {
            List<String> summary = new ArrayList<>();
            summary.add("Gateway sees " + reachableCount + " reachable node(s) out of " + memberCount + " member(s).");
            summary.add("Reachable nodes hold " + primaryKeyCount + " primary key(s) and " + replicaKeyCount + " replica key(s).");
            if (unreachableNodeIds.isEmpty()) {
                summary.add("No unreachable members were observed during the gateway snapshot.");
            } else {
                summary.add("Unreachable members observed: " + unreachableNodeIds + ".");
            }
            return summary;
        }

        private List<String> recommendedActions() {
            List<String> actions = new ArrayList<>();
            if (unreachableNodeIds.isEmpty()) {
                actions.add("Continue monitoring heartbeat runs, key ownership, and replica counts.");
            } else {
                actions.add("Wait for heartbeat repair or call /node/heartbeat-repair on a reachable member.");
                actions.add("Confirm promoted replicas are readable through /api/dht/get for affected keys.");
            }
            actions.add("Use /metrics for time-series monitoring and /api/cluster/ops-report as the LLM-safe context source.");
            return actions;
        }
    }
}
