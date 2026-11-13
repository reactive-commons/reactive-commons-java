package org.reactivecommons.async.kafka.communications;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.internals.ProducerFactory;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Kafka producer is created on the first send, not with the sender: a service that only consumes must not open
 * a producer. Closing the sender closes the producer, if it was created.
 */
class ReactiveMessageSenderLifecycleTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final MockProducer<String, byte[]> producer =
            new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    private final AtomicInteger producersCreated = new AtomicInteger();
    private final MessageConverter converter = mock(MessageConverter.class);

    @Test
    void shouldNotCreateTheProducerUntilTheFirstSend() throws InterruptedException {
        sender(factory());

        Thread.sleep(300);

        assertThat(producersCreated).as("producers created without sending").hasValue(0);
    }

    @Test
    void shouldCreateASingleProducerForConcurrentSends() {
        ReactiveMessageSender sender = sender(factory());

        StepVerifier.create(Flux.range(0, 50)
                        .flatMap(i -> sender.send("key-" + i).subscribeOn(Schedulers.parallel()))
                        .then())
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(producersCreated).as("producers created").hasValue(1);
        assertThat(producer.history()).hasSize(50);
    }

    @Test
    void shouldCloseTheProducerAndRejectLaterSends() {
        ReactiveMessageSender sender = sender(factory());
        StepVerifier.create(sender.send("first")).expectComplete().verify(TIMEOUT);

        sender.close();
        sender.close(); // idempotent

        assertThat(producer.closed()).as("producer closed").isTrue();
        StepVerifier.create(sender.send("after-close"))
                .expectError(IllegalStateException.class)
                .verify(TIMEOUT);
    }

    @Test
    void shouldNotCreateTheProducerWhenClosedWithoutSending() {
        ReactiveMessageSender sender = sender(factory());

        sender.close();

        assertThat(producersCreated).as("producers created").hasValue(0);
    }

    @Test
    void shouldFailTheSendWhenTheProducerCannotBeCreated() {
        IllegalArgumentException invalidConfig = new IllegalArgumentException("invalid producer configuration");
        ReactiveMessageSender sender = sender(new ProducerFactory() {
            @Override
            public <K, V> Producer<K, V> createProducer(SenderOptions<K, V> senderOptions) {
                throw invalidConfig;
            }
        });

        StepVerifier.create(sender.send("first"))
                .expectErrorMatches(invalidConfig::equals)
                .verify(TIMEOUT);
    }

    private ProducerFactory factory() {
        return new ProducerFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Producer<K, V> createProducer(SenderOptions<K, V> senderOptions) {
                producersCreated.incrementAndGet();
                return (Producer<K, V>) producer;
            }
        };
    }

    private ReactiveMessageSender sender(ProducerFactory factory) {
        // Each send uses its key as correlation id, so every test message gets a distinct one
        when(converter.toMessage(any())).thenAnswer(invocation -> {
            KafkaMessage.KafkaMessageProperties properties = new KafkaMessage.KafkaMessageProperties();
            properties.setTopic("event.topic");
            properties.setKey(invocation.getArgument(0));
            properties.setHeaders(Map.of());
            return new KafkaMessage("{}".getBytes(StandardCharsets.UTF_8), properties, "event");
        });
        SenderOptions<String, byte[]> options = SenderOptions.<String, byte[]>create(
                        Map.of("bootstrap.servers", "localhost:9092"))
                .withKeySerializer(new StringSerializer())
                .withValueSerializer(new ByteArraySerializer())
                .stopOnError(false);
        return new ReactiveMessageSender(KafkaSender.create(factory, options), converter,
                mock(TopologyCreator.class), NoOpSchemaValidator.INSTANCE);
    }
}
