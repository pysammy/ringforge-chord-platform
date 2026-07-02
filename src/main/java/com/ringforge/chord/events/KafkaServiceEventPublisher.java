package com.ringforge.chord.events;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class KafkaServiceEventPublisher implements ServiceEventPublisher {
    private final KafkaProducer<String, String> producer;
    private final String topic;
    private final String sourceNodeId;

    public KafkaServiceEventPublisher(String bootstrapServers, String topic, int sourceNodeId) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        properties.put("acks", "1");
        properties.put("linger.ms", "5");
        properties.put("delivery.timeout.ms", "3000");
        properties.put("request.timeout.ms", "1000");
        properties.put("max.block.ms", "1000");
        this.producer = new KafkaProducer<>(properties);
        this.topic = topic == null || topic.trim().isEmpty() ? "ringforge.events" : topic;
        this.sourceNodeId = String.valueOf(sourceNodeId);
    }

    @Override
    public void publish(String type, Map<String, String> details) {
        Map<String, String> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("type", type);
        event.put("sourceNodeId", sourceNodeId);
        event.putAll(details);
        producer.send(new ProducerRecord<>(topic, sourceNodeId, json(event)));
    }

    @Override
    public void close() {
        producer.flush();
        producer.close();
    }

    private static String json(Map<String, String> values) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            json.append('"').append(escape(entry.getKey())).append("\":");
            json.append('"').append(escape(entry.getValue())).append('"');
        }
        json.append('}');
        return json.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
