package com.ringforge.chord.app;

import com.ringforge.chord.service.NodeEndpoint;
import com.ringforge.chord.service.ServiceChordNode;
import com.ringforge.chord.service.ServiceChordNodeServer;
import com.ringforge.chord.events.KafkaServiceEventPublisher;
import com.ringforge.chord.events.NoopServiceEventPublisher;
import com.ringforge.chord.events.ServiceEventPublisher;
import com.ringforge.chord.storage.InMemoryKeyValueStore;
import com.ringforge.chord.storage.KeyValueStore;
import com.ringforge.chord.storage.RedisKeyValueStore;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class ServiceNodeMain {
    private ServiceNodeMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        int nodeId = requiredInt(options, "id");
        int port = intOption(options, "port", 0);
        int bitLength = intOption(options, "bit-length", 8);
        int replicationFactor = intOption(options, "replication-factor", 3);
        long heartbeatMillis = longOption(options, "heartbeat-ms", 1_000L);
        int joinRetries = intOption(options, "join-retries", 30);
        long joinRetryMillis = longOption(options, "join-retry-ms", 1_000L);
        String host = options.getOrDefault("host", "localhost");
        String joinUri = options.get("join");
        KeyValueStore store = store(options, nodeId, "primary");
        KeyValueStore replicaStore = store(options, nodeId, "replica");
        ServiceEventPublisher eventPublisher = eventPublisher(options, nodeId);

        ServiceChordNode node = new ServiceChordNode(nodeId, bitLength, store, replicaStore, replicationFactor, eventPublisher);
        ServiceChordNodeServer server = ServiceChordNodeServer.start(node, port, heartbeatMillis);
        NodeEndpoint self = new NodeEndpoint(nodeId, URI.create("http://" + host + ":" + server.port()));

        try {
            if (joinUri == null || joinUri.trim().isEmpty()) {
                node.configureCluster(Collections.singletonList(self));
            } else {
                joinWithRetry(node, self, URI.create(joinUri), joinRetries, joinRetryMillis);
            }
        } catch (RuntimeException error) {
            server.close();
            throw error;
        }

        System.out.println("RingForge service node " + nodeId + " listening on " + self.baseUri());
        System.out.println("heartbeatMillis=" + heartbeatMillis + " replicationFactor=" + replicationFactor);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            eventPublisher.close();
            stopped.countDown();
        }, "ringforge-service-node-shutdown"));
        stopped.await();
    }

    private static KeyValueStore store(Map<String, String> options, int nodeId, String role) {
        String storage = options.getOrDefault("storage", "memory");
        if ("redis".equalsIgnoreCase(storage)) {
            String host = options.getOrDefault("redis-host", "localhost");
            int port = intOption(options, "redis-port", 6379);
            String prefix = options.getOrDefault("redis-prefix", "ringforge:node:" + nodeId);
            return new RedisKeyValueStore(host, port, prefix + ":" + role);
        }
        return new InMemoryKeyValueStore();
    }

    private static ServiceEventPublisher eventPublisher(Map<String, String> options, int nodeId) {
        String bootstrapServers = options.get("kafka-bootstrap-servers");
        if (bootstrapServers == null || bootstrapServers.trim().isEmpty()) {
            return new NoopServiceEventPublisher();
        }
        String topic = options.getOrDefault("kafka-topic", "ringforge.events");
        return new KafkaServiceEventPublisher(bootstrapServers, topic, nodeId);
    }

    private static void joinWithRetry(ServiceChordNode node, NodeEndpoint self, URI joinUri,
                                      int joinRetries, long joinRetryMillis) {
        RuntimeException lastError = null;
        int attempts = Math.max(1, joinRetries);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                node.joinVia(self, new NodeEndpoint(-1, joinUri));
                return;
            } catch (RuntimeException error) {
                lastError = error;
                if (attempt == attempts) {
                    break;
                }
                sleep(joinRetryMillis);
            }
        }
        throw new IllegalStateException("Unable to join bootstrap " + joinUri + " after " + attempts + " attempt(s)",
                lastError);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to join", error);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String name = arg.substring(2);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for --" + name);
            }
            options.put(name, args[++i]);
        }
        return options;
    }

    private static int requiredInt(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing required option --" + name);
        }
        return Integer.parseInt(value);
    }

    private static int intOption(Map<String, String> options, String name, int fallback) {
        String value = options.get(name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static long longOption(Map<String, String> options, String name, long fallback) {
        String value = options.get(name);
        return value == null ? fallback : Long.parseLong(value);
    }
}
