package org.reactivecommons.async.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the Kafka consumers created per consumer group: the Kafka client creates and configures its interceptors
 * once per consumer. Registered through the {@code interceptor.classes} property of the consumer.
 */
public class CountingConsumerInterceptor implements ConsumerInterceptor<Object, Object> {

    private static final Map<String, AtomicInteger> CREATED = new ConcurrentHashMap<>();

    /**
     * The consumer property that registers this interceptor, without the prefix of the domain.
     */
    static final String PROPERTY = "connectionProperties.consumer.properties[interceptor.classes]";

    static int consumersCreated(String group) {
        AtomicInteger created = CREATED.get(group);
        return created == null ? 0 : created.get();
    }

    @Override
    public void configure(Map<String, ?> configs) {
        Object group = configs.get(ConsumerConfig.GROUP_ID_CONFIG);
        CREATED.computeIfAbsent(String.valueOf(group), key -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public ConsumerRecords<Object, Object> onConsume(ConsumerRecords<Object, Object> records) {
        return records;
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        // Nothing to count
    }

    @Override
    public void close() {
        // Nothing to release
    }
}
