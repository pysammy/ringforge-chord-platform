package com.ringforge.chord.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ServiceChordClient {
    private final URI baseUri;
    private final int timeoutMillis;

    public ServiceChordClient(URI baseUri) {
        this(baseUri, 2_000);
    }

    public ServiceChordClient(URI baseUri, int timeoutMillis) {
        this.baseUri = baseUri;
        this.timeoutMillis = timeoutMillis;
    }

    public ServiceLookupResult lookup(int key, List<Integer> path) {
        String response = request("GET", "/lookup?key=" + key + "&path=" + encodePath(path));
        return parseLookup(response);
    }

    public void put(int key, String value, List<Integer> path) {
        request("POST", "/keys/put?key=" + key + "&value=" + encode(value) + "&path=" + encodePath(path));
    }

    public Optional<String> getLocal(int key) {
        String response = request("GET", "/keys/local?key=" + key);
        if (!response.contains("\"found\":true")) {
            return Optional.empty();
        }
        return Optional.ofNullable(extractJsonString(response, "value"));
    }

    private String request(String method, String path) {
        try {
            HttpURLConnection connection = (HttpURLConnection) baseUri.resolve(path).toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Accept", "application/json");
            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
                try (OutputStream ignored = connection.getOutputStream()) {
                    // Empty request body.
                }
            }

            int status = connection.getResponseCode();
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
            if (status >= 400) {
                throw new IllegalStateException("Node request failed with status " + status + ": " + response);
            }
            return response;
        } catch (IOException error) {
            throw new IllegalStateException("Node request failed: " + error.getMessage(), error);
        }
    }

    static ServiceLookupResult parseLookup(String json) {
        int key = extractJsonInt(json, "key");
        boolean found = json.contains("\"found\":true");
        String value = extractJsonString(json, "value");
        int responsibleNodeId = extractJsonInt(json, "responsibleNodeId");
        List<Integer> path = extractJsonIntArray(json, "path");
        return new ServiceLookupResult(key, found, value, responsibleNodeId, path);
    }

    private static String encodePath(List<Integer> path) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                value.append(',');
            }
            value.append(path.get(i));
        }
        return encode(value.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static int extractJsonInt(String json, String name) {
        String field = "\"" + name + "\":";
        int start = json.indexOf(field);
        if (start < 0) {
            return 0;
        }
        int valueStart = start + field.length();
        int valueEnd = valueStart;
        while (valueEnd < json.length() && (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '-')) {
            valueEnd++;
        }
        return Integer.parseInt(json.substring(valueStart, valueEnd));
    }

    private static String extractJsonString(String json, String name) {
        String field = "\"" + name + "\":";
        int fieldStart = json.indexOf(field);
        if (fieldStart < 0) {
            return null;
        }
        int valueStart = json.indexOf('"', fieldStart + field.length());
        if (valueStart < 0) {
            return null;
        }
        int valueEnd = json.indexOf('"', valueStart + 1);
        if (valueEnd < 0) {
            return null;
        }
        return json.substring(valueStart + 1, valueEnd).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static List<Integer> extractJsonIntArray(String json, String name) {
        String field = "\"" + name + "\":[";
        int start = json.indexOf(field);
        List<Integer> values = new ArrayList<>();
        if (start < 0) {
            return values;
        }
        int bodyStart = start + field.length();
        int bodyEnd = json.indexOf(']', bodyStart);
        if (bodyEnd < 0) {
            return values;
        }
        String body = json.substring(bodyStart, bodyEnd);
        if (body.trim().isEmpty()) {
            return values;
        }
        for (String part : body.split(",")) {
            values.add(Integer.parseInt(part.trim()));
        }
        return values;
    }
}
