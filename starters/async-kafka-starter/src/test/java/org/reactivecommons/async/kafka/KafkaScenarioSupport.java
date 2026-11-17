package org.reactivecommons.async.kafka;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.reactivecommons.async.starter.config.ReactiveCommonsListenersConfig;
import org.reactivecommons.async.starter.impl.common.kafka.RCKafkaConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.kafka.KafkaContainer;

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
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.application.name", appName);
        properties.put("reactive.commons.kafka.app.enabled", "false");
        properties.put("reactive.commons.kafka." + domain + ".enabled", "true");
        properties.put("reactive.commons.kafka." + domain + ".connectionProperties.bootstrap-servers",
                kafka.getBootstrapServers());
        properties.put("reactive.commons.kafka." + domain + ".connectionProperties.consumer.auto-offset-reset",
                "earliest");
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("scenario", properties));
        // The configurations of the application first, so the default beans of the starter (e.g. an empty
        // HandlerRegistry) back off, as they do in a Spring Boot application
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
