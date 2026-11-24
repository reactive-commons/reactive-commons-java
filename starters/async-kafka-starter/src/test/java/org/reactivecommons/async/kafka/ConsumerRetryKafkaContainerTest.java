package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.impl.config.annotations.EnableEventListeners;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
import org.reactivecommons.async.kafka.KafkaScenarioSupport.OwnDomain;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.body;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.committedOffset;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.readAll;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * With the default retry policy ({@code maxRetries: -1}, {@code withDLQRetry: false}) a message whose handler fails
 * is retried in place, against a REAL Kafka broker: its offset is not committed until the handler succeeds, so the
 * message is neither skipped nor lost. A message that can never be handled is skipped without retries.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerRetryKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final Map<String, Object> RETRY_EVERY_200_MS = Map.of("retryDelay", "200");
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Container
    private static final KafkaContainer KAFKA = KafkaScenarioSupport.kafkaContainer();

    @Configuration
    @EnableTopicListeners
    static class ListenTopics {
    }

    @Configuration
    @EnableEventListeners
    static class ListenEvents {
    }

    /**
     * With {@code withDLQRetry: true} and the default {@code maxRetries} (-1), a failed event is retried 10 times,
     * as documented, and then sent to its {@code .dlq} topic.
     */
    @Test
    void withADlqAFailedEventIsRetriedTenTimesAndThenSentToItsDlq() throws Exception {
        String event = "retry.dlq-default";
        String app = "retry-dlq-default";
        publish(KAFKA, event, "{\"name\":\"" + event + "\",\"eventId\":\"evt-1\",\"data\":{\"amount\":10}}");
        AtomicInteger attempts = new AtomicInteger();
        HandlerRegistry handlers = HandlerRegistry.register().listenDomainEvent(DOMAIN, event, domainEvent -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("dependency down"));
        }, Map.class);

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain(app, DOMAIN, Map.of("withDLQRetry", "true", "retryDelay", "100")), handlers,
                ListenEvents.class)) {
            await().atMost(WAIT).until(() -> !readAll(KAFKA, event + ".dlq").isEmpty());
            assertThat(attempts).as("deliveries to the handler: the first one and 10 retries").hasValue(11);
            assertThat(readAll(KAFKA, event + ".dlq")).as("discarded events")
                    .singleElement().asString().contains("\"eventId\":\"evt-1\"");
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, app + "-events", event, 0) == 1);
        }
    }

    @Test
    void aFailedMessageIsRetriedUntilItsHandlerSucceeds() throws Exception {
        String topic = "retry.until-success";
        String group = "retry-until-success";
        publish(KAFKA, topic, "payment-1");
        AtomicBoolean recovered = new AtomicBoolean();
        List<String> attempts = new CopyOnWriteArrayList<>();
        HandlerRegistry handlers = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            attempts.add(body(message));
            return recovered.get() ? Mono.empty() : Mono.error(new IllegalStateException("dependency down"));
        });

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain(group, DOMAIN, RETRY_EVERY_200_MS), handlers, ListenTopics.class)) {
            await().atMost(WAIT).until(() -> attempts.size() >= 3);
            assertThat(committedOffset(KAFKA, group, topic, 0))
                    .as("offset committed while the handler keeps failing").isEqualTo(-1);

            recovered.set(true);

            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 0) == 1);
            assertThat(attempts).as("deliveries to the handler").allMatch("payment-1"::equals).hasSizeGreaterThan(3);
        }
    }

    @Test
    void aMessageThatCanNeverBeHandledIsSkippedWithoutRetries() throws Exception {
        String topic = "retry.poison";
        String group = "retry-poison";
        publish(KAFKA, topic, "poison");
        List<String> attempts = new CopyOnWriteArrayList<>();
        HandlerRegistry handlers = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            String value = body(message);
            attempts.add(value);
            return value.equals("poison")
                    ? Mono.error(new MessageConversionException("not a valid payload"))
                    : Mono.empty();
        });

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain(group, DOMAIN, RETRY_EVERY_200_MS), handlers, ListenTopics.class)) {
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 0) == 1);
            publish(KAFKA, topic, "next");
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 0) == 2);
            assertThat(attempts).as("deliveries to the handler").containsExactly("poison", "next");
        }
    }
}
