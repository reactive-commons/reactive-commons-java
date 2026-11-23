package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.kafka.communications.exceptions.TopicNotFoundException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.createTopic;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.readAll;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * Publishes with the domain event bus of the starter against a REAL Kafka broker.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class SenderKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Container
    private static final KafkaContainer KAFKA = KafkaScenarioSupport.kafkaContainer();

    @Configuration
    @EnableDomainEventBus
    static class Send {
    }

    /**
     * KC-07 (P05): the same event emitted 20 times at once, so 20 records with the same key: every emit completes,
     * and the 20 records are in the topic.
     */
    @Test
    void concurrentEmitsOfTheSameEventAllComplete() throws Exception {
        String topic = "sender.same-key";
        createTopic(KAFKA, topic, 1);

        try (AnnotationConfigApplicationContext context = startOnOwnDomain(KAFKA, "sender-same-key", DOMAIN,
                Send.class)) {
            DomainEventBus bus = context.getBean(DomainEventBus.class);

            StepVerifier.create(Flux.range(0, 20)
                            .flatMap(i -> Mono.from(
                                    bus.emit(DOMAIN, new DomainEvent<>(topic, "evt-1", Map.of("n", i)))))
                            .then())
                    .expectComplete()
                    .verify(WAIT);

            await().atMost(WAIT).until(() -> readAll(KAFKA, topic).size() == 20);
        }
    }

    /**
     * KC-11: a send to a topic that does not exist fails with {@link TopicNotFoundException} (the cluster is asked
     * without blocking the caller), instead of waiting for the producer to give up on its metadata.
     */
    @Test
    void anEmitToATopicThatDoesNotExistFailsAtOnce() {
        try (AnnotationConfigApplicationContext context = startOnOwnDomain(KAFKA, "sender-missing", DOMAIN,
                Send.class)) {
            DomainEventBus bus = context.getBean(DomainEventBus.class);

            StepVerifier.create(bus.emit(DOMAIN, new DomainEvent<>("sender.missing", "evt-1", Map.of())))
                    .expectError(TopicNotFoundException.class)
                    .verify(WAIT);
        }
    }
}
