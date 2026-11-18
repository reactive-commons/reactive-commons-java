package org.reactivecommons.async.kafka;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
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
        return startOnOwnDomain(kafka, new OwnDomain(appName, domain), null, configurations);
    }

    /**
     * Same as above, with properties of the domain (e.g. {@code retryDelay}) and the handlers of the application.
     *
     * @param ownDomain the application name, its domain and the properties of the domain
     * @param handlers  the handlers of the application, or {@code null} when a configuration registers them
     */
    static AnnotationConfigApplicationContext startOnOwnDomain(KafkaContainer kafka, OwnDomain ownDomain,
                                                               HandlerRegistry handlers, Class<?>... configurations) {
        return startOnOwnDomain(kafka.getBootstrapServers(), ownDomain, handlers, configurations);
    }

    /**
     * Same as above, against the given bootstrap servers, which may not be reachable.
     */
    static AnnotationConfigApplicationContext startOnOwnDomain(String bootstrapServers, OwnDomain ownDomain,
                                                               HandlerRegistry handlers, Class<?>... configurations) {
        String prefix = "reactive.commons.kafka." + ownDomain.domain() + ".";
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.application.name", ownDomain.appName());
        properties.put("reactive.commons.kafka.app.enabled", "false");
        properties.put(prefix + "enabled", "true");
        properties.put(prefix + "connectionProperties.bootstrap-servers", bootstrapServers);
        properties.put(prefix + "connectionProperties.consumer.auto-offset-reset", "earliest");
        ownDomain.properties().forEach((key, value) -> properties.put(prefix + key, value));
        return start(properties, handlers, configurations);
    }

    /**
     * The Kafka domain of its own on which the application registers its handlers.
     *
     * @param appName    the {@code spring.application.name} of the application
     * @param domain     the name of the domain
     * @param properties properties of the domain, without the {@code reactive.commons.kafka.<domain>} prefix
     */
    record OwnDomain(String appName, String domain, Map<String, Object> properties) {

        /**
         * A domain without properties of its own.
         */
        OwnDomain(String appName, String domain) {
            this(appName, domain, Map.of());
        }
    }

    /**
     * Starts the starter with the given properties, the handlers of the application (or {@code null} when a
     * configuration registers them) and its configurations.
     */
    static AnnotationConfigApplicationContext start(Map<String, Object> properties, HandlerRegistry handlers,
                                                    Class<?>... configurations) {
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
            List<Future<RecordMetadata>> sent = new ArrayList<>();
            for (String value : values) {
                sent.add(producer.send(new ProducerRecord<>(topic, partition, null, value)));
            }
            for (Future<RecordMetadata> result : sent) {
                result.get();
            }
        }
    }

    /**
     * The values of every record of a topic, read from the beginning with a plain Kafka consumer of the test, or an
     * empty list when the topic does not exist.
     */
    static List<String> readAll(KafkaContainer kafka, String topic) {
        return readRecords(kafka, topic).stream().map(ConsumerRecord::value).toList();
    }

    /**
     * Every record of a topic, read from the beginning with a plain Kafka consumer of the test, or an empty list when
     * the topic does not exist.
     */
    static List<ConsumerRecord<String, String>> readRecords(KafkaContainer kafka, String topic) {
        Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))
                    && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Publishes a record, with its key and headers, with a plain Kafka producer of the test.
     */
    static void publish(KafkaContainer kafka, ProducerRecord<String, String> producerRecord) throws Exception {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.CLIENT_ID_CONFIG, "test-publisher");
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(producerRecord).get();
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
     * Whether a consumer group finished rebalancing with the given number of members, each one with partitions
     * assigned.
     */
    static boolean isStableWithAssignedMembers(KafkaContainer kafka, String group, int members) throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(group)).all().get()
                    .get(group);
            return description.groupState() == GroupState.STABLE
                    && description.members().size() == members
                    && description.members().stream()
                    .noneMatch(member -> member.assignment().topicPartitions().isEmpty());
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
