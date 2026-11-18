package org.reactivecommons.async.kafka.communications;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.exceptions.SendQueueFullException;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.internals.ProducerFactory;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The bounds of the sender: a send never waits for its outcome longer than {@code sendTimeout}, the sender never
 * holds more than {@code maxPendingSends} sends, a send that gives up leaves nothing behind, and a producer that
 * could not be created is created again by the next send.
 */
class ReactiveMessageSenderLimitsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String TOPIC = "event.topic";

    private final MessageConverter converter = mock(MessageConverter.class);
    private final TopologyCreator topologyCreator = mock(TopologyCreator.class);
    private ReactiveMessageSender sender;

    @BeforeEach
    void setUp() {
        when(converter.toMessage(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(topologyCreator.ensureTopic(anyString())).thenReturn(Mono.empty());
    }

    @AfterEach
    void tearDown() {
        if (sender != null) {
            sender.close();
        }
    }

    /**
     * KC-07: a send cancelled by its caller (e.g. by a timeout of the caller) leaves no confirmation behind.
     */
    @Test
    void aCancelledSendLeavesNoConfirmationBehind() {
        MockProducer<String, byte[]> producer = new MockProducer<>(false, null, new StringSerializer(),
                new ByteArraySerializer());
        sender = sender(() -> KafkaSender.create(factory(producer), options(Map.of())), Duration.ofSeconds(30), 10);

        Disposable cancelled = sender.send(message("order-1")).subscribe();
        await().atMost(TIMEOUT).until(() -> producer.history().size() == 1);
        assertThat(sender.pendingSends()).isEqualTo(1);

        cancelled.dispose();

        assertThat(sender.pendingSends()).isZero();
    }

    /**
     * KC-09: once {@code maxPendingSends} sends wait for their outcome, a new send fails at once, and is accepted
     * again when one of them completes.
     */
    @Test
    void aSendBeyondTheMaxPendingSendsFailsAtOnce() {
        MockProducer<String, byte[]> producer = new MockProducer<>(false, null, new StringSerializer(),
                new ByteArraySerializer());
        sender = sender(() -> KafkaSender.create(factory(producer), options(Map.of())), Duration.ofSeconds(30), 2);
        Mono<Void> first = sender.send(message("1")).cache();
        Mono<Void> second = sender.send(message("2")).cache();
        first.subscribe();
        second.subscribe();
        await().atMost(TIMEOUT).until(() -> producer.history().size() == 2);

        StepVerifier.create(sender.send(message("3")))
                .expectError(SendQueueFullException.class)
                .verify(Duration.ofSeconds(1));

        producer.completeNext();
        StepVerifier.create(first).expectComplete().verify(TIMEOUT);
        Mono<Void> fourth = sender.send(message("4")).cache();
        fourth.subscribe();
        await().atMost(TIMEOUT).until(() -> producer.history().size() == 3);
        producer.completeNext();
        producer.completeNext();
        StepVerifier.create(second).expectComplete().verify(TIMEOUT);
        StepVerifier.create(fourth).expectComplete().verify(TIMEOUT);
    }

    /**
     * KC-09 (P12): with the broker unreachable the producer blocks on each record up to {@code max.block.ms} (60 s
     * here, the default of the client). The sends do not fail one after the other: all of them fail once their
     * {@code sendTimeout} passes.
     */
    @Test
    void sendsToAnUnreachableBrokerAllFailAroundTheSendTimeout() throws IOException {
        Duration sendTimeout = Duration.ofSeconds(2);
        String unreachable = "localhost:" + unusedPort();
        sender = sender(() -> KafkaSender.create(options(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, unreachable,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 60_000,
                ProducerConfig.CLIENT_ID_CONFIG, "unreachable-broker-test"))), sendTimeout, 100);
        long start = System.nanoTime();

        List<Signal<Void>> outcomes = Flux.range(0, 24)
                .flatMap(i -> sender.send(message("key-" + i)).materialize())
                .collectList()
                .block(TIMEOUT);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(outcomes).hasSize(24)
                .allSatisfy(signal -> assertThat(signal.getThrowable()).isInstanceOf(TimeoutException.class));
        assertThat(elapsed).isBetween(sendTimeout, sendTimeout.multipliedBy(3));
    }

    /**
     * KC-28: a producer that cannot be created fails the sends waiting for it, and the next send creates a new one.
     */
    @Test
    void aProducerThatCouldNotBeCreatedIsCreatedAgainByTheNextSend() {
        MockProducer<String, byte[]> producer = new MockProducer<>(true, null, new StringSerializer(),
                new ByteArraySerializer());
        IllegalStateException unresolvable = new IllegalStateException("bootstrap servers not resolvable");
        AtomicInteger attempts = new AtomicInteger();
        ProducerFactory failsOnce = new ProducerFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Producer<K, V> createProducer(SenderOptions<K, V> senderOptions) {
                if (attempts.incrementAndGet() == 1) {
                    throw unresolvable;
                }
                return (Producer<K, V>) producer;
            }
        };
        sender = sender(() -> KafkaSender.create(failsOnce, options(Map.of())), Duration.ofSeconds(30), 10);

        StepVerifier.create(sender.send(message("first")))
                .expectErrorMatches(unresolvable::equals)
                .verify(TIMEOUT);
        StepVerifier.create(sender.send(message("second")))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(attempts).hasValue(2);
        assertThat(producer.history()).extracting(ProducerRecord::key).containsExactly("second");
    }

    private ReactiveMessageSender sender(Supplier<KafkaSender<String, byte[]>> factory, Duration sendTimeout,
                                         int maxPendingSends) {
        return new ReactiveMessageSender(factory, converter, topologyCreator, NoOpSchemaValidator.INSTANCE,
                sendTimeout, maxPendingSends);
    }

    private static KafkaMessage message(String key) {
        KafkaMessage.KafkaMessageProperties properties = new KafkaMessage.KafkaMessageProperties();
        properties.setTopic(TOPIC);
        properties.setKey(key);
        return new KafkaMessage("{}".getBytes(StandardCharsets.UTF_8), properties, null);
    }

    private static ProducerFactory factory(Producer<String, byte[]> producer) {
        return ReactiveMessageSenderDeliveryTest.factory(producer);
    }

    private static SenderOptions<String, byte[]> options(Map<String, Object> properties) {
        Map<String, Object> config = new HashMap<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                "localhost:9092"));
        config.putAll(properties);
        return SenderOptions.<String, byte[]>create(config)
                .withKeySerializer(new StringSerializer())
                .withValueSerializer(new ByteArraySerializer())
                .stopOnError(false)
                .closeTimeout(Duration.ofSeconds(1));
    }

    /**
     * A port nothing listens on: the producer gets its connection refused, as with a broker that is down.
     */
    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
