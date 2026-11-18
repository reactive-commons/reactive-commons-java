package org.reactivecommons.async.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
import org.reactivecommons.async.kafka.KafkaScenarioSupport.OwnDomain;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
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
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.readAll;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.threads;

/**
 * The raw topics of a domain share one consumer, against a REAL Kafka broker: each record reaches the handler of its
 * topic, and each topic keeps its own DLQ and its tombstones.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerTopicsKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final Duration WAIT = Duration.ofSeconds(60);

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaScenarioSupport.KAFKA_IMAGE);

    @Configuration
    @EnableTopicListeners
    static class ListenTopics {
    }

    @Test
    void oneConsumerGivesTheRecordsOfEveryTopicToTheHandlerOfTheirTopic() throws Exception {
        String group = "topics-one-consumer";
        List<String> topics = List.of("topics.one", "topics.two", "topics.three");
        List<String> received = new CopyOnWriteArrayList<>();
        HandlerRegistry handlers = HandlerRegistry.register();
        for (String topic : topics) {
            publish(KAFKA, topic, "record of " + topic);
            handlers.listenTopic(DOMAIN, topic, message -> Mono.fromRunnable(() ->
                    received.add(topic + " <- " + body(message))));
        }

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA, new OwnDomain(group, DOMAIN),
                handlers, ListenTopics.class)) {
            await().atMost(WAIT).until(() -> received.size() == 3);
            assertThat(received).containsExactlyInAnyOrder(
                    "topics.one <- record of topics.one",
                    "topics.two <- record of topics.two",
                    "topics.three <- record of topics.three");
            assertThat(threads(group + "-", "reactive-kafka-")).as("threads of the Kafka consumers").hasSize(1);
        }
    }

    @Test
    void eachTopicKeepsItsOwnDlqAndItsTombstones() throws Exception {
        String group = "topics-dlq";
        List<String> topics = List.of("topics.dlq.one", "topics.dlq.two");
        List<String> tombstones = new CopyOnWriteArrayList<>();
        HandlerRegistry handlers = HandlerRegistry.register();
        for (String topic : topics) {
            handlers.listenTopic(DOMAIN, topic, message -> {
                if (((Message) message).getBody() == null) {
                    return Mono.fromRunnable(() -> tombstones.add(topic + ":" + key(message)));
                }
                return Mono.error(new IllegalStateException("dependency down"));
            }, creator -> ((TopologyCreator) creator).createTopics(List.of(topic))
                    .then(((TopologyCreator) creator).createDlqTopics(List.of(topic))));
            publish(KAFKA, new ProducerRecord<>(topic, "deleted", null));
            // With a key: a record without one cannot be sent to a DLQ yet (KC-07)
            publish(KAFKA, new ProducerRecord<>(topic, "order-1", "failed in " + topic));
        }

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain(group, DOMAIN, Map.of("withDLQRetry", "true", "maxRetries", "0", "retryDelay", "100")),
                handlers, ListenTopics.class)) {
            await().atMost(WAIT).until(() -> topics.stream().noneMatch(topic -> readAll(KAFKA, topic + ".dlq")
                    .isEmpty()));
            for (String topic : topics) {
                assertThat(readAll(KAFKA, topic + ".dlq")).as("DLQ of " + topic).containsExactly("failed in " + topic);
            }
            await().atMost(WAIT).until(() -> tombstones.size() == 2);
            assertThat(tombstones).containsExactlyInAnyOrder("topics.dlq.one:deleted", "topics.dlq.two:deleted");
        }
    }

    private static String key(RawMessage message) {
        return ((KafkaMessage) message).getProperties().getKey();
    }
}
