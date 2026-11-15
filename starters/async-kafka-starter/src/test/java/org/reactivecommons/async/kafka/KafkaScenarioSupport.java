package org.reactivecommons.async.kafka;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.reactivecommons.async.starter.config.ReactiveCommonsListenersConfig;
import org.reactivecommons.async.starter.impl.common.kafka.RCKafkaConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Starts the Kafka starter the way an application does (the same {@code @Enable...} annotations and the same
 * configuration classes) against a real Kafka broker.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class KafkaScenarioSupport {

    static final String KAFKA_IMAGE = "apache/kafka-native";
    static final String PRODUCER_THREAD = "kafka-producer-network-thread";

    /**
     * The configuration of a service that uses RabbitMQ for its default domain: the Kafka 'app' domain is disabled
     * and the Kafka handlers are registered on a domain of their own, which listens from the earliest offset.
     */
    static AnnotationConfigApplicationContext startOnOwnDomain(KafkaContainer kafka, String appName, String domain,
                                                               Class<?>... configurations) {
        return startOnOwnDomain(kafka, appName, domain, Map.of(), null, configurations);
    }

    /**
     * Same as above, with properties of the domain (e.g. {@code retryDelay}) and the handlers of the application.
     *
     * @param domainProperties properties of the domain, without the {@code reactive.commons.kafka.<domain>} prefix
     * @param handlers         the handlers of the application, or {@code null} when a configuration registers them
     */
    static AnnotationConfigApplicationContext startOnOwnDomain(KafkaContainer kafka, String appName, String domain,
                                                               Map<String, Object> domainProperties,
                                                               HandlerRegistry handlers, Class<?>... configurations) {
        String prefix = "reactive.commons.kafka." + domain + ".";
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.application.name", appName);
        properties.put("reactive.commons.kafka.app.enabled", "false");
        properties.put(prefix + "enabled", "true");
        properties.put(prefix + "connectionProperties.bootstrap-servers", kafka.getBootstrapServers());
        properties.put(prefix + "connectionProperties.consumer.auto-offset-reset", "earliest");
        domainProperties.forEach((key, value) -> properties.put(prefix + key, value));
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("scenario", properties));
        // The configurations of the application first, so the default beans of the starter (e.g. an empty
        // HandlerRegistry) back off, as they do in a Spring Boot application
        if (handlers != null) {
            context.registerBean("handlerRegistry", HandlerRegistry.class, () -> handlers);
        }
        context.register(configurations);
        context.register(RCKafkaConfig.class, ReactiveCommonsConfig.class, ReactiveCommonsListenersConfig.class);
        context.refresh();
        return context;
    }

    /**
     * Publishes with a plain Kafka producer of the test, not with the library.
     */
    static void publish(KafkaContainer kafka, String topic, String value) throws Exception {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.CLIENT_ID_CONFIG, "test-publisher");
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, value)).get();
        }
    }

    /**
     * Creates a topic with the given number of partitions.
     */
    static void createTopic(KafkaContainer kafka, String topic, int partitions) throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
        }
    }

    /**
     * Publishes the values, in order, to one partition of a topic, with a plain Kafka producer of the test.
     */
    static void publish(KafkaContainer kafka, String topic, int partition, String... values) throws Exception {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.CLIENT_ID_CONFIG, "test-publisher");
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            for (String value : values) {
                producer.send(new ProducerRecord<>(topic, partition, null, value)).get();
            }
        }
    }

    /**
     * The offset committed by a consumer group on a partition, or -1 when it committed none.
     */
    static long committedOffset(KafkaContainer kafka, String group, String topic, int partition) throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get()
                    .get(new TopicPartition(topic, partition));
            return offset == null ? -1 : offset.offset();
        }
    }

    /**
     * The body of a message received by a raw topic handler, as text.
     */
    static String body(RawMessage message) {
        return new String(((Message) message).getBody(), StandardCharsets.UTF_8);
    }

    /**
     * Names of the live threads that start with any of the given prefixes and contain the given text.
     */
    static List<String> threads(String contains, String... prefixes) {
        Set<Thread> all = Thread.getAllStackTraces().keySet();
        return all.stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.contains(contains))
                .filter(name -> Stream.of(prefixes).anyMatch(name::startsWith))
                .sorted()
                .toList();
    }
}
