package org.reactivecommons.async.kafka.communications;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.internals.ProducerFactory;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Uses the real reactor-kafka sender over a {@link MockProducer} to reproduce what happens when the producer fails a
 * record, e.g. when the topic does not exist and the cluster has {@code auto.create.topics.enable=false}: the
 * producer keeps logging {@code UNKNOWN_TOPIC_OR_PARTITION} and fails the record after {@code max.block.ms}.
 */
class ReactiveMessageSenderProducerErrorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final TimeoutException TOPIC_NOT_PRESENT =
            new TimeoutException("Topic event.missing not present in metadata after 60000 ms.");

    private final MockProducer<String, byte[]> producer =
            new MockProducer<>(false, null, new StringSerializer(), new ByteArraySerializer());
    private final MessageConverter converter = mock(MessageConverter.class);

    @Test
    void shouldFailOnlyTheFailedSendWhenStopOnErrorIsDisabled() {
        ReactiveMessageSender sender = sender(senderOptions().stopOnError(false));

        // The producer fails the first record...
        Mono<Void> failed = sender.send("first").cache();
        failed.subscribe(v -> {
        }, e -> {
        });
        awaitUntil(() -> producer.history().size() == 1);
        producer.errorNext(TOPIC_NOT_PRESENT);

        StepVerifier.create(failed).expectErrorMatches(TOPIC_NOT_PRESENT::equals).verify(TIMEOUT);

        // ...and the send sequence is still alive for the next ones
        sendAndComplete(sender, "second", 2);
        sendAndComplete(sender, "third", 3);
        sendAndComplete(sender, "fourth", 4);
        sendAndComplete(sender, "fifth", 5);
    }

    @Test
    void shouldNeverCompleteTheFailedSendWithReactorKafkaDefaultOptions() {
        // Documents why stopOnError(false) is needed: with the default (true) reactor-kafka terminates the whole
        // send sequence, the confirmation never arrives and the caller's Mono hangs forever
        assertThat(senderOptions().stopOnError()).isTrue();
        ReactiveMessageSender sender = sender(senderOptions());

        Mono<Void> failed = sender.send("first").cache();
        failed.subscribe(v -> {
        }, e -> {
        });
        awaitUntil(() -> producer.history().size() == 1);
        producer.errorNext(TOPIC_NOT_PRESENT);

        StepVerifier.create(failed)
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(500))
                .thenCancel()
                .verify(TIMEOUT);
    }

    private void sendAndComplete(ReactiveMessageSender sender, String key, int expectedHistory) {
        Mono<Void> sent = sender.send(key).cache();
        sent.subscribe(v -> {
        }, e -> {
        });
        awaitUntil(() -> producer.history().size() == expectedHistory);
        producer.completeNext();
        StepVerifier.create(sent).expectComplete().verify(TIMEOUT);
    }

    private SenderOptions<String, byte[]> senderOptions() {
        return SenderOptions.<String, byte[]>create(Map.of("bootstrap.servers", "localhost:9092"))
                .withKeySerializer(new StringSerializer())
                .withValueSerializer(new ByteArraySerializer());
    }

    private ReactiveMessageSender sender(SenderOptions<String, byte[]> options) {
        // Each send uses its key as correlation id, so every test message gets a distinct one
        when(converter.toMessage(any())).thenAnswer(invocation -> {
            KafkaMessage.KafkaMessageProperties properties = new KafkaMessage.KafkaMessageProperties();
            properties.setTopic("event.missing");
            properties.setKey(invocation.getArgument(0));
            properties.setHeaders(Map.of());
            return new KafkaMessage("{}".getBytes(StandardCharsets.UTF_8), properties, "event");
        });
        ProducerFactory factory = new ProducerFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Producer<K, V> createProducer(SenderOptions<K, V> senderOptions) {
                return (Producer<K, V>) producer;
            }
        };
        return new ReactiveMessageSender(KafkaSender.create(factory, options), converter,
                mock(TopologyCreator.class), NoOpSchemaValidator.INSTANCE);
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + TIMEOUT);
            }
            Thread.onSpinWait();
        }
    }
}
