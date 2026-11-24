package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * Closes an application context that consumed and published against a REAL Kafka broker: its producer, its consumer,
 * its admin clients and the threads of its sender and its listeners are released.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ClosedContextResourcesKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final String TOPIC = "closed-context.topic";
    private static final AtomicInteger RECEIVED = new AtomicInteger();
    /**
     * The threads of the Kafka clients, of the sender and of the listeners.
     */
    private static final List<String> RESOURCE_THREADS = List.of("kafka-producer-network-thread",
            "kafka-admin-client-thread", "kafka-coordinator-heartbeat-thread", "reactive-kafka-", "KMessageSender",
            "kafka-listeners-", "ApplicationTopicListener", "errorReporterScheduler",
            // KC-17: the refresh of the metrics of each Kafka client, bound while the client is open
            "micrometer-kafka-metrics");

    private static final String ADMIN_THREAD = "kafka-admin-client-thread";

    @Container
    private static final KafkaContainer KAFKA = KafkaScenarioSupport.kafkaContainer();

    @Configuration
    @EnableTopicListeners
    static class ListenTopics {
    }

    @Configuration
    @EnableTopicListeners
    @EnableDomainEventBus
    static class ListenAndSend {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenTopic(DOMAIN, TOPIC, message -> {
                        RECEIVED.incrementAndGet();
                        return Mono.empty();
                    });
        }
    }

    @Test
    void theResourcesOfAClosedContextAreReleased() throws Exception {
        publish(KAFKA, TOPIC, "created");
        Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());

        try (AnnotationConfigApplicationContext context = startOnOwnDomain(KAFKA, "closed-context", DOMAIN,
                ListenAndSend.class)) {
            Mono.from(context.getBean(DomainEventBus.class)
                    .emit(DOMAIN, new DomainEvent<>(TOPIC, "1", Map.of()))).block(Duration.ofSeconds(30));
            await().atMost(Duration.ofSeconds(30)).until(() -> RECEIVED.get() >= 2);
            assertThat(resourceThreadsStartedAfter(before)).as("threads while the context is running")
                    .anyMatch(name -> name.startsWith("kafka-producer-network-thread"))
                    // The sender has no threads of its own: the callers continue on Reactor's bounded scheduler
                    .noneMatch(name -> name.startsWith("KMessageSender"))
                    // KC-31: one admin client for the domain, shared by its topology creator and its health check
                    .filteredOn(name -> name.startsWith(ADMIN_THREAD)).hasSize(1);
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> resourceThreadsStartedAfter(before).isEmpty());
        assertThat(resourceThreadsStartedAfter(before)).as("threads left by the closed context").isEmpty();
    }

    /**
     * With the default domain enabled, the domain shares its admin client with the discard notifier that
     * publishes the discards of the other domains: one admin client per domain, none left once closed.
     */
    @Test
    void eachDomainHasASingleAdminClient() throws Exception {
        String appTopic = "closed-context.app";
        String accountsTopic = "closed-context.accounts";
        publish(KAFKA, appTopic, "created");
        publish(KAFKA, accountsTopic, "created");
        AtomicInteger received = new AtomicInteger();
        HandlerRegistry handlers = HandlerRegistry.register()
                .listenTopic("app", appTopic, message -> {
                    received.incrementAndGet();
                    return Mono.empty();
                })
                .listenTopic(DOMAIN, accountsTopic, message -> {
                    received.incrementAndGet();
                    return Mono.empty();
                });
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.application.name", "two-domains");
        for (String domain : List.of("app", DOMAIN)) {
            properties.put("reactive.commons.kafka." + domain + ".connectionProperties.bootstrap-servers",
                    KAFKA.getBootstrapServers());
            properties.put("reactive.commons.kafka." + domain + ".connectionProperties.consumer.auto-offset-reset",
                    "earliest");
        }
        Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());

        try (AnnotationConfigApplicationContext ignored = KafkaScenarioSupport.start(properties, handlers,
                ListenTopics.class)) {
            await().atMost(Duration.ofSeconds(30)).until(() -> received.get() >= 2);
            assertThat(resourceThreadsStartedAfter(before)).as("threads while the context is running")
                    .filteredOn(name -> name.startsWith(ADMIN_THREAD)).hasSize(2);
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> resourceThreadsStartedAfter(before).isEmpty());
    }

    private static List<String> resourceThreadsStartedAfter(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> !before.contains(thread))
                .map(Thread::getName)
                .filter(name -> RESOURCE_THREADS.stream().anyMatch(name::startsWith))
                .sorted()
                .toList();
    }
}
