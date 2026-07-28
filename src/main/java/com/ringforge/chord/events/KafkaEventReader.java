package com.ringforge.chord.events;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public final class KafkaEventReader {
    private final String bootstrapServers;
    private final String topic;

    public KafkaEventReader(String bootstrapServers, String topic) {
        this.bootstrapServers = bootstrapServers;
        this.topic = topic == null || topic.trim().isEmpty() ? "ringforge.events" : topic;
    }

    public List<String> latestEvents(int limit, Duration timeout) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "ringforge-audit-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = partitions(consumer);
            if (partitions.isEmpty()) {
                return new ArrayList<>();
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            long deadline = System.nanoTime() + timeout.toNanos();
            List<EventRecord> records = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                boolean received = false;
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    records.add(new EventRecord(record.timestamp(), record.partition(), record.offset(), record.value()));
                    received = true;
                }
                if (!received && records.size() >= boundedLimit) {
                    break;
                }
            }

            records.sort(Comparator.comparingLong(EventRecord::timestamp)
                    .thenComparingInt(EventRecord::partition)
                    .thenComparingLong(EventRecord::offset));
            int start = Math.max(0, records.size() - boundedLimit);
            List<String> values = new ArrayList<>();
            for (EventRecord record : records.subList(start, records.size())) {
                values.add(record.value());
            }
            return values;
        }
    }

    private List<TopicPartition> partitions(KafkaConsumer<String, String> consumer) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(2));
        List<TopicPartition> partitions = new ArrayList<>();
        if (infos != null) {
            for (PartitionInfo info : infos) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
        }
        return partitions;
    }

    private static final class EventRecord {
        private final long timestamp;
        private final int partition;
        private final long offset;
        private final String value;

        private EventRecord(long timestamp, int partition, long offset, String value) {
            this.timestamp = timestamp;
            this.partition = partition;
            this.offset = offset;
            this.value = value;
        }

        private long timestamp() {
            return timestamp;
        }

        private int partition() {
            return partition;
        }

        private long offset() {
            return offset;
        }

        private String value() {
            return value;
        }
    }
}
