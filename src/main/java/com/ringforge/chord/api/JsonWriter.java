package com.ringforge.chord.api;

import com.ringforge.chord.core.ChordNode;
import com.ringforge.chord.core.FingerEntry;
import com.ringforge.chord.core.LookupResult;
import com.ringforge.chord.diagnostics.DiagnosticFinding;
import com.ringforge.chord.diagnostics.DiagnosticReport;
import com.ringforge.chord.events.RingEvent;
import com.ringforge.chord.metrics.BenchmarkReport;
import com.ringforge.chord.ops.OpsAdvice;
import com.ringforge.chord.simulation.ChordRing;
import com.ringforge.chord.simulation.HealthFinding;
import com.ringforge.chord.simulation.HealthReport;

import java.util.List;
import java.util.Map;

public final class JsonWriter {
    private JsonWriter() {
    }

    public static String snapshot(ChordRing ring) {
        HealthReport health = ring.healthReport();
        int keyCount = 0;
        int replicaCount = 0;
        int maxKeys = 0;
        int minKeys = ring.nodes().isEmpty() ? 0 : Integer.MAX_VALUE;
        for (ChordNode node : ring.nodes()) {
            int count = node.snapshotKeys().size();
            keyCount += count;
            replicaCount += node.snapshotReplicas().size();
            maxKeys = Math.max(maxKeys, count);
            minKeys = Math.min(minKeys, count);
        }

        StringBuilder json = new StringBuilder();
        json.append('{');
        field(json, "name", "RingForge Chord Platform").append(',');
        field(json, "problem", "Reliable key placement and routing diagnostics under node churn").append(',');
        json.append("\"ringSize\":").append(ring.identifierRing().size()).append(',');
        json.append("\"bitLength\":").append(ring.identifierRing().bitLength()).append(',');
        json.append("\"replicationFactor\":").append(ring.replicationFactor()).append(',');
        json.append("\"metrics\":{");
        json.append("\"nodeCount\":").append(ring.nodes().size()).append(',');
        json.append("\"failedNodeCount\":").append(ring.failedNodeIds().size()).append(',');
        json.append("\"keyCount\":").append(keyCount).append(',');
        json.append("\"replicaCount\":").append(replicaCount).append(',');
        json.append("\"maxKeysPerNode\":").append(maxKeys).append(',');
        json.append("\"minKeysPerNode\":").append(minKeys).append(',');
        json.append("\"healthy\":").append(health.healthy()).append(',');
        json.append("\"diagnosticsHealthy\":").append(ring.diagnostics().healthy());
        json.append("},");
        intArray(json, "failedNodes", new java.util.ArrayList<>(ring.failedNodeIds()));
        json.append(',');
        json.append("\"nodes\":[");
        for (int i = 0; i < ring.nodes().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            node(json, ring.nodes().get(i));
        }
        json.append("],");
        health(json, health);
        json.append(',');
        diagnostics(json, ring.diagnostics());
        json.append(',');
        opsAdvice(json, ring.opsAdvice());
        json.append(',');
        events(json, "events", ring.latestEvents(80));
        json.append('}');
        return json.toString();
    }

    public static String lookup(LookupResult result) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"key\":").append(result.key()).append(',');
        json.append("\"found\":").append(result.found()).append(',');
        field(json, "value", result.value().orElse(null)).append(',');
        json.append("\"startNodeId\":").append(result.startNodeId()).append(',');
        json.append("\"responsibleNodeId\":").append(result.responsibleNodeId()).append(',');
        json.append("\"hopCount\":").append(result.hopCount()).append(',');
        json.append("\"latencyMillis\":").append(String.format(java.util.Locale.US, "%.4f", result.latencyMillis())).append(',');
        intArray(json, "path", result.path());
        json.append('}');
        return json.toString();
    }

    public static String action(String status, String message) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        field(json, "status", status).append(',');
        field(json, "message", message);
        json.append('}');
        return json.toString();
    }

    public static String events(List<RingEvent> events) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        events(json, "events", events);
        json.append('}');
        return json.toString();
    }

    public static String diagnostics(DiagnosticReport report) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        diagnostics(json, report);
        json.append('}');
        return json.toString();
    }

    public static String benchmark(BenchmarkReport report) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        benchmarkFields(json, report);
        json.append('}');
        return json.toString();
    }

    public static String opsAdvice(OpsAdvice advice) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        opsAdvice(json, advice);
        json.append('}');
        return json.toString();
    }

    public static String error(String message) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        field(json, "error", message);
        json.append('}');
        return json.toString();
    }

    private static void node(StringBuilder json, ChordNode node) {
        json.append('{');
        json.append("\"id\":").append(node.id()).append(',');
        json.append("\"predecessor\":").append(node.predecessor().id()).append(',');
        json.append("\"successor\":").append(node.successor().id()).append(',');
        json.append("\"keys\":{");
        int keyIndex = 0;
        for (Map.Entry<Integer, String> entry : node.snapshotKeys().entrySet()) {
            if (keyIndex++ > 0) {
                json.append(',');
            }
            field(json, String.valueOf(entry.getKey()), entry.getValue());
        }
        json.append("},");
        json.append("\"replicas\":{");
        int replicaIndex = 0;
        for (Map.Entry<Integer, String> entry : node.snapshotReplicas().entrySet()) {
            if (replicaIndex++ > 0) {
                json.append(',');
            }
            field(json, String.valueOf(entry.getKey()), entry.getValue());
        }
        json.append("},");
        json.append("\"fingers\":[");
        List<FingerEntry> entries = node.fingerTable().entries();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            FingerEntry entry = entries.get(i);
            json.append('{');
            json.append("\"index\":").append(entry.index()).append(',');
            json.append("\"start\":").append(entry.start()).append(',');
            json.append("\"endExclusive\":").append(entry.endExclusive()).append(',');
            json.append("\"successor\":").append(entry.successor().id());
            json.append('}');
        }
        json.append(']');
        json.append('}');
    }

    private static void health(StringBuilder json, HealthReport health) {
        json.append("\"health\":{");
        json.append("\"healthy\":").append(health.healthy()).append(',');
        json.append("\"findings\":[");
        for (int i = 0; i < health.findings().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            HealthFinding finding = health.findings().get(i);
            json.append('{');
            field(json, "severity", finding.severity()).append(',');
            field(json, "message", finding.message());
            json.append('}');
        }
        json.append("]}");
    }

    private static void diagnostics(StringBuilder json, DiagnosticReport report) {
        json.append("\"diagnostics\":{");
        json.append("\"healthy\":").append(report.healthy()).append(',');
        json.append("\"findings\":[");
        for (int i = 0; i < report.findings().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            DiagnosticFinding finding = report.findings().get(i);
            json.append('{');
            field(json, "severity", finding.severity()).append(',');
            field(json, "category", finding.category()).append(',');
            field(json, "message", finding.message()).append(',');
            field(json, "recommendation", finding.recommendation());
            json.append('}');
        }
        json.append("]}");
    }

    private static void benchmarkFields(StringBuilder json, BenchmarkReport report) {
        json.append("\"lookupCount\":").append(report.lookupCount()).append(',');
        json.append("\"nodeCount\":").append(report.nodeCount()).append(',');
        json.append("\"keyCount\":").append(report.keyCount()).append(',');
        json.append("\"averageHops\":").append(String.format(java.util.Locale.US, "%.4f", report.averageHops())).append(',');
        json.append("\"maxHops\":").append(report.maxHops()).append(',');
        json.append("\"healthy\":").append(report.healthy());
    }

    private static void opsAdvice(StringBuilder json, OpsAdvice advice) {
        json.append("\"opsAdvice\":{");
        stringArray(json, "summary", advice.summary());
        json.append(',');
        stringArray(json, "recommendedActions", advice.recommendedActions());
        json.append('}');
    }

    private static void events(StringBuilder json, String name, List<RingEvent> events) {
        json.append('"').append(escape(name)).append("\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            event(json, events.get(i));
        }
        json.append(']');
    }

    private static void event(StringBuilder json, RingEvent event) {
        json.append('{');
        json.append("\"sequence\":").append(event.sequence()).append(',');
        field(json, "timestamp", event.timestamp().toString()).append(',');
        field(json, "type", event.type().name()).append(',');
        field(json, "message", event.message()).append(',');
        json.append("\"details\":{");
        int index = 0;
        for (Map.Entry<String, String> entry : event.details().entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            field(json, entry.getKey(), entry.getValue());
        }
        json.append("}}");
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

    private static StringBuilder field(StringBuilder json, String name, String value) {
        json.append('"').append(escape(name)).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(escape(value)).append('"');
        }
        return json;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
