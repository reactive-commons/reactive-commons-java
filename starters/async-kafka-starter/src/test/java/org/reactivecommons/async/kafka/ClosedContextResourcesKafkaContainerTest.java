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
            "ApplicationTopicListener", "errorReporterScheduler");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaScenarioSupport.KAFKA_IMAGE);

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
                    .anyMatch(name -> name.startsWith("KMessageSender"));
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> resourceThreadsStartedAfter(before).isEmpty());
        assertThat(resourceThreadsStartedAfter(before)).as("threads left by the closed context").isEmpty();
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
