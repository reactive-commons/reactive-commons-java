package org.reactivecommons.async.kafka.communications;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.internals.ProducerFactory;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sends records through the real reactor-kafka sender over a {@link MockProducer}: each send completes with the
 * outcome of its own record, whatever its key, the records leave in the order they were sent, and the caller is
 * neither blocked nor continued on the thread that sends.
 */
class ReactiveMessageSenderDeliveryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String TOPIC = "event.topic";

    private final MessageConverter converter = mock(MessageConverter.class);
    private final TopologyCreator topologyCreator = mock(TopologyCreator.class);

    @BeforeEach
    void setUp() {
        // The messages of these tests are already Kafka messages
        when(converter.toMessage(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(topologyCreator.ensureTopic(anyString())).thenReturn(Mono.empty());
    }

    /**
     * KC-07: the confirmation of a send is not looked up by the key of its record. Two sends with the same key and
     * one without key complete each with its own outcome.
     */
    @Test
    void sendsWithTheSameKeyOrWithoutKeyCompleteSeparately() {
        MockProducer<String, byte[]> producer = producer(false);
        ReactiveMessageSender sender = sender(producer, NoOpSchemaValidator.INSTANCE);

        Mono<Void> first = sender.send(message("order-1", Map.of())).cache();
        Mono<Void> second = sender.send(message("order-1", Map.of())).cache();
        Mono<Void> withoutKey = sender.send(message(null, Map.of())).cache();
        List.of(first, second, withoutKey).forEach(sent -> sent.subscribe(v -> {
        }, e -> {
        }));
        await().atMost(TIMEOUT).until(() -> producer.history().size() == 3);
        IntStream.range(0, 3).forEach(i -> producer.completeNext());

        StepVerifier.create(first).expectComplete().verify(TIMEOUT);
        StepVerifier.create(second).expectComplete().verify(TIMEOUT);
        StepVerifier.create(withoutKey).expectComplete().verify(TIMEOUT);
    }

    /**
     * KC-25: sends subscribed one after the other, without waiting, reach the producer in that order.
     */
    @Test
    void sendsSubscribedOneAfterTheOtherLeaveInThatOrder() {
        MockProducer<String, byte[]> producer = producer(true);
        ReactiveMessageSender sender = sender(producer, NoOpSchemaValidator.INSTANCE);
        List<String> keys = IntStream.range(0, 5000).mapToObj(i -> "key-" + i).toList();

        keys.forEach(key -> sender.send(message(key, Map.of())).subscribe());

        await().atMost(TIMEOUT).until(() -> producer.history().size() == keys.size());
        assertThat(producer.history()).extracting(ProducerRecord::key).containsExactlyElementsOf(keys);
    }

    /**
     * KC-25: the caller continues on a thread of a bounded Reactor scheduler, not on the thread that hands the records
     * to the producer, and the sender starts no threads of its own.
     */
    @Test
    void theCallerContinuesOnABoundedReactorThreadAndTheSenderHasNoThreadsOfItsOwn() {
        MockProducer<String, byte[]> producer = producer(true);
        ReactiveMessageSender sender = sender(producer, NoOpSchemaValidator.INSTANCE);
        AtomicReference<String> continuation = new AtomicReference<>();

        StepVerifier.create(sender.send(message("key", Map.of()))
                        .doOnSuccess(v -> continuation.set(Thread.currentThread().getName())))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(continuation.get()).startsWith("boundedElastic-");
        assertThat(Thread.getAllStackTraces().keySet()).extracting(Thread::getName)
                .noneMatch(name -> name.startsWith("KMessageSender"));
        sender.close();
    }

    /**
     * KC-29: a header without value is sent without value, and a header with non-ASCII characters is sent as UTF-8:
     * read back, both are what was sent.
     */
    @Test
    void headersWithoutValueOrWithNonAsciiTextAreSentAndReadBackAsTheyWere() {
        MockProducer<String, byte[]> producer = producer(true);
        ReactiveMessageSender sender = sender(producer, NoOpSchemaValidator.INSTANCE);
        Map<String, Object> headers = new HashMap<>();
        headers.put("no-value", null);
        headers.put("name", "ñandú €");

        StepVerifier.create(sender.send(message("key", headers))).expectComplete().verify(TIMEOUT);

        ProducerRecord<String, byte[]> sent = producer.history().get(0);
        Header noValue = sent.headers().lastHeader("no-value");
        assertThat(noValue).isNotNull();
        assertThat(noValue.value()).isNull();
        assertThat(sent.headers().lastHeader("name").value()).isEqualTo("ñandú €".getBytes(StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        ReceiverRecord<String, byte[]> received = mock(ReceiverRecord.class);
        when(received.headers()).thenReturn(sent.headers());
        when(received.key()).thenReturn(sent.key());
        when(received.topic()).thenReturn(sent.topic());
        when(received.value()).thenReturn(sent.value());
        assertThat(KafkaMessage.fromDelivery(received).getProperties().getHeaders())
                .containsEntry("no-value", null)
                .containsEntry("name", "ñandú €");
    }

    /**
     * KC-11: the outbound schema validation, which may call a schema registry, does not run on the caller's thread.
     */
    @Test
    void theOutboundValidationDoesNotRunOnTheCallersThread() {
        MockProducer<String, byte[]> producer = producer(true);
        AtomicReference<String> validation = new AtomicReference<>();
        SchemaValidator validator = new SchemaValidator() {
            @Override
            public void validateOutbound(String topic, byte[] payload, org.apache.kafka.common.header.Headers headers) {
                validation.set(Thread.currentThread().getName());
            }

            @Override
            public void validateInbound(String topic, byte[] payload, org.apache.kafka.common.header.Headers headers) {
                // only the outbound validation is used here
            }
        };
        ReactiveMessageSender sender = sender(producer, validator);

        StepVerifier.create(sender.send(message("key", Map.of()))).expectComplete().verify(TIMEOUT);

        assertThat(validation.get()).isNotNull().isNotEqualTo(Thread.currentThread().getName());
    }

    /**
     * KC-11: a send to a topic the sender does not know yet does not block the subscribing thread while the cluster
     * is asked whether it exists.
     */
    @Test
    void aSendToATopicNotKnownYetDoesNotBlockTheSubscribingThread() {
        AdminClient adminClient = mock(AdminClient.class);
        // The cluster does not answer
        DescribeTopicsResult described = mock(DescribeTopicsResult.class);
        doReturn(new KafkaFutureImpl<>()).when(described).allTopicNames();
        when(adminClient.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(described);
        TopologyCreator creator = new TopologyCreator(adminClient, new KafkaCustomizations(), true);
        ReactiveMessageSender sender = new ReactiveMessageSender(KafkaSender.create(factory(producer(true)),
                options()), converter, creator, NoOpSchemaValidator.INSTANCE);

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> sender.send(message("key", Map.of(), "not.known.yet")).subscribe(v -> {
                }, e -> {
                }));
    }

    private static KafkaMessage message(String key, Map<String, Object> headers) {
        return message(key, headers, TOPIC);
    }

    private static KafkaMessage message(String key, Map<String, Object> headers, String topic) {
        KafkaMessage.KafkaMessageProperties properties = new KafkaMessage.KafkaMessageProperties();
        properties.setTopic(topic);
        properties.setKey(key);
        properties.setHeaders(headers);
        return new KafkaMessage("{}".getBytes(StandardCharsets.UTF_8), properties, null);
    }

    private static MockProducer<String, byte[]> producer(boolean autoComplete) {
        return new MockProducer<>(autoComplete, null, new StringSerializer(), new ByteArraySerializer());
    }

    private ReactiveMessageSender sender(MockProducer<String, byte[]> producer, SchemaValidator validator) {
        return new ReactiveMessageSender(KafkaSender.create(factory(producer), options()), converter,
                topologyCreator, validator);
    }

    static ProducerFactory factory(Producer<String, byte[]> producer) {
        return new ProducerFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Producer<K, V> createProducer(SenderOptions<K, V> senderOptions) {
                return (Producer<K, V>) producer;
            }
        };
    }

    static SenderOptions<String, byte[]> options() {
        return SenderOptions.<String, byte[]>create(Map.of("bootstrap.servers", "localhost:9092"))
                .withKeySerializer(new StringSerializer())
                .withValueSerializer(new ByteArraySerializer())
                .stopOnError(false);
    }
}
