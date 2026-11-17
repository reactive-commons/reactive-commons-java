package org.reactivecommons.async.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.impl.config.annotations.EnableEventListeners;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.committedOffset;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * A record without value (the tombstone of a compacted topic), against a REAL Kafka broker: it does not stop the
 * consumption of its partition, and the consumer is not created again.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerTombstoneKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Map<String, Object> COUNT_CONSUMERS = Map.of(CountingConsumerInterceptor.PROPERTY,
            CountingConsumerInterceptor.class.getName());

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaScenarioSupport.KAFKA_IMAGE);

    @Configuration
    @EnableEventListeners
    static class ListenEvents {
    }

    @Configuration
    @EnableTopicListeners
    static class ListenTopics {
    }

    @Test
    void aTombstoneInAnEventTopicIsSkippedAndTheNextEventIsHandled() throws Exception {
        String event = "tombstone.events";
        String app = "tombstone-events";
        publish(KAFKA, event, event(event, "evt-1"));
        publish(KAFKA, new ProducerRecord<>(event, 0, "evt-deleted", null));
        publish(KAFKA, event, event(event, "evt-2"));
        List<String> handled = new CopyOnWriteArrayList<>();
        HandlerRegistry handlers = HandlerRegistry.register().listenDomainEvent(DOMAIN, event, domainEvent -> {
            handled.add(domainEvent.getEventId());
            return Mono.empty();
        }, Map.class);

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA, app, DOMAIN, COUNT_CONSUMERS,
                handlers, ListenEvents.class)) {
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, app + "-events", event, 0) == 3);
            assertThat(handled).as("events handled").containsExactly("evt-1", "evt-2");
            assertThat(CountingConsumerInterceptor.consumersCreated(app + "-events")).as("consumers created")
                    .isEqualTo(1);
        }
    }

    @Test
    void aTombstoneInARawTopicIsGivenToTheHandlerWithoutBody() throws Exception {
        String topic = "tombstone.raw";
        String group = "tombstone-raw";
        publish(KAFKA, new ProducerRecord<>(topic, 0, "order-1", "created"));
        publish(KAFKA, new ProducerRecord<>(topic, 0, "order-1", null));
        publish(KAFKA, new ProducerRecord<>(topic, 0, "order-2", "created"));
        List<String> handled = new CopyOnWriteArrayList<>();
        HandlerRegistry handlers = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            byte[] body = ((Message) message).getBody();
            String key = ((Message) message).getProperties().getKey();
            handled.add(key + "=" + (body == null ? null : new String(body, StandardCharsets.UTF_8)));
            return Mono.empty();
        });

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA, group, DOMAIN, COUNT_CONSUMERS,
                handlers, ListenTopics.class)) {
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 0) == 3);
            assertThat(handled).as("records handled").containsExactly("order-1=created", "order-1=null",
                    "order-2=created");
            assertThat(CountingConsumerInterceptor.consumersCreated(group)).as("consumers created").isEqualTo(1);
        }
    }

    private static String event(String name, String eventId) {
        return "{\"name\":\"" + name + "\",\"eventId\":\"" + eventId + "\",\"data\":{\"amount\":10}}";
    }
}
