package com.ringforge.chord.service;

import com.ringforge.chord.core.FingerEntry;

import java.util.List;
import java.util.Map;

final class ServiceJson {
    private ServiceJson() {
    }

    static String action(String status, String message) {
        return "{\"status\":\"" + escape(status) + "\",\"message\":\"" + escape(message) + "\"}";
    }

    static String error(String message) {
        return "{\"error\":\"" + escape(message) + "\"}";
    }

    static String lookup(ServiceLookupResult result) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"key\":").append(result.key()).append(',');
        json.append("\"found\":").append(result.found()).append(',');
        if (result.value().isPresent()) {
            json.append("\"value\":\"").append(escape(result.value().get())).append("\",");
        } else {
            json.append("\"value\":null,");
        }
        json.append("\"responsibleNodeId\":").append(result.responsibleNodeId()).append(',');
        intArray(json, "path", result.path());
        json.append('}');
        return json.toString();
    }

    static String state(ServiceChordNode node) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"nodeId\":").append(node.nodeId()).append(',');
        json.append("\"predecessor\":").append(node.predecessorId()).append(',');
        json.append("\"successor\":").append(node.successorId()).append(',');
        values(json, "keys", node.localKeys());
        json.append(',');
        json.append("\"fingers\":[");
        List<FingerEntry> fingers = node.fingerTable().entries();
        for (int i = 0; i < fingers.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            FingerEntry finger = fingers.get(i);
            json.append('{');
            json.append("\"index\":").append(finger.index()).append(',');
            json.append("\"start\":").append(finger.start()).append(',');
            json.append("\"successor\":").append(finger.successor().id());
            json.append('}');
        }
        json.append("]}");
        return json.toString();
    }

    static String members(List<NodeEndpoint> members) {
        StringBuilder json = new StringBuilder();
        json.append("{\"members\":[");
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            NodeEndpoint member = members.get(i);
            json.append('{');
            json.append("\"nodeId\":").append(member.nodeId()).append(',');
            json.append("\"uri\":\"").append(escape(member.baseUri().toString())).append("\"");
            json.append('}');
        }
        json.append("]}");
        return json.toString();
    }

    static String valueResponse(boolean found, String value) {
        if (!found) {
            return "{\"found\":false,\"value\":null}";
        }
        return "{\"found\":true,\"value\":\"" + escape(value) + "\"}";
    }

    private static void values(StringBuilder json, String name, Map<Integer, String> values) {
        json.append('"').append(name).append("\":{");
        int index = 0;
        for (Map.Entry<Integer, String> entry : values.entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            json.append('"').append(entry.getKey()).append("\":\"").append(escape(entry.getValue())).append('"');
        }
        json.append('}');
    }

    private static void intArray(StringBuilder json, String name, List<Integer> values) {
        json.append('"').append(name).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(values.get(i));
        }
        json.append(']');
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
