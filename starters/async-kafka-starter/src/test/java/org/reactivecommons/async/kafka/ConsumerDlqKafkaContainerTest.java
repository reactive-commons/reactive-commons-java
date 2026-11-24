package org.reactivecommons.async.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableEventListeners;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
import org.reactivecommons.async.kafka.KafkaScenarioSupport.OwnDomain;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.committedOffset;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.createTopic;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.readRecords;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * A message that exhausted its retries is sent to the DLQ of its topic, against a REAL Kafka broker, and its offset is
 * committed only once it is there: a message that cannot be sent to its DLQ yet is kept, not lost.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerDlqKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final Duration WAIT = Duration.ofSeconds(60);
    /**
     * Longer than the interval of the periodic commit (5 s), so a commit made meanwhile would be seen.
     */
    private static final Duration LONGER_THAN_A_COMMIT = Duration.ofSeconds(7);

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
     * A handler that always fails, whose topology setup creates the DLQ of the topic, as a raw topic listener must.
     */
    private static HandlerRegistry failingHandlerWithItsDlq(String topic) {
        return HandlerRegistry.register().listenTopic(DOMAIN, topic,
                message -> Mono.error(new IllegalStateException("dependency down")),
                creator -> ((TopologyCreator) creator).createDlqTopics(List.of(topic)));
    }

    @Test
    void aRawMessageThatExhaustedItsRetriesIsSentAsItIsToTheDlqOfItsTopic() throws Exception {
        String topic = "dlq.raw";
        String group = "dlq-raw";
        ProducerRecord<String, String> original = new ProducerRecord<>(topic, "order-1", "{\"id\":1}");
        original.headers().add("trace-id", "abc".getBytes(StandardCharsets.UTF_8));
        publish(KAFKA, original);

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain(group, DOMAIN, Map.of("withDLQRetry", "true", "maxRetries", "1", "retryDelay", "100")),
                failingHandlerWithItsDlq(topic), ListenTopics.class)) {
            await().atMost(WAIT).until(() -> !readRecords(KAFKA, topic + ".dlq").isEmpty());
            List<ConsumerRecord<String, String>> discarded = readRecords(KAFKA, topic + ".dlq");
            assertThat(discarded).as("records of the DLQ").hasSize(1);
            ConsumerRecord<String, String> dlq = discarded.get(0);
            assertThat(dlq.key()).as("key").isEqualTo("order-1");
            assertThat(dlq.value()).as("value").isEqualTo("{\"id\":1}");
            assertThat(header(dlq, "trace-id")).as("header").isEqualTo("abc");
            assertThat(header(dlq, "x-dlq-original-topic")).as("original topic").isEqualTo(topic);
            assertThat(header(dlq, "x-dlq-original-partition")).as("original partition").isEqualTo("0");
            assertThat(header(dlq, "x-dlq-original-offset")).as("original offset").isEqualTo("0");
            assertThat(header(dlq, "x-dlq-exception-class")).as("exception class")
                    .isEqualTo(IllegalStateException.class.getName());
            assertThat(header(dlq, "x-dlq-exception-message")).as("exception message").isEqualTo("dependency down");
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 0) == 1);
        }
    }

    /**
     * A record without key, with a header without value, reaches the DLQ as it is: the sender confirms each send by
     * an id of its own, not by the key of its record, and writes a header without value.
     */
    @Test
    void aRawMessageWithoutKeyAndWithAHeaderWithoutValueIsSentAsItIsToTheDlq() throws Exception {
        String topic = "dlq.raw-no-key";
        String group = "dlq-raw-no-key";
        ProducerRecord<String, String> original = new ProducerRecord<>(topic, null, "{\"id\":3}");
        original.headers().add("no-value", null);
        original.headers().add("name", "ñandú".getBytes(StandardCharsets.UTF_8));
        publish(KAFKA, original);

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain(group, DOMAIN, Map.of("withDLQRetry", "true", "maxRetries", "1", "retryDelay", "100")),
                failingHandlerWithItsDlq(topic), ListenTopics.class)) {
            await().atMost(WAIT).until(() -> !readRecords(KAFKA, topic + ".dlq").isEmpty());
            ConsumerRecord<String, String> dlq = readRecords(KAFKA, topic + ".dlq").get(0);
            assertThat(dlq.key()).as("key").isNull();
            assertThat(dlq.value()).as("value").isEqualTo("{\"id\":3}");
            Header noValue = dlq.headers().lastHeader("no-value");
            assertThat(noValue).as("header without value").isNotNull();
            assertThat(noValue.value()).as("value of the header without value").isNull();
            assertThat(header(dlq, "name")).as("non-ASCII header").isEqualTo("ñandú");
            assertThat(header(dlq, "x-dlq-original-topic")).as("original topic").isEqualTo(topic);
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 0) == 1);
        }
    }

    /**
     * The headers reach the DLQ with their bytes, not read as text: a binary value (e.g. the id of a schema) is not
     * corrupted. With a repeated key, the last value is kept, the one the handler sees.
     */
    @Test
    void aRawMessageWithBinaryHeadersReachesTheDlqWithTheBytesOfEachHeader() throws Exception {
        String topic = "dlq.raw-binary";
        String group = "dlq-raw-binary";
        byte[] binary = {0x00, (byte) 0xFF, (byte) 0xFE, (byte) 0x80};
        byte[] lastRepeated = {(byte) 0xC3, 0x28};
        ProducerRecord<String, String> original = new ProducerRecord<>(topic, "order-4", "{\"id\":4}");
        original.headers().add("schema-id", binary);
        original.headers().add("repeated", "first".getBytes(StandardCharsets.UTF_8));
        original.headers().add("repeated", lastRepeated);
        publish(KAFKA, original);

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain(group, DOMAIN, Map.of("withDLQRetry", "true", "maxRetries", "1", "retryDelay", "100")),
                failingHandlerWithItsDlq(topic), ListenTopics.class)) {
            await().atMost(WAIT).until(() -> !readRecords(KAFKA, topic + ".dlq").isEmpty());
            ConsumerRecord<String, String> dlq = readRecords(KAFKA, topic + ".dlq").get(0);
            assertArrayEquals(binary, dlq.headers().lastHeader("schema-id").value(), "bytes of the binary header");
            assertThat(dlq.headers().headers("repeated")).as("values of the repeated header").hasSize(1);
            assertArrayEquals(lastRepeated, dlq.headers().lastHeader("repeated").value(), "last repeated value");
            assertThat(header(dlq, "x-dlq-original-topic")).as("original topic").isEqualTo(topic);
            assertThat(header(dlq, "x-dlq-original-offset")).as("original offset").isEqualTo("0");
            assertThat(header(dlq, "x-dlq-exception-class")).as("exception class")
                    .isEqualTo(IllegalStateException.class.getName());
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 0) == 1);
        }
    }

    @Test
    void aDiscardedEventCarriesWhereItCameFromAndWhyItWasDiscarded() throws Exception {
        String event = "dlq.event";
        String app = "dlq-event";
        publish(KAFKA, event, "{\"name\":\"" + event + "\",\"eventId\":\"evt-1\",\"data\":{\"amount\":10}}");
        publish(KAFKA, event, "{\"name\":\"" + event + "\",\"eventId\":\"evt-2\",\"data\":{\"amount\":20}}");
        HandlerRegistry handlers = HandlerRegistry.register().listenDomainEvent(DOMAIN, event, domainEvent ->
                "evt-2".equals(domainEvent.getEventId())
                        ? Mono.error(new IllegalStateException("dependency down"))
                        : Mono.empty(), Map.class);

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain(app, DOMAIN, Map.of("withDLQRetry", "true", "maxRetries", "1", "retryDelay", "100")),
                handlers, ListenEvents.class)) {
            await().atMost(WAIT).until(() -> !readRecords(KAFKA, event + ".dlq").isEmpty());
            ConsumerRecord<String, String> dlq = readRecords(KAFKA, event + ".dlq").get(0);
            assertThat(dlq.value()).as("value").contains("\"eventId\":\"evt-2\"");
            assertThat(header(dlq, "x-dlq-original-topic")).as("original topic").isEqualTo(event);
            assertThat(header(dlq, "x-dlq-original-partition")).as("original partition").isEqualTo("0");
            assertThat(header(dlq, "x-dlq-original-offset")).as("original offset").isEqualTo("1");
            assertThat(header(dlq, "x-dlq-exception-class")).as("exception class")
                    .isEqualTo(IllegalStateException.class.getName());
            assertThat(header(dlq, "x-dlq-exception-message")).as("exception message").isEqualTo("dependency down");
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, app + "-events", event, 0) == 2);
        }
    }

    private static String header(ConsumerRecord<String, String> consumerRecord, String name) {
        Header header = consumerRecord.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void aMessageThatCannotBeSentToItsDlqYetIsNotCommittedUntilItIs() throws Exception {
        String topic = "dlq.missing";
        String group = "dlq-missing";
        publish(KAFKA, new ProducerRecord<>(topic, "order-2", "{\"id\":2}"));

        // The application does not create the topology, and the DLQ topic does not exist yet
        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain(group, DOMAIN, Map.of("withDLQRetry", "true", "maxRetries", "0", "retryDelay", "100",
                        "createTopology", "false")),
                failingHandlerWithItsDlq(topic), ListenTopics.class)) {
            // Waits without any condition to wait for: nothing must happen meanwhile
            assertThat(new CountDownLatch(1).await(LONGER_THAN_A_COMMIT.toMillis(), TimeUnit.MILLISECONDS)).isFalse();
            assertThat(committedOffset(KAFKA, group, topic, 0))
                    .as("offset committed while the DLQ cannot be published to").isEqualTo(-1);

            createTopic(KAFKA, topic + ".dlq", 1);

            await().atMost(WAIT).until(() -> !readRecords(KAFKA, topic + ".dlq").isEmpty());
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 0) == 1);
        }
    }
}
