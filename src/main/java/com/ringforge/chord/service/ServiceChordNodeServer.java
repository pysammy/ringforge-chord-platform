package com.ringforge.chord.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ServiceChordNodeServer implements AutoCloseable {
    private final ServiceChordNode node;
    private final HttpServer server;

    private ServiceChordNodeServer(ServiceChordNode node, HttpServer server) {
        this.node = node;
        this.server = server;
    }

    public static ServiceChordNodeServer start(ServiceChordNode node, int port) throws IOException {
        ServiceChordNodeServer nodeServer = new ServiceChordNodeServer(node, HttpServer.create(new InetSocketAddress(port), 0));
        nodeServer.server.createContext("/node/state", nodeServer::state);
        nodeServer.server.createContext("/node/members", nodeServer::members);
        nodeServer.server.createContext("/node/join", nodeServer::join);
        nodeServer.server.createContext("/node/stabilize", nodeServer::stabilize);
        nodeServer.server.createContext("/node/heartbeat-repair", nodeServer::heartbeatRepair);
        nodeServer.server.createContext("/node/notify", nodeServer::notify);
        nodeServer.server.createContext("/node/successor", nodeServer::successor);
        nodeServer.server.createContext("/node/predecessor", nodeServer::predecessor);
        nodeServer.server.createContext("/node/finger-table", nodeServer::fingerTable);
        nodeServer.server.createContext("/lookup", nodeServer::lookup);
        nodeServer.server.createContext("/keys/put", nodeServer::put);
        nodeServer.server.createContext("/keys/local", nodeServer::local);
        nodeServer.server.createContext("/node/health", nodeServer::health);
        nodeServer.server.setExecutor(Executors.newFixedThreadPool(6));
        nodeServer.server.start();
        return nodeServer;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public ServiceChordNode node() {
        return node;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void state(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        sendJson(exchange, 200, ServiceJson.state(node));
    }

    private void members(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 200, ServiceJson.members(node.members()));
            return;
        }
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            String encodedMembers = query(exchange.getRequestURI()).getOrDefault("members", "");
            node.replaceMembers(parseMembers(encodedMembers));
            sendJson(exchange, 200, ServiceJson.members(node.members()));
        } catch (RuntimeException error) {
            sendJson(exchange, 500, ServiceJson.error(error.getMessage()));
        }
    }

    private void join(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            Map<String, String> query = query(exchange.getRequestURI());
            int nodeId = Integer.parseInt(query.getOrDefault("nodeId", "0"));
            String uri = query.getOrDefault("uri", "");
            List<NodeEndpoint> members = node.addMember(new NodeEndpoint(nodeId, java.net.URI.create(uri)));
            sendJson(exchange, 200, ServiceJson.members(members));
        } catch (RuntimeException error) {
            sendJson(exchange, 500, ServiceJson.error(error.getMessage()));
        }
    }

    private void stabilize(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            node.stabilize();
            sendJson(exchange, 200, ServiceJson.state(node));
        } catch (RuntimeException error) {
            sendJson(exchange, 500, ServiceJson.error(error.getMessage()));
        }
    }

    private void heartbeatRepair(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            List<Integer> failedNodeIds = node.repairFailedMembers();
            sendJson(exchange, 200, ServiceJson.heartbeatRepair(node.members(), failedNodeIds));
        } catch (RuntimeException error) {
            sendJson(exchange, 500, ServiceJson.error(error.getMessage()));
        }
    }

    private void notify(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            Map<String, String> query = query(exchange.getRequestURI());
            int nodeId = Integer.parseInt(query.getOrDefault("nodeId", "0"));
            String uri = query.getOrDefault("uri", "");
            node.notify(new NodeEndpoint(nodeId, java.net.URI.create(uri)));
            sendJson(exchange, 200, ServiceJson.state(node));
        } catch (RuntimeException error) {
            sendJson(exchange, 500, ServiceJson.error(error.getMessage()));
        }
    }

    private void successor(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        sendJson(exchange, 200, "{\"successor\":" + node.successorId() + "}");
    }

    private void predecessor(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        sendJson(exchange, 200, "{\"predecessor\":" + node.predecessorId() + "}");
    }

    private void fingerTable(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        sendJson(exchange, 200, ServiceJson.state(node));
    }

    private void lookup(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        try {
            Map<String, String> query = query(exchange.getRequestURI());
            int key = Integer.parseInt(query.getOrDefault("key", "0"));
            sendJson(exchange, 200, ServiceJson.lookup(node.lookup(key, parsePath(query.get("path")))));
        } catch (RuntimeException error) {
            sendJson(exchange, 500, ServiceJson.error(error.getMessage()));
        }
    }

    private void put(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            Map<String, String> query = query(exchange.getRequestURI());
            int key = Integer.parseInt(query.getOrDefault("key", "0"));
            String value = query.getOrDefault("value", "");
            node.put(key, value, parsePath(query.get("path")));
            sendJson(exchange, 200, ServiceJson.action("ok", "stored"));
        } catch (RuntimeException error) {
            sendJson(exchange, 500, ServiceJson.error(error.getMessage()));
        }
    }

    private void local(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        int key = Integer.parseInt(query(exchange.getRequestURI()).getOrDefault("key", "0"));
        java.util.Optional<String> value = node.getLocal(key);
        sendJson(exchange, 200, ServiceJson.valueResponse(value.isPresent(), value.orElse(null)));
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        sendJson(exchange, 200, ServiceJson.action("ok", "healthy"));
    }

    private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, ServiceJson.error("method not allowed"));
            return false;
        }
        return true;
    }

    private static List<Integer> parsePath(String value) {
        List<Integer> path = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return path;
        }
        for (String part : value.split(",")) {
            if (!part.trim().isEmpty()) {
                path.add(Integer.parseInt(part.trim()));
            }
        }
        return path;
    }

    private static List<NodeEndpoint> parseMembers(String value) {
        List<NodeEndpoint> members = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return members;
        }
        for (String part : value.split(";")) {
            if (part.trim().isEmpty()) {
                continue;
            }
            int equals = part.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException("Invalid member entry: " + part);
            }
            int nodeId = Integer.parseInt(part.substring(0, equals));
            String uri = part.substring(equals + 1);
            members.add(new NodeEndpoint(nodeId, java.net.URI.create(uri)));
        }
        return members;
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

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }
}
