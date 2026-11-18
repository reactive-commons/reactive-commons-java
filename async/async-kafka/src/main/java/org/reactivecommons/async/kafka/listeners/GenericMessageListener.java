package org.reactivecommons.async.kafka.listeners;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.header.Header;
import org.reactivecommons.async.commons.DLQDiscardNotifier;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.FallbackStrategy;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.utils.LoggerSubscriber;
import org.reactivecommons.async.kafka.KafkaDlqHeaders;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.PartitionFlowControl;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.validation.SchemaResolutionException;
import org.reactivecommons.async.kafka.validation.SchemaValidationException;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.function.Function.identity;

@Slf4j
public abstract class GenericMessageListener {
    private final ConcurrentHashMap<String, Function<Message, Mono<Object>>> handlers = new ConcurrentHashMap<>();
    private final ReactiveMessageListener messageListener;
    private static final Duration DLQ_RETRY_MIN_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DLQ_RETRY_MAX_BACKOFF = Duration.ofSeconds(10);
    /**
     * Bounds of the wait before a flow that terminated is subscribed again, the same as the restarts of the RabbitMQ
     * listeners.
     */
    static final Duration RESTART_MIN_BACKOFF = Duration.ofMillis(200);
    static final Duration RESTART_MAX_BACKOFF = Duration.ofSeconds(5);
    private static final double RESTART_JITTER = 0.5;
    private final Scheduler scheduler;
    private final Scheduler errorReporterScheduler;
    private final KafkaListenerSchedulers ownSchedulers;

    private final List<String> topics;
    private final String groupId;
    private final boolean useDLQ;
    private final boolean createTopology;
    private final long maxRetries;
    private final Duration retryDelay;
    private final DiscardNotifier discardNotifier;
    private final String objectType;
    private final CustomReporter customReporter;
    private final Disposable.Swap subscription = Disposables.swap();
    private final AtomicBoolean started = new AtomicBoolean();
    /**
     * Set once the listener stops consuming: no message is handled anymore and the flow is not subscribed again.
     */
    private volatile boolean stopping;
    /**
     * Messages being handled (their handler, retries or discard), which closing the application waits for.
     */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Sinks.Empty<Void> idle = Sinks.empty();
    /**
     * The last offset acknowledged by the current consumer: committing it commits every offset it acknowledged.
     */
    private final AtomicReference<ReceiverOffset> lastAcknowledged = new AtomicReference<>();
    private final AtomicLong restarts = new AtomicLong();
    private volatile boolean restarting;
    /**
     * {@link System#nanoTime()} when the listener last started restarting, while {@link #restarting}.
     */
    private volatile long restartingSince;
    private final KafkaListenerMetrics metrics;

    /**
     * A listener with threads of its own, released when it stops.
     */
    protected GenericMessageListener(ListenerContext<ReactiveMessageListener> context, String objectType,
                                     String groupId, List<String> topics) {
        this(context, objectType, groupId, topics, null);
    }

    /**
     * @param schedulers the threads to run on, shared with the other listeners of the domain and released by their
     *                   owner; when {@code null} the listener creates its own, released when it stops
     */
    protected GenericMessageListener(ListenerContext<ReactiveMessageListener> context, String objectType,
                                     String groupId, List<String> topics, KafkaListenerSchedulers schedulers) {
        if (schedulers == null) {
            this.ownSchedulers = KafkaListenerSchedulers.create(getClass().getSimpleName());
            schedulers = ownSchedulers;
        } else {
            this.ownSchedulers = null;
        }
        this.scheduler = schedulers.handlers();
        this.errorReporterScheduler = schedulers.errorReporter();
        final RetryPolicy retryPolicy = context.retryPolicy();
        this.groupId = groupId;
        this.topics = topics;
        this.messageListener = context.listener();
        this.createTopology = context.createTopology();
        this.maxRetries = retryPolicy.maxRetries();
        this.retryDelay = Duration.ofMillis(retryPolicy.retryDelay());
        this.useDLQ = retryPolicy.withDLQRetry();
        this.discardNotifier = context.discardNotifier();
        this.objectType = objectType;
        this.customReporter = context.errorReporter();
        KafkaListenerMetrics listenerMetrics = messageListener == null ? null : messageListener.getMetrics();
        this.metrics = listenerMetrics == null ? KafkaListenerMetrics.NONE : listenerMetrics;
    }

    private boolean hasRetries() {
        return maxRetries != -1;
    }

    /**
     * Without a DLQ and with the default {@code maxRetries} (-1), a message whose handler fails is retried in place
     * until it succeeds: Kafka cannot redeliver a single message, so its offset must not be committed until then.
     */
    private boolean retriesInPlaceForever() {
        return !hasRetries() && !useDLQ;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        if (useDLQ) {
            return creator.createTopics(topics).then(creator.createDlqTopics(getDlqBaseTopics()));
        }
        return creator.createTopics(topics);
    }

    /**
     * Topics for which a {@code <topic>.dlq} topic is created at startup, so the {@link DiscardNotifier} can
     * publish there once a message exhausts its retries: the topics of the listener, and the one of the messages
     * that cannot be read ({@link DLQDiscardNotifier#UNREADABLE_MESSAGES}).
     */
    protected List<String> getDlqBaseTopics() {
        List<String> dlqBaseTopics = new ArrayList<>(topics);
        dlqBaseTopics.add(DLQDiscardNotifier.UNREADABLE_MESSAGES);
        return dlqBaseTopics;
    }

    /**
     * Starts consuming: creates the topology when configured, then subscribes a consumer once. When the flow
     * terminates (a consumer that cannot be created, a failed poll or commit, a topology that cannot be created) it is
     * subscribed again after a wait that grows from {@link #RESTART_MIN_BACKOFF} to {@link #RESTART_MAX_BACKOFF}, and
     * starts from the minimum again once the flow delivers a message. Idempotent.
     */
    public void startListener(TopologyCreator creator) {
        if (stopping || !started.compareAndSet(false, true)) {
            return;
        }
        log.info("Handling the messages of each partition one at a time, in order, for topics: {}", topics);

        if (useDLQ) {
            log.info("Retrying a failed message {} times, every {} ms, before sending it to its DLQ", maxRetries,
                    retryDelay.toMillis());
        } else if (hasRetries()) {
            log.info("Retrying a failed message {} times, every {} ms, before discarding it", maxRetries,
                    retryDelay.toMillis());
        } else {
            log.info("Retrying a failed message every {} ms until it succeeds", retryDelay.toMillis());
        }

        // Each subscription gets its own consumer, so its own flow control
        Flux<ReceiverRecord<String, byte[]>> records = Flux.defer(() -> {
            lastAcknowledged.set(null);
            PartitionFlowControl flowControl = new PartitionFlowControl(this::consuming);
            return receive(creator, flowControl)
                    .transform(received -> consumeFaultTolerant(received, flowControl))
                    .doOnNext(handled -> consuming());
        });
        AtomicBoolean firstSubscription = new AtomicBoolean(true);
        Flux<ReceiverRecord<String, byte[]>> flow = Flux.defer(() -> {
            if (stopping) {
                return Flux.empty();
            }
            if (!firstSubscription.getAndSet(false)) {
                restarts.incrementAndGet();
                topics.forEach(metrics::listenerRestarted);
            }
            return createTopology ? setUpBindings(creator).thenMany(records) : records;
        });

        LoggerSubscriber<ReceiverRecord<String, byte[]>> subscriber = new LoggerSubscriber<>(getClass().getName());
        subscription.update(subscriber);
        // The waits run on a timer: subscribing again from the termination itself would recurse when the
        // subscription fails at once
        flow.retryWhen(Retry.backoff(Long.MAX_VALUE, RESTART_MIN_BACKOFF)
                        .maxBackoff(RESTART_MAX_BACKOFF)
                        .jitter(RESTART_JITTER)
                        .transientErrors(true)
                        .filter(err -> !stopping)
                        .scheduler(scheduler)
                        .doBeforeRetry(signal -> onFlowFailed(signal.failure(), signal.totalRetriesInARow())))
                .repeatWhen(completions -> completions
                        .takeWhile(completion -> !stopping)
                        .concatMap(completion -> {
                            onFlowCompleted();
                            return Mono.delay(RESTART_MIN_BACKOFF, scheduler);
                        }))
                .subscribe(subscriber);
    }

    /**
     * Subscribes a new consumer to the topics of the listener.
     *
     * @param creator     the topology creator given to {@link #startListener(TopologyCreator)}
     * @param flowControl pauses and resumes the partitions of this subscription
     */
    protected Flux<ReceiverRecord<String, byte[]>> receive(TopologyCreator creator, PartitionFlowControl flowControl) {
        return messageListener.listen(groupId, topics, flowControl);
    }

    private void onFlowFailed(Throwable failure, long failuresInARow) {
        markRestarting();
        if (failuresInARow == 0) {
            // With its stack trace once: the next failures in a row are most likely the same
            log.warn("Listener for topics {} stopped consuming, subscribing it again in {} ms at most", topics,
                    RESTART_MIN_BACKOFF.multipliedBy(3).dividedBy(2).toMillis(), failure);
        } else {
            log.debug("Listener for topics {} stopped consuming again (failure {} in a row): {}", topics,
                    failuresInARow + 1, failure.toString());
        }
    }

    private void onFlowCompleted() {
        markRestarting();
        log.info("Listener for topics {} completed, subscribing it again in {} ms", topics,
                RESTART_MIN_BACKOFF.toMillis());
    }

    /**
     * Keeps the time the listener started restarting: the failures in a row of the same restart do not reset it.
     */
    private void markRestarting() {
        if (!restarting) {
            restartingSince = System.nanoTime();
            restarting = true;
        }
    }

    /**
     * The consumer of the current subscription got its partitions or delivered a message.
     */
    private void consuming() {
        restarting = false;
    }

    /**
     * How many times the flow was subscribed again after it terminated.
     */
    public long restarts() {
        return restarts.get();
    }

    /**
     * Whether the flow terminated and its new subscription did not get its partitions nor deliver a message yet.
     */
    public boolean isRestarting() {
        return restarting && !stopping;
    }

    /**
     * How long the listener has been restarting (see {@link #isRestarting()}): {@link Duration#ZERO} when it is not.
     */
    public Duration restartingFor() {
        long since = restartingSince;
        if (!isRestarting()) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(Math.max(0, System.nanoTime() - since));
    }

    /**
     * The topics the listener consumes.
     */
    public List<String> topics() {
        return List.copyOf(topics);
    }

    /**
     * Stops consuming without dropping the messages being handled: they finish, and their offsets are committed, so
     * they are not delivered again after a restart. The messages received and not started yet are not handled nor
     * committed: the next consumer gets them. A message whose handler keeps failing, or that cannot be sent to its DLQ
     * yet, is not retried anymore and is left uncommitted too. The listener is not subscribed again.
     *
     * @return completes once no message is being handled and the offsets acknowledged are committed. Follow with
     * {@link #stop()}, also when it does not complete in time
     */
    public Mono<Void> drain() {
        stopping = true;
        if (inFlight.get() == 0) {
            idle.tryEmitEmpty();
        }
        return idle.asMono().then(Mono.defer(this::commitAcknowledged));
    }

    private Mono<Void> commitAcknowledged() {
        ReceiverOffset last = lastAcknowledged.get();
        if (last == null) {
            return Mono.empty();
        }
        return last.commit()
                .doOnError(err -> log.warn("Unable to commit the offsets of topics {} before stopping, the messages "
                        + "after the last commit are delivered again: {}", topics, err.toString()))
                .onErrorComplete();
    }

    /**
     * Stops consuming at once (cancelling the flow closes the Kafka consumer, after a final commit of the offsets
     * acknowledged), and releases the threads of the listener when they are its own. A message still being handled is
     * cancelled: call {@link #drain()} first to let it finish. Idempotent.
     */
    public void stop() {
        stopping = true;
        subscription.dispose();
        if (ownSchedulers != null) {
            ownSchedulers.dispose();
        }
    }

    /**
     * Handles the messages of each partition one at a time, in the order of the partition, and the partitions at the
     * same time. A message is acknowledged once handled, so the offsets of a partition are acknowledged in order:
     * acknowledging an offset commits every previous one of the partition, so a later message must never be
     * acknowledged while an earlier one is still being handled.
     * <p>
     * The messages waiting in a partition do not take room from the others: each partition is paused on the consumer
     * when too many of its messages wait (see {@link PartitionFlowControl}).
     */
    private Flux<ReceiverRecord<String, byte[]>> consumeFaultTolerant(Flux<ReceiverRecord<String, byte[]>> messageFlux,
                                                                      PartitionFlowControl flowControl) {
        return messageFlux
                .doOnNext(msj -> flowControl.received(partitionOf(msj)))
                .groupBy(GenericMessageListener::partitionOf, Integer.MAX_VALUE)
                .flatMap(partition -> partition.concatMap(msj -> process(msj)
                        .doFinally(signal -> flowControl.handled(partitionOf(msj)))), Integer.MAX_VALUE);
    }

    /**
     * Handles the message, unless the listener is stopping: then it is neither handled nor acknowledged, and the next
     * consumer of its partition gets it.
     */
    private Mono<ReceiverRecord<String, byte[]>> process(ReceiverRecord<String, byte[]> msj) {
        return Mono.defer(() -> {
            // Counted before checking whether the listener is stopping, so drain() never misses a message that
            // starts while it checks
            inFlight.incrementAndGet();
            if (stopping) {
                handled();
                return Mono.empty();
            }
            return handleAndAcknowledge(msj).doFinally(signal -> handled());
        });
    }

    private void handled() {
        if (inFlight.decrementAndGet() == 0 && stopping) {
            idle.tryEmitEmpty();
        }
    }

    private static TopicPartition partitionOf(ReceiverRecord<String, byte[]> msj) {
        return new TopicPartition(msj.topic(), msj.partition());
    }

    /**
     * Every message ends acknowledged: handled, discarded, or skipped when even its discard failed. reactor-kafka
     * delays each rebalance of the group (up to {@code maxDelayRebalance}) while a message it delivered is not
     * acknowledged, and an error here would end the flow, so the consumer would be created again and read the same
     * message again.
     */
    private Mono<ReceiverRecord<String, byte[]>> handleAndAcknowledge(ReceiverRecord<String, byte[]> msj) {
        final Instant init = Instant.now();
        return Mono.defer(() -> {
            if (msj.value() == null && skipsTombstones()) {
                return skipTombstone(msj);
            }
            // Built once: the handler, the error reporter and the DLQ get the same message
            final Message message = KafkaMessage.fromDelivery(msj, getExecutorPath(msj));
            return handle(msj, message, init)
                    .doOnSuccess(r -> {
                        acknowledge(msj);
                        metrics.messageDone(msj.topic(), KafkaListenerMetrics.OUTCOME_ACK, since(init));
                    })
                    // The failure may be signalled on any thread (e.g. a timer of the handler): the discard runs on
                    // the threads of the listener
                    .onErrorResume(err -> Mono.defer(() -> discard(msj, message, err, init))
                            .subscribeOn(scheduler));
        }).onErrorResume(err -> skipUnhandled(msj, err, init));
    }

    /**
     * Whether a record without value (the tombstone of a compacted topic, which deletes its key) is acknowledged
     * without reaching the handler. True for domain events and notifications, which a tombstone does not carry.
     */
    protected boolean skipsTombstones() {
        return true;
    }

    /**
     * A message is done with (handled, discarded or skipped): its offset is committed with the next commit of the
     * consumer, together with the earlier ones of its partition.
     */
    protected void acknowledge(ReceiverRecord<String, byte[]> msj) {
        msj.receiverOffset().acknowledge();
        lastAcknowledged.set(msj.receiverOffset());
    }

    /**
     * The scheduler that runs the handlers of the listener, for the timers of a subclass.
     */
    protected Scheduler handlerScheduler() {
        return scheduler;
    }

    private Mono<ReceiverRecord<String, byte[]>> skipTombstone(ReceiverRecord<String, byte[]> msj) {
        log.debug("Skipping the record without value (tombstone) {} of topic {}, partition {}, offset {}", msj.key(),
                msj.topic(), msj.partition(), msj.offset());
        acknowledge(msj);
        return Mono.just(msj);
    }

    /**
     * Last resort for a message that could not even be discarded, e.g. its {@link Message} could not be built: it is
     * skipped, since reading it again would fail the same way and hold its partition forever.
     */
    private Mono<ReceiverRecord<String, byte[]>> skipUnhandled(ReceiverRecord<String, byte[]> msj, Throwable err,
                                                               Instant init) {
        log.error("Message {} of topic {}, partition {}, offset {} could not be handled nor discarded, it is skipped",
                sanitize(msj.key()), msj.topic(), msj.partition(), msj.offset(), err);
        acknowledge(msj);
        metrics.messageDone(msj.topic(), KafkaListenerMetrics.OUTCOME_DISCARD, since(init));
        return Mono.just(msj);
    }

    protected Mono<ReceiverRecord<String, byte[]>> handle(ReceiverRecord<String, byte[]> msj, Message message,
                                                          Instant initTime) {
        final String executorPath;
        final Function<Message, Mono<Object>> handler;
        try {
            executorPath = getExecutorPath(msj);
            handler = getExecutor(executorPath);
        } catch (Exception e) {
            log.error("ATTENTION !! Outer error protection reached for {}, in Async Consumer!! Severe Warning! ",
                    msj.key());
            return Mono.error(e);
        }

        // Validated in each attempt, on the threads of the listener: resolving the schema may block on the registry,
        // which must not hold the thread of the consumer, and a registry that cannot be reached is retried like a
        // failed handler
        Mono<Object> flow = Mono.defer(() -> {
                    validateInbound(msj);
                    return handler.apply(message);
                })
                .transform(enrichPostProcess(message))
                .doOnError(err -> metrics.handlerFailed(msj.topic()));
        if (hasRetries()) {
            flow = flow.retryWhen(Retry.fixedDelay(maxRetries, retryDelay)
                            .filter(err -> isRetryable(err) && !stopping)
                            .scheduler(scheduler)
                            .doBeforeRetry(signal -> metrics.handlerRetried(msj.topic())))
                    // The failure of the last attempt, not the one of the retries, is reported and discarded
                    .onErrorMap(Exceptions::isRetryExhausted, err -> err.getCause() != null ? err.getCause() : err);
        } else if (retriesInPlaceForever()) {
            flow = flow.retryWhen(Retry.fixedDelay(Long.MAX_VALUE, retryDelay)
                    .filter(err -> isRetryable(err) && !stopping)
                    .scheduler(scheduler)
                    .doBeforeRetry(signal -> onFailedAttempt(msj, message, executorPath, initTime, signal)));
        }
        return flow.doOnSuccess(o -> logExecution(executorPath, initTime, true))
                .subscribeOn(scheduler)
                .thenReturn(msj);
    }

    /**
     * A record without value (a tombstone) has no payload to validate.
     */
    private void validateInbound(ReceiverRecord<String, byte[]> msj) {
        SchemaValidator validator = messageListener.getSchemaValidator();
        if (validator != null && msj.value() != null) {
            validator.validateInbound(msj.topic(), msj.value(), msj.headers());
        }
    }

    /**
     * Discards a message whose retries are exhausted or that can never be handled: it is sent to its DLQ, or else
     * logged and skipped. Either way it ends acknowledged.
     */
    private Mono<ReceiverRecord<String, byte[]>> discard(ReceiverRecord<String, byte[]> msj, Message message,
                                                         Throwable err, Instant init) {
        if (stopping && isRetryable(err)) {
            // Its retries were cut short by the stop: it is not discarded, the next consumer gets it
            return leftForTheNextConsumer(msj);
        }
        reportErrorMetric(msj, init);
        sendErrorToCustomReporter(err, message, hasRetries());
        logError(err, msj, FallbackStrategy.DEFINITIVE_DISCARD);
        if (useDLQ) {
            return sendToDlq(message, msj, KafkaDlqHeaders.of(msj, err))
                    .then(Mono.fromSupplier(() -> {
                        acknowledge(msj);
                        metrics.messageDone(msj.topic(), KafkaListenerMetrics.OUTCOME_DLQ, since(init));
                        return msj;
                    }))
                    // The listener stopped while the DLQ could not be published to: it stays uncommitted
                    .onErrorResume(error -> stopping, error -> leftForTheNextConsumer(msj));
        }
        acknowledge(msj);
        metrics.messageDone(msj.topic(), KafkaListenerMetrics.OUTCOME_DISCARD, since(init));
        return Mono.just(msj);
    }

    private static Duration since(Instant start) {
        return Duration.between(start, Instant.now());
    }

    private Mono<ReceiverRecord<String, byte[]>> leftForTheNextConsumer(ReceiverRecord<String, byte[]> msj) {
        log.info("Listener stopping: message {} of topic {}, partition {}, offset {} is left uncommitted, it is "
                + "delivered again", msj.key(), msj.topic(), msj.partition(), msj.offset());
        return Mono.empty();
    }

    /**
     * Sends a discarded message to its DLQ, retrying until it is there: its offset is committed only then, so the
     * message is kept, not lost, while the DLQ cannot be published to (the broker is unreachable, the DLQ topic does
     * not exist yet). A message too large for the DLQ can never be sent there: it is logged and skipped.
     */
    private Mono<Void> sendToDlq(Message message, ReceiverRecord<String, byte[]> msj,
                                 Map<String, Object> dlqHeaders) {
        return Mono.defer(() -> notifyDiscard(message, msj, dlqHeaders))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, DLQ_RETRY_MIN_BACKOFF)
                        .maxBackoff(DLQ_RETRY_MAX_BACKOFF)
                        .filter(err -> !causedBy(err, RecordTooLargeException.class) && !stopping)
                        .scheduler(scheduler)
                        .doBeforeRetry(signal -> logDlqRetry(msj, signal)))
                .onErrorResume(err -> causedBy(err, RecordTooLargeException.class), err -> {
                    log.error("Message {} from topic {} is too large for its DLQ, it is skipped: {}", msj.key(),
                            msj.topic(), err.toString());
                    return Mono.empty();
                });
    }

    /**
     * Publishes a discarded message to its DLQ, and fails if it could not be published yet.
     *
     * @param msj        the record received, for the overrides that publish it as it is
     * @param dlqHeaders headers to add to the message in the DLQ, see {@link KafkaDlqHeaders}
     */
    @SuppressWarnings("java:S1172")
    protected Mono<Void> notifyDiscard(Message message, ReceiverRecord<String, byte[]> msj,
                                       Map<String, Object> dlqHeaders) {
        // The notifier publishes a new event built from the message: the headers reach the event bus that publishes
        // it through the subscriber context
        return discardNotifier.tryNotifyDiscard(message)
                .contextWrite(context -> KafkaDlqHeaders.into(context, dlqHeaders));
    }

    private void logDlqRetry(ReceiverRecord<String, byte[]> msj, Retry.RetrySignal signal) {
        if (signal.totalRetries() == 0) {
            log.warn("Unable to send message {} from topic {} to its DLQ, it is kept and sent again until it is "
                    + "there: {}", msj.key(), msj.topic(), signal.failure().toString());
        } else {
            log.debug("Unable to send message {} from topic {} to its DLQ (attempt {}): {}", msj.key(), msj.topic(),
                    signal.totalRetries() + 1, signal.failure().toString());
        }
    }

    private static boolean causedBy(Throwable err, Class<? extends Throwable> type) {
        for (Throwable cause = err; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether retrying the handler may succeed. A message that cannot be read (it cannot be converted, or it does
     * not match its schema) fails the same way every time.
     */
    private static boolean isRetryable(Throwable err) {
        for (Throwable cause = err; cause != null; cause = cause.getCause()) {
            // The schema could not be obtained, which says nothing about the message: the registry may answer later
            if (cause instanceof SchemaResolutionException) {
                return true;
            }
            if (cause instanceof MessageConversionException || cause instanceof SchemaValidationException
                    || cause instanceof ClassCastException) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports every failed attempt of a message retried in place. The first one is logged at WARN with its stack
     * trace, the next ones at DEBUG, so a message that keeps failing does not flood the log.
     */
    private void onFailedAttempt(ReceiverRecord<String, byte[]> msj, Message message, String executorPath,
                                 Instant initTime, Retry.RetrySignal signal) {
        final Throwable err = signal.failure();
        final long attempt = signal.totalRetries() + 1;
        metrics.handlerRetried(msj.topic());
        logExecution(executorPath, initTime, false);
        sendErrorToCustomReporter(err, message, attempt > 1);
        if (attempt == 1) {
            log.warn("Handler of message {} from topic {} failed, retrying it every {} ms until it succeeds",
                    msj.key(), msj.topic(), retryDelay.toMillis(), err);
        } else {
            log.debug("Handler of message {} from topic {} failed again (attempt {}): {}", msj.key(), msj.topic(),
                    attempt, err.toString());
        }
    }

    private void logExecution(String executorPath, Instant initTime, boolean success) {
        try {
            final Instant afterExecutionTime = Instant.now();
            final long timeElapsed = Duration.between(initTime, afterExecutionTime).toMillis();
            doLogExecution(executorPath, timeElapsed);
            customReporter.reportMetric(objectType, executorPath, timeElapsed, success);
        } catch (Exception e) {
            log.warn("Unable to send execution metrics!", e);
        }

    }

    private void reportErrorMetric(ReceiverRecord<String, byte[]> msj, Instant initTime) {
        String executorPath;
        try {
            executorPath = getExecutorPath(msj);
        } catch (Exception e) {
            executorPath = "unknown";
        }
        logExecution(executorPath, initTime, false);
    }

    private void doLogExecution(String executorPath, long timeElapsed) {
        log.debug("{} with path {} handled, took {} ms", objectType, executorPath, timeElapsed);
    }


    /**
     * Logs a failed message in a single {@code WARN} line: where it comes from (topic, partition, offset, key), its
     * size and the class of the error. Its stack trace, its headers and its body are logged at {@code DEBUG} only:
     * they may carry personal data or credentials, and a message that keeps failing would write them again and again.
     */
    protected void logError(Throwable err, ReceiverRecord<String, byte[]> msj, FallbackStrategy strategy) {
        final String key = sanitize(msj.key());
        final int size = msj.value() == null ? 0 : msj.value().length;
        log.warn("{} - topic {}, partition {}, offset {}, {} bytes, failed with {} (stack trace, headers and body at "
                        + "DEBUG)", format(strategy.message, key), msj.topic(), msj.partition(), msj.offset(), size,
                err.getClass().getName());
        if (log.isDebugEnabled()) {
            try {
                log.debug("Message {} of topic {}, partition {}, offset {} failed. Headers: {} Body: {}", key,
                        msj.topic(), msj.partition(), msj.offset(), headersOf(msj),
                        msj.value() == null ? null : new String(msj.value(), StandardCharsets.UTF_8), err);
            } catch (Exception e) {
                log.debug("Unable to log the content of message {}", key, e);
            }
        }
    }

    private static String headersOf(ReceiverRecord<String, byte[]> msj) {
        StringJoiner headers = new StringJoiner(", ", "{", "}");
        for (Header header : msj.headers()) {
            headers.add(header.key() + "="
                    + (header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8)));
        }
        return headers.toString();
    }

    /**
     * The key comes from the producer: a line break in it would let it forge lines of the log.
     */
    private static String sanitize(String value) {
        return value == null ? null : value.replace('\r', '_').replace('\n', '_');
    }

    private Function<Message, Mono<Object>> getExecutor(String path) {
        final Function<Message, Mono<Object>> handler = handlers.get(path);
        return handler != null ? handler : computeRawMessageHandler(path);
    }

    private Function<Message, Mono<Object>> computeRawMessageHandler(String commandId) {
        return handlers.computeIfAbsent(commandId, s ->
                rawMessageHandler(commandId)
        );
    }

    protected abstract Function<Message, Mono<Object>> rawMessageHandler(String executorPath);

    protected abstract String getExecutorPath(ReceiverRecord<String, byte[]> msj);

    protected Function<Mono<Object>, Mono<Object>> enrichPostProcess(Message msg) {
        return identity();
    }

    private void sendErrorToCustomReporter(final Throwable err, final Message message, final boolean redelivered) {
        try {
            customReporter.reportError(err, message, parseMessageForReporter(message), redelivered)
                    .subscribeOn(errorReporterScheduler)
                    .doOnError(t -> log.warn("Error sending error to external reporter", t))
                    .subscribe();
        } catch (Throwable t) {
            log.warn("Error in scheduler when sending error to external reporter", t);
        }
    }

    protected abstract Object parseMessageForReporter(Message msj);
}


