package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.body;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.committedOffset;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.createTopic;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * The messages of a partition are handled one at a time, in the order of the partition, against a REAL Kafka
 * broker: a message still being handled is never committed past by a later one, so it is delivered again after a
 * restart. Different partitions are handled at the same time.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerOrderingKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final Duration WAIT = Duration.ofSeconds(30);
    /**
     * Longer than the interval of the periodic commit (5 s), so a commit made meanwhile would be seen.
     */
    private static final Duration LONGER_THAN_A_COMMIT = Duration.ofSeconds(7);

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaScenarioSupport.KAFKA_IMAGE);

    @Configuration
    @EnableTopicListeners
    static class ListenTopics {
    }

    @Test
    void theMessagesOfAPartitionAreHandledOneAtATimeInOrder() throws Exception {
        String topic = "ordering.in-order";
        createTopic(KAFKA, topic, 1);
        publish(KAFKA, topic, 0, "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        List<String> completed = new CopyOnWriteArrayList<>();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        // The earlier a message, the longer its handler takes: handled at the same time, they would complete
        // in reverse order
        HandlerRegistry handlers = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            String value = body(message);
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            return Mono.delay(Duration.ofMillis((10 - Long.parseLong(value)) * 50))
                    .doOnNext(ignored -> {
                        running.decrementAndGet();
                        completed.add(value);
                    })
                    .then();
        });

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA, "ordering-in-order", DOMAIN,
                Map.of(), handlers, ListenTopics.class)) {
            await().atMost(WAIT).until(() -> completed.size() == 10);
            assertThat(completed).as("order in which the handlers completed")
                    .containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
            assertThat(peak).as("handlers of the partition running at the same time").hasValue(1);
        }
    }

    @Test
    void aMessageStillBeingHandledIsNotCommittedPastAndIsDeliveredAgainAfterARestart() throws Exception {
        String topic = "ordering.in-flight";
        String group = "ordering-in-flight";
        createTopic(KAFKA, topic, 1);
        publish(KAFKA, topic, 0, "slow", "fast-1", "fast-2");
        List<String> started = new CopyOnWriteArrayList<>();
        HandlerRegistry slowFirst = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            String value = body(message);
            started.add(value);
            return value.equals("slow") ? Mono.never() : Mono.empty();
        });

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA, group, DOMAIN, Map.of(),
                slowFirst, ListenTopics.class)) {
            await().atMost(WAIT).until(() -> started.contains("slow"));
            Thread.sleep(LONGER_THAN_A_COMMIT.toMillis());
            assertThat(started).as("handlers started while 'slow' is still running").containsExactly("slow");
            assertThat(committedOffset(KAFKA, group, topic, 0)).as("committed offset").isEqualTo(-1);
        }

        List<String> afterRestart = new CopyOnWriteArrayList<>();
        HandlerRegistry recovered = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            afterRestart.add(body(message));
            return Mono.empty();
        });
        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA, group, DOMAIN, Map.of(),
                recovered, ListenTopics.class)) {
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 0) == 3);
            assertThat(afterRestart).as("messages delivered after the restart")
                    .containsExactly("slow", "fast-1", "fast-2");
        }
    }

    @Test
    void aMessageStillBeingHandledDoesNotHoldTheOtherPartitions() throws Exception {
        String topic = "ordering.partitions";
        String group = "ordering-partitions";
        createTopic(KAFKA, topic, 2);
        publish(KAFKA, topic, 0, "blocked");
        publish(KAFKA, topic, 1, "free-1", "free-2");
        List<String> completed = new CopyOnWriteArrayList<>();
        HandlerRegistry handlers = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            String value = body(message);
            return value.equals("blocked") ? Mono.never() : Mono.fromRunnable(() -> completed.add(value));
        });

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA, group, DOMAIN, Map.of(),
                handlers, ListenTopics.class)) {
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 1) == 2);
            assertThat(completed).as("messages of the other partition").containsExactly("free-1", "free-2");
            assertThat(committedOffset(KAFKA, group, topic, 0)).as("committed offset of the blocked partition")
                    .isEqualTo(-1);
        }
    }
}
