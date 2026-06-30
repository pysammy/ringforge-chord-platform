package com.ringforge.chord.transport;

import com.ringforge.chord.storage.KeyValueStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class HttpKeyValueStore implements KeyValueStore {
    private final URI baseUri;
    private final int timeoutMillis;

    public HttpKeyValueStore(URI baseUri) {
        this(baseUri, 2_000);
    }

    public HttpKeyValueStore(URI baseUri, int timeoutMillis) {
        this.baseUri = baseUri;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void put(int key, String value) {
        request("POST", "/store/put?key=" + key + "&value=" + encode(value));
    }

    @Override
    public Optional<String> get(int key) {
        String response = request("GET", "/store/get?key=" + key);
        if (!response.contains("\"found\":true")) {
            return Optional.empty();
        }
        return Optional.ofNullable(extractJsonString(response, "value"));
    }

    @Override
    public Optional<String> delete(int key) {
        String response = request("POST", "/store/delete?key=" + key);
        if (!response.contains("\"found\":true")) {
            return Optional.empty();
        }
        return Optional.ofNullable(extractJsonString(response, "value"));
    }

    @Override
    public Map<Integer, String> drain() {
        return parseValues(request("POST", "/store/drain"));
    }

    @Override
    public Map<Integer, String> snapshot() {
        return parseValues(request("GET", "/store/snapshot"));
    }

    private String request(String method, String path) {
        try {
            URI uri = baseUri.resolve(path);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Accept", "application/json");
            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
                try (OutputStream ignored = connection.getOutputStream()) {
                    // Empty body; parameters are carried in the query string for the local node protocol.
                }
            }

            int status = connection.getResponseCode();
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
            if (status >= 400) {
                throw new IllegalStateException("Remote store request failed with status " + status + ": " + response);
            }
            return response;
        } catch (IOException error) {
            throw new IllegalStateException("Remote store request failed: " + error.getMessage(), error);
        }
    }

    private static Map<Integer, String> parseValues(String json) {
        Map<Integer, String> values = new LinkedHashMap<>();
        int valuesStart = json.indexOf("\"values\":{");
        if (valuesStart < 0) {
            return values;
        }
        int objectStart = json.indexOf('{', valuesStart + 9);
        int objectEnd = json.indexOf('}', objectStart);
        if (objectStart < 0 || objectEnd < 0 || objectEnd <= objectStart + 1) {
            return values;
        }
        String body = json.substring(objectStart + 1, objectEnd);
        int position = 0;
        while (position < body.length()) {
            int keyStart = body.indexOf('"', position);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = body.indexOf('"', keyStart + 1);
            int valueStart = body.indexOf('"', keyEnd + 1);
            int valueEnd = findStringEnd(body, valueStart + 1);
            if (keyEnd < 0 || valueStart < 0 || valueEnd < 0) {
                break;
            }
            int key = Integer.parseInt(unescape(body.substring(keyStart + 1, keyEnd)));
            String value = unescape(body.substring(valueStart + 1, valueEnd));
            values.put(key, value);
            position = valueEnd + 1;
        }
        return values;
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
        int valueEnd = findStringEnd(json, valueStart + 1);
        if (valueEnd < 0) {
            return null;
        }
        return unescape(json.substring(valueStart + 1, valueEnd));
    }

    private static int findStringEnd(String text, int start) {
        boolean escaping = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaping) {
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
