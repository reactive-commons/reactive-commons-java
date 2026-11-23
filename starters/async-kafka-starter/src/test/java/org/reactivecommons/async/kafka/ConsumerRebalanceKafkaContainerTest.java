package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.body;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.createTopic;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.isStableWithAssignedMembers;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * A rebalance of the consumer group, against a REAL Kafka broker. reactor-kafka delays the revocation of the
 * partitions, up to {@code maxDelayRebalance} (60 s), while a record it delivered is not acknowledged: every record,
 * the discarded ones included, must end up acknowledged, or each later rebalance of the group stalls that long.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerRebalanceKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration MAX_REBALANCE = Duration.ofSeconds(10);

    @Container
    private static final KafkaContainer KAFKA = KafkaScenarioSupport.kafkaContainer();

    @Configuration
    @EnableTopicListeners
    static class ListenTopics {
    }

    @Test
    void afterADiscardedMessageASecondInstanceGetsItsPartitionWithoutDelay() throws Exception {
        String topic = "rebalance.discarded";
        String group = "rebalance-discarded";
        createTopic(KAFKA, topic, 2);
        publish(KAFKA, topic, 0, "fail", "ok");
        List<String> handled = new CopyOnWriteArrayList<>();
        HandlerRegistry handlers = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            String value = body(message);
            handled.add(value);
            return "fail".equals(value) ? Mono.error(new IllegalStateException("dependency down")) : Mono.empty();
        });
        // Retried once, then discarded: there is no DLQ
        Map<String, Object> discardAfterOneRetry = Map.of("maxRetries", "1", "retryDelay", "100");

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain(group, DOMAIN, discardAfterOneRetry), handlers, ListenTopics.class)) {
            // The partition is handled in order: once "ok" is handled, "fail" was already discarded
            await().atMost(WAIT).until(() -> handled.contains("ok"));
            assertThat(handled).as("deliveries to the handler").containsExactly("fail", "fail", "ok");

            long start = System.nanoTime();
            try (AnnotationConfigApplicationContext secondInstance = startOnOwnDomain(KAFKA,
                    new OwnDomain(group, DOMAIN, discardAfterOneRetry), handlers, ListenTopics.class)) {
                await().atMost(Duration.ofSeconds(90)).until(() -> isStableWithAssignedMembers(KAFKA, group, 2));
                Duration rebalance = Duration.ofNanos(System.nanoTime() - start);
                assertThat(rebalance).as("time until the second instance gets its partition")
                        .isLessThan(MAX_REBALANCE);
            }
        }
    }
}
