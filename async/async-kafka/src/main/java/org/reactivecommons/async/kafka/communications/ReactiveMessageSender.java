package org.reactivecommons.async.kafka.communications;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.exceptions.SendQueueFullException;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;
import reactor.util.concurrent.Queues;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Publishes messages with a single reactor-kafka send sequence, which hands the records to the Kafka producer in the
 * order they are sent.
 * <ul>
 *     <li>Each send is confirmed by an id of its own, not by the key of its record: sends with the same key, or
 *     without key, complete each with the outcome of its own record.</li>
 *     <li>A send fails after {@code sendTimeout} if its outcome is not known by then (it may still be written), and
 *     fails at once with {@link SendQueueFullException} when {@code maxPendingSends} sends are already waiting for
 *     their outcome.</li>
 *     <li>The caller continues on a thread of {@link Schedulers#boundedElastic()}, never on the thread that hands the
 *     records to the producer, nor on the network thread of the producer.</li>
 *     <li>The Kafka producer is created on the first send: a service that only consumes never opens one. If it cannot
 *     be created, or fails for good (e.g. an authentication error), the sends waiting for it fail and the next send
 *     creates a new one.</li>
 * </ul>
 */
@Slf4j
public class ReactiveMessageSender {
    /**
     * The same default as the {@code sendTimeout} of the RabbitMQ sender.
     */
    public static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_MAX_PENDING_SENDS = 10_000;
    private static final String CLOSED_MESSAGE = "The Kafka sender is closed";

    private final Supplier<KafkaSender<String, byte[]>> senderFactory;
    private final MessageConverter messageConverter;
    private final TopologyCreator topologyCreator;
    private final SchemaValidator schemaValidator;
    private final Duration sendTimeout;
    private final int maxPendingSends;

    /**
     * The sends waiting for their outcome, by the id of their send. Every entry belongs to the current pipeline: they
     * are only added while holding the lock of the sender, and all of them are failed when the pipeline stops.
     */
    private final Map<Long, MonoSink<Void>> confirmations = new ConcurrentHashMap<>();
    private final AtomicLong sendIds = new AtomicLong();
    /**
     * Held while a pipeline subscribes (which creates the producer) and while it is closed, so the sender is never
     * closed in the middle of creating a producer, which would be left open. Never held together with the lock of the
     * sender, which the callers take to send.
     */
    private final Object lifecycle = new Object();
    private Pipeline pipeline;
    private volatile boolean closed;

    /**
     * A sender over a single {@link KafkaSender}, with the default {@code sendTimeout} and {@code maxPendingSends}.
     * Since the same {@link KafkaSender} is used again, it cannot recover from a producer that could not be created:
     * prefer {@link #ReactiveMessageSender(Supplier, MessageConverter, TopologyCreator, SchemaValidator,
     * SendLimits)}.
     */
    public ReactiveMessageSender(KafkaSender<String, byte[]> sender, MessageConverter messageConverter,
                                 TopologyCreator topologyCreator, SchemaValidator schemaValidator) {
        this(() -> sender, messageConverter, topologyCreator, schemaValidator,
                new SendLimits(DEFAULT_SEND_TIMEOUT, DEFAULT_MAX_PENDING_SENDS));
    }

    /**
     * @param senderFactory creates the {@link KafkaSender} on the first send, and again after a producer that could
     *                      not be created or failed for good
     * @param limits        how long a send waits for its outcome, and how many sends may wait for it at once
     */
    public ReactiveMessageSender(Supplier<KafkaSender<String, byte[]>> senderFactory,
                                 MessageConverter messageConverter, TopologyCreator topologyCreator,
                                 SchemaValidator schemaValidator, SendLimits limits) {
        Objects.requireNonNull(limits, "limits");
        this.senderFactory = Objects.requireNonNull(senderFactory, "senderFactory");
        this.messageConverter = messageConverter;
        this.topologyCreator = topologyCreator;
        this.schemaValidator = schemaValidator == null ? NoOpSchemaValidator.INSTANCE : schemaValidator;
        this.sendTimeout = limits.sendTimeout();
        this.maxPendingSends = limits.maxPendingSends();
    }

    /**
     * Completes once the broker acknowledged the record. Nothing blocks the subscribing thread: a topic not known yet
     * is checked asynchronously, and a schema validation runs on {@link Schedulers#boundedElastic()}.
     * <p>
     * Two sends subscribed one after the other reach the producer in that order, unless a schema validator is used:
     * each one is then validated on its own thread, so sends that must keep their order are chained.
     */
    public <V> Mono<Void> send(V message) {
        return Mono.defer(() -> {
                    if (closed) {
                        return Mono.error(new IllegalStateException(CLOSED_MESSAGE));
                    }
                    KafkaMessage kafkaMessage = (KafkaMessage) messageConverter.toMessage(message);
                    return topologyCreator.ensureTopic(kafkaMessage.getProperties().getTopic())
                            .then(createRecord(kafkaMessage));
                })
                .flatMap(this::enqueue)
                .timeout(sendTimeout, Mono.error(() -> new TimeoutException(
                        "The Kafka send did not complete within " + sendTimeout.toMillis() + " ms")))
                // The caller continues off the threads of the producer, which complete the other sends
                .publishOn(Schedulers.boundedElastic());
    }

    /**
     * Closes the producer, if it was created, once the records already handed to it are sent. The sends still
     * waiting for their outcome fail, and so does any later send. Idempotent.
     */
    public void close() {
        Pipeline current;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            current = pipeline;
            pipeline = null;
        }
        if (current != null) {
            release(current);
        }
        IllegalStateException closedError = new IllegalStateException(CLOSED_MESSAGE);
        takeAll().forEach(sink -> sink.error(closedError));
    }

    /**
     * The number of sends waiting for their outcome.
     */
    int pendingSends() {
        return confirmations.size();
    }

    private Mono<ProducerRecord<String, byte[]>> createRecord(KafkaMessage message) {
        Mono<ProducerRecord<String, byte[]>> producerRecord = Mono.fromCallable(() -> toProducerRecord(message));
        if (schemaValidator instanceof NoOpSchemaValidator) {
            return producerRecord;
        }
        // Validating may resolve a schema with a blocking call to a registry
        return producerRecord.subscribeOn(Schedulers.boundedElastic());
    }

    private ProducerRecord<String, byte[]> toProducerRecord(KafkaMessage message) {
        List<Header> headers = new ArrayList<>();
        Map<String, Object> properties = message.getProperties().getHeaders();
        if (properties != null) {
            properties.forEach((name, value) -> headers.add(new RecordHeader(name, headerValue(value))));
        }
        ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(message.getProperties().getTopic(), null,
                message.getProperties().getKey(), message.getBody(), headers);
        schemaValidator.validateOutbound(producerRecord.topic(), producerRecord.value(), producerRecord.headers());
        return producerRecord;
    }

    /**
     * Kafka allows a header without value: it is sent without value. Text is written as UTF-8, the charset the
     * listeners read it with.
     */
    private static byte[] headerValue(Object value) {
        if (value == null) {
            return null; // NOSONAR: a header without value is sent as such
        }
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Mono<Void> enqueue(ProducerRecord<String, byte[]> producerRecord) {
        return Mono.create(sink -> {
            long id = sendIds.incrementAndGet();
            RuntimeException rejection = accept(SenderRecord.create(producerRecord, id), sink);
            if (rejection != null) {
                sink.error(rejection);
                return;
            }
            // A send that times out or is cancelled leaves no confirmation behind, and its record is not handed to
            // the producer if it did not reach it yet
            sink.onDispose(() -> confirmations.remove(id));
        });
    }

    /**
     * @return why the send was rejected, or {@code null} once its record is queued for the producer
     */
    private synchronized RuntimeException accept(SenderRecord<String, byte[], Long> senderRecord,
                                                 MonoSink<Void> sink) {
        RuntimeException rejection = rejectionBeforeEmit();
        if (rejection != null) {
            return rejection;
        }
        if (pipeline == null) {
            pipeline = startPipeline();
        }
        Long id = senderRecord.correlationMetadata();
        confirmations.put(id, sink);
        Sinks.EmitResult result = pipeline.records().tryEmitNext(senderRecord);
        if (result.isSuccess()) {
            return null;
        }
        confirmations.remove(id);
        return emitFailure(result);
    }

    /**
     * Called holding the lock of the sender.
     *
     * @return why a send is rejected before its record is emitted, or {@code null} if it may be emitted
     */
    private RuntimeException rejectionBeforeEmit() {
        if (closed) {
            return new IllegalStateException(CLOSED_MESSAGE);
        }
        if (confirmations.size() >= maxPendingSends) {
            return queueFull();
        }
        return null;
    }

    private RuntimeException emitFailure(Sinks.EmitResult result) {
        if (result == Sinks.EmitResult.FAIL_OVERFLOW || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            return queueFull();
        }
        // The pipeline is stopping: the next send starts a new one
        return new IllegalStateException("The Kafka send sequence stopped (" + result + "), the message was not sent");
    }

    private SendQueueFullException queueFull() {
        return new SendQueueFullException("The Kafka sender already has " + maxPendingSends
                + " sends waiting for their outcome, the message was not sent");
    }

    /**
     * The send sequence is subscribed, which creates the producer, on {@link Schedulers#boundedElastic()}: creating
     * a producer resolves the bootstrap servers, and must not hold the caller nor the lock that the other callers
     * take. The records sent meanwhile wait in its bounded queue.
     */
    private Pipeline startPipeline() {
        KafkaSender<String, byte[]> kafkaSender = senderFactory.get();
        Sinks.Many<SenderRecord<String, byte[], Long>> records = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<SenderRecord<String, byte[], Long>>get(maxPendingSends).get());
        Pipeline started = new Pipeline(kafkaSender, records, Disposables.swap());
        Schedulers.boundedElastic().schedule(() -> subscribe(started));
        return started;
    }

    private void subscribe(Pipeline started) {
        synchronized (lifecycle) {
            if (started.subscription().isDisposed()) {
                return; // closed before it could subscribe: no producer is created
            }
            started.subscription().update(started.sender()
                    .send(started.records().asFlux()
                            .filter(senderRecord -> confirmations.containsKey(senderRecord.correlationMetadata())))
                    .subscribe(this::confirm, error -> stop(started, error)));
        }
    }

    private void confirm(SenderResult<Long> result) {
        MonoSink<Void> sink = confirmations.remove(result.correlationMetadata());
        if (sink == null) {
            return; // the caller gave up waiting
        }
        if (result.exception() != null) {
            sink.error(result.exception());
        } else {
            sink.success();
        }
    }

    /**
     * The send sequence stops when the producer cannot be created or fails for good: its sends fail, its sender is
     * closed and the next send starts a new pipeline, with a new producer.
     */
    private void stop(Pipeline stopped, Throwable error) {
        List<MonoSink<Void>> failed;
        synchronized (this) {
            if (pipeline != stopped) {
                return; // closed meanwhile, which already released it
            }
            pipeline = null;
            // Taken under the lock that adds them: a send that starts the next pipeline is not among them
            failed = takeAll();
        }
        log.warn("The Kafka producer failed, the sends waiting for it fail and the next send creates a new one: {}",
                error.getClass().getName());
        failed.forEach(sink -> sink.error(error));
        // Closing the producer waits for it: never on the thread that reported the error, which may be its own
        Schedulers.boundedElastic().schedule(() -> release(stopped));
    }

    private void release(Pipeline released) {
        synchronized (lifecycle) {
            released.sender().close();
            released.subscription().dispose();
        }
    }

    private List<MonoSink<Void>> takeAll() {
        List<MonoSink<Void>> taken = new ArrayList<>();
        confirmations.keySet().forEach(id -> {
            MonoSink<Void> sink = confirmations.remove(id);
            if (sink != null) {
                taken.add(sink);
            }
        });
        return taken;
    }

    private record Pipeline(KafkaSender<String, byte[]> sender, Sinks.Many<SenderRecord<String, byte[], Long>> records,
                            Disposable.Swap subscription) {
    }
}
