package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.PRODUCER_THREAD;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.threads;

/**
 * A service that only consumes from Kafka (its default domain lives in RabbitMQ, so the Kafka 'app' domain is
 * disabled and the handlers are registered on a domain of their own) must not open a Kafka producer: the producer
 * connects and authenticates against the cluster and keeps a network thread and its buffers for nothing.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerOnlyKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final String TOPIC = "consumer-only.topic";
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaScenarioSupport.KAFKA_IMAGE);

    @Configuration
    @EnableTopicListeners
    static class ListenTopic {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenTopic(DOMAIN, TOPIC, message -> {
                        RECEIVED.add(new String(((Message) message).getBody(), StandardCharsets.UTF_8));
                        return Mono.empty();
                    });
        }
    }

    @Configuration
    @EnableDomainEventBus
    static class SendEvents {
    }

    @Test
    void aServiceThatOnlyConsumesDoesNotOpenAProducer() throws Exception {
        publish(KAFKA, TOPIC, "consumed-1");

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA, "consumer-only", DOMAIN,
                ListenTopic.class)) {
            await().atMost(Duration.ofSeconds(30)).until(() -> RECEIVED.contains("consumed-1"));

            List<String> producers = threads("consumer-only", PRODUCER_THREAD);
            System.out.printf("[REAL BROKER] consumer-only service: received=%s, producer threads=%s%n",
                    RECEIVED, producers);
            assertThat(producers).as("producer threads of a service that only consumes").isEmpty();
        }
    }

    @Test
    void theProducerIsOpenedOnceTheFirstEventIsEmitted() {
        try (AnnotationConfigApplicationContext context = startOnOwnDomain(KAFKA, "first-emit", DOMAIN,
                SendEvents.class)) {
            assertThat(threads("first-emit", PRODUCER_THREAD)).as("producer threads before emitting").isEmpty();

            DomainEventBus bus = context.getBean(DomainEventBus.class);
            for (int i = 0; i < 3; i++) {
                Mono.from(bus.emit(DOMAIN, new DomainEvent<>(TOPIC, "id-" + i, Map.of("n", i))))
                        .block(Duration.ofSeconds(30));
            }

            assertThat(threads("first-emit", PRODUCER_THREAD)).as("producer threads after emitting").hasSize(1);
        }
    }
}
