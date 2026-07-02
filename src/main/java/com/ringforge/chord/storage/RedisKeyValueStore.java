package com.ringforge.chord.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RedisKeyValueStore implements KeyValueStore {
    private final String host;
    private final int port;
    private final String namespace;
    private final int timeoutMillis;

    public RedisKeyValueStore(String host, int port, String namespace) {
        this(host, port, namespace, 2_000);
    }

    public RedisKeyValueStore(String host, int port, String namespace, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.namespace = namespace == null || namespace.trim().isEmpty() ? "ringforge" : namespace;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void put(int key, String value) {
        command("SET", valueKey(key), value);
        command("SADD", indexKey(), String.valueOf(key));
    }

    @Override
    public Optional<String> get(int key) {
        Object value = command("GET", valueKey(key));
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    @Override
    public Optional<String> delete(int key) {
        Optional<String> existing = get(key);
        command("DEL", valueKey(key));
        command("SREM", indexKey(), String.valueOf(key));
        return existing;
    }

    @Override
    public Map<Integer, String> drain() {
        Map<Integer, String> snapshot = snapshot();
        for (Integer key : snapshot.keySet()) {
            command("DEL", valueKey(key));
        }
        command("DEL", indexKey());
        return snapshot;
    }

    @Override
    public Map<Integer, String> snapshot() {
        List<Integer> keys = indexedKeys();
        Collections.sort(keys);
        Map<Integer, String> values = new LinkedHashMap<>();
        for (Integer key : keys) {
            get(key).ifPresent(value -> values.put(key, value));
        }
        return values;
    }

    private List<Integer> indexedKeys() {
        Object response = command("SMEMBERS", indexKey());
        List<Integer> keys = new ArrayList<>();
        if (response instanceof List<?>) {
            for (Object value : (List<?>) response) {
                if (value != null) {
                    keys.add(Integer.parseInt(value.toString()));
                }
            }
        }
        return keys;
    }

    private String indexKey() {
        return namespace + ":keys";
    }

    private String valueKey(int key) {
        return namespace + ":key:" + key;
    }

    private Object command(String... parts) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            OutputStream output = socket.getOutputStream();
            writeCommand(output, parts);
            output.flush();
            return readReply(socket.getInputStream());
        } catch (IOException error) {
            throw new IllegalStateException("Redis request failed: " + error.getMessage(), error);
        }
    }

    private static void writeCommand(OutputStream output, String[] parts) throws IOException {
        output.write(("*" + parts.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String part : parts) {
            byte[] data = part.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + data.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(data);
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Object readReply(InputStream input) throws IOException {
        int type = input.read();
        if (type < 0) {
            throw new IOException("empty Redis response");
        }
        if (type == '+') {
            return readLine(input);
        }
        if (type == '-') {
            throw new IllegalStateException("Redis error: " + readLine(input));
        }
        if (type == ':') {
            return Long.parseLong(readLine(input));
        }
        if (type == '$') {
            int length = Integer.parseInt(readLine(input));
            if (length < 0) {
                return null;
            }
            byte[] data = input.readNBytes(length);
            consumeCrlf(input);
            return new String(data, StandardCharsets.UTF_8);
        }
        if (type == '*') {
            int count = Integer.parseInt(readLine(input));
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                values.add(readReply(input));
            }
            return values;
        }
        throw new IOException("unsupported Redis response type: " + (char) type);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) {
                throw new IOException("unterminated Redis line");
            }
            if (previous == '\r' && current == '\n') {
                byte[] bytes = data.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            data.write(current);
            previous = current;
        }
    }

    private static void consumeCrlf(InputStream input) throws IOException {
        int cr = input.read();
        int lf = input.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("invalid Redis bulk terminator");
        }
    }
}
