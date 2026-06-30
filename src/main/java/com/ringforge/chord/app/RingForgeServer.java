package com.ringforge.chord.app;

import com.ringforge.chord.api.JsonWriter;
import com.ringforge.chord.simulation.ChordRing;
import com.ringforge.chord.simulation.SampleScenario;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class RingForgeServer {
    private ChordRing ring;

    public RingForgeServer() {
        this.ring = SampleScenario.build();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        RingForgeServer app = new RingForgeServer();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/snapshot", app::snapshot);
        server.createContext("/api/lookup", app::lookup);
        server.createContext("/api/reset", app::reset);
        server.createContext("/api/repair", app::repair);
        server.createContext("/api/join", app::join);
        server.createContext("/api/leave", app::leave);
        server.createContext("/api/put", app::put);
        server.createContext("/", app::staticAsset);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("RingForge server listening on http://localhost:" + port);
    }

    private synchronized void snapshot(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        sendJson(exchange, 200, JsonWriter.snapshot(ring));
    }

    private synchronized void lookup(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        try {
            Map<String, String> query = query(exchange.getRequestURI());
            int start = Integer.parseInt(query.getOrDefault("start", "0"));
            int key = Integer.parseInt(query.getOrDefault("key", "99"));
            sendJson(exchange, 200, JsonWriter.lookup(ring.lookup(start, key)));
        } catch (RuntimeException error) {
            sendJson(exchange, 400, JsonWriter.error(error.getMessage()));
        }
    }

    private synchronized void reset(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        ring = SampleScenario.build();
        sendJson(exchange, 200, JsonWriter.action("ok", "Scenario reset."));
    }

    private synchronized void repair(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        ring.repair();
        sendJson(exchange, 200, JsonWriter.action("ok", "Finger tables and key ownership repaired."));
    }

    private synchronized void join(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            int node = Integer.parseInt(query(exchange.getRequestURI()).getOrDefault("node", "0"));
            ring.join(node);
            sendJson(exchange, 200, JsonWriter.action("ok", "Node " + ring.identifierRing().normalize(node) + " joined."));
        } catch (RuntimeException error) {
            sendJson(exchange, 400, JsonWriter.error(error.getMessage()));
        }
    }

    private synchronized void leave(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            int node = Integer.parseInt(query(exchange.getRequestURI()).getOrDefault("node", "65"));
            boolean removed = ring.leave(node).isPresent();
            sendJson(exchange, removed ? 200 : 404,
                    removed ? JsonWriter.action("ok", "Node " + ring.identifierRing().normalize(node) + " left.")
                            : JsonWriter.error("Node not found: " + node));
        } catch (RuntimeException error) {
            sendJson(exchange, 400, JsonWriter.error(error.getMessage()));
        }
    }

    private synchronized void put(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            Map<String, String> query = query(exchange.getRequestURI());
            int key = Integer.parseInt(query.getOrDefault("key", "99"));
            String value = query.getOrDefault("value", "manual");
            ring.put(key, value);
            sendJson(exchange, 200, JsonWriter.action("ok", "Key " + ring.identifierRing().normalize(key) + " stored."));
        } catch (RuntimeException error) {
            sendJson(exchange, 400, JsonWriter.error(error.getMessage()));
        }
    }

    private void staticAsset(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed", "text/plain");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }

        String contentType;
        if (path.endsWith(".css")) {
            contentType = "text/css";
        } else if (path.endsWith(".js")) {
            contentType = "application/javascript";
        } else {
            contentType = "text/html";
        }

        byte[] data = readResource("/web" + path);
        if (data == null) {
            sendText(exchange, 404, "Not found", "text/plain");
            return;
        }
        send(exchange, 200, data, contentType);
    }

    private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, JsonWriter.error("Method not allowed"));
            return false;
        }
        return true;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return values;
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
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

    private static byte[] readResource(String path) throws IOException {
        try (InputStream input = RingForgeServer.class.getResourceAsStream(path)) {
            if (input == null) {
                return null;
            }
            return input.readAllBytes();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void sendText(HttpExchange exchange, int status, String text, String contentType) throws IOException {
        send(exchange, status, text.getBytes(StandardCharsets.UTF_8), contentType);
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
}
