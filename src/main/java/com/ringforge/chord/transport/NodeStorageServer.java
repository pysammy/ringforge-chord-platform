package com.ringforge.chord.transport;

import com.ringforge.chord.storage.InMemoryKeyValueStore;
import com.ringforge.chord.storage.KeyValueStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

public final class NodeStorageServer implements AutoCloseable {
    private final KeyValueStore store;
    private final HttpServer server;

    private NodeStorageServer(KeyValueStore store, HttpServer server) {
        this.store = store;
        this.server = server;
    }

    public static NodeStorageServer start(int port) throws IOException {
        return start(port, new InMemoryKeyValueStore());
    }

    public static NodeStorageServer start(int port, KeyValueStore store) throws IOException {
        NodeStorageServer nodeServer = new NodeStorageServer(store, HttpServer.create(new InetSocketAddress(port), 0));
        nodeServer.server.createContext("/store/put", nodeServer::put);
        nodeServer.server.createContext("/store/get", nodeServer::get);
        nodeServer.server.createContext("/store/delete", nodeServer::delete);
        nodeServer.server.createContext("/store/drain", nodeServer::drain);
        nodeServer.server.createContext("/store/snapshot", nodeServer::snapshot);
        nodeServer.server.createContext("/node/health", nodeServer::health);
        nodeServer.server.setExecutor(Executors.newFixedThreadPool(4));
        nodeServer.server.start();
        return nodeServer;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9001;
        NodeStorageServer.start(port);
        System.out.println("RingForge node storage server listening on http://localhost:" + port);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private synchronized void put(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        int key = Integer.parseInt(query.getOrDefault("key", "0"));
        String value = query.getOrDefault("value", "");
        store.put(key, value);
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    private synchronized void get(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        int key = Integer.parseInt(query(exchange.getRequestURI()).getOrDefault("key", "0"));
        Optional<String> value = store.get(key);
        sendJson(exchange, 200, foundJson(value));
    }

    private synchronized void delete(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        int key = Integer.parseInt(query(exchange.getRequestURI()).getOrDefault("key", "0"));
        Optional<String> value = store.delete(key);
        sendJson(exchange, 200, foundJson(value));
    }

    private synchronized void drain(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        sendJson(exchange, 200, valuesJson(store.drain()));
    }

    private synchronized void snapshot(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        sendJson(exchange, 200, valuesJson(store.snapshot()));
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            return false;
        }
        return true;
    }

    private static String foundJson(Optional<String> value) {
        if (value.isEmpty()) {
            return "{\"found\":false,\"value\":null}";
        }
        return "{\"found\":true,\"value\":\"" + escape(value.get()) + "\"}";
    }

    private static String valuesJson(Map<Integer, String> values) {
        StringBuilder json = new StringBuilder();
        json.append("{\"values\":{");
        int index = 0;
        for (Map.Entry<Integer, String> entry : values.entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            json.append('"').append(entry.getKey()).append("\":\"").append(escape(entry.getValue())).append('"');
        }
        json.append("}}");
        return json.toString();
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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
