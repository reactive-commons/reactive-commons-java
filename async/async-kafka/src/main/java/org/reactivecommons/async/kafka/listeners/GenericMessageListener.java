package org.reactivecommons.async.kafka.listeners;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.FallbackStrategy;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.utils.LoggerSubscriber;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.validation.SchemaValidationException;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static java.lang.String.format;
import static java.util.function.Function.identity;

@Slf4j
public abstract class GenericMessageListener {
    private final ConcurrentHashMap<String, Function<Message, Mono<Object>>> handlers = new ConcurrentHashMap<>();
    private final ReactiveMessageListener messageListener;
    private final Scheduler scheduler = Schedulers.newParallel(getClass().getSimpleName(), 12);
    private final Scheduler errorReporterScheduler = Schedulers.newBoundedElastic(4, 256,
            "errorReporterScheduler");

    private final List<String> topics;
    private final String groupId;
    private final boolean useDLQ;
    private final boolean createTopology;
    private final long maxRetries;
    private final Duration retryDelay;
    private final DiscardNotifier discardNotifier;
    private final String objectType;
    private final CustomReporter customReporter;
    private volatile Flux<ReceiverRecord<String, byte[]>> messageFlux;
    private final Disposable.Swap subscription = Disposables.swap();
    private volatile boolean stopped;

    protected GenericMessageListener(ListenerContext<ReactiveMessageListener> context, String objectType,
                                     String groupId, List<String> topics) {
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
     * publish there once a message exhausts its retries.
     */
    protected List<String> getDlqBaseTopics() {
        return topics;
    }

    public void startListener(TopologyCreator creator) {
        log.info("Handling the messages of each partition one at a time, in order, for topics: {}", topics);

        if (useDLQ) {
            log.info("ATTENTION! Using DLQ Strategy for retries with {} + 1 Max Retries configured!", maxRetries);
        } else if (hasRetries()) {
            log.info("Retrying a failed message {} times, every {} ms, before discarding it", maxRetries,
                    retryDelay.toMillis());
        } else {
            log.info("Retrying a failed message every {} ms until it succeeds", retryDelay.toMillis());
        }

        if (createTopology) {
            this.messageFlux = setUpBindings(creator)
                    .thenMany(this.messageListener.listen(groupId, topics)
                            .doOnError(err -> log.error("Error listening queue", err))
                            .transform(this::consumeFaultTolerant));
        } else {
            this.messageFlux = this.messageListener.listen(groupId, topics)
                    .doOnError(err -> log.error("Error listening queue", err))
                    .transform(this::consumeFaultTolerant);
        }

        onTerminate();
    }

    /**
     * Subscribes the flow again when it terminates, unless the listener was stopped.
     */
    private void onTerminate() {
        if (stopped) {
            return;
        }
        LoggerSubscriber<ReceiverRecord<String, byte[]>> subscriber = new LoggerSubscriber<>(getClass().getName());
        subscription.update(subscriber);
        messageFlux.doOnTerminate(this::onTerminate).subscribe(subscriber);
    }

    /**
     * Stops consuming (cancelling the flow closes the Kafka consumer) and releases the threads of the listener.
     * Idempotent.
     */
    public void stop() {
        stopped = true;
        subscription.dispose();
        scheduler.dispose();
        errorReporterScheduler.dispose();
    }

    /**
     * Handles the messages of each partition one at a time, in the order of the partition, and the partitions at the
     * same time. A message is acknowledged once handled, so the offsets of a partition are acknowledged in order:
     * acknowledging an offset commits every previous one of the partition, so a later message must never be
     * acknowledged while an earlier one is still being handled.
     */
    private Flux<ReceiverRecord<String, byte[]>> consumeFaultTolerant(Flux<ReceiverRecord<String, byte[]>> messageFlux) {
        return messageFlux
                .groupBy(msj -> new TopicPartition(msj.topic(), msj.partition()))
                .flatMap(partition -> partition.concatMap(this::handleAndAcknowledge), Integer.MAX_VALUE);
    }

    private Mono<ReceiverRecord<String, byte[]>> handleAndAcknowledge(ReceiverRecord<String, byte[]> msj) {
        final Instant init = Instant.now();
        return handle(msj, init)
                .doOnSuccess(r -> r.receiverOffset().acknowledge())
                .onErrorResume(err -> requeueOrAck(msj, err, init));
    }

    protected Mono<ReceiverRecord<String, byte[]>> handle(ReceiverRecord<String, byte[]> msj, Instant initTime) {
        try {
            final String executorPath = getExecutorPath(msj);
            final Function<Message, Mono<Object>> handler = getExecutor(executorPath);
            validateInbound(msj);
            final Message message = KafkaMessage.fromDelivery(msj, executorPath);

            Mono<Object> flow = Mono.defer(() -> handler.apply(message))
                    .transform(enrichPostProcess(message));
            if (hasRetries()) {
                flow = flow.retryWhen(Retry.fixedDelay(maxRetries, retryDelay))
                        .onErrorMap(err -> {
                            if (err.getMessage() != null && err.getMessage().contains("Retries exhausted")) {
                                log.warn(err.getMessage());
                                return err.getCause();
                            }
                            return err;
                        });
            } else if (retriesInPlaceForever()) {
                flow = flow.retryWhen(Retry.fixedDelay(Long.MAX_VALUE, retryDelay)
                        .filter(GenericMessageListener::isRetryable)
                        .scheduler(scheduler)
                        .doBeforeRetry(signal -> onFailedAttempt(msj, message, executorPath, initTime, signal)));
            }
            return flow.doOnSuccess(o -> logExecution(executorPath, initTime, true))
                    .subscribeOn(scheduler)
                    .thenReturn(msj);
        } catch (SchemaValidationException e) {
            // Kept apart from the generic case because a rejected record is not a failure of the listener
            // itself.
            return Mono.error(e);
        } catch (Exception e) {
            log.error("ATTENTION !! Outer error protection reached for {}, in Async Consumer!! Severe Warning! ",
                    msj.key());
            return Mono.error(e);
        }
    }

    private void validateInbound(ReceiverRecord<String, byte[]> msj) {
        SchemaValidator validator = messageListener.getSchemaValidator();
        if (validator != null) {
            validator.validateInbound(msj.topic(), msj.value(), msj.headers());
        }
    }

    /**
     * Discards a message whose retries are exhausted or that can never be handled.
     */
    private Mono<ReceiverRecord<String, byte[]>> requeueOrAck(ReceiverRecord<String, byte[]> msj, Throwable err,
                                                              Instant init) {
        final Message message = KafkaMessage.fromDelivery(msj);
        reportErrorMetric(msj, init);
        sendErrorToCustomReporter(err, message, hasRetries());
        logError(err, msj, FallbackStrategy.DEFINITIVE_DISCARD);
        if (useDLQ) {
            return discardNotifier
                    .notifyDiscard(message)
                    .doOnSuccess(_a -> msj.receiverOffset().acknowledge())
                    .thenReturn(msj);
        }
        if (retriesInPlaceForever()) {
            // Only a message that can never be handled gets here: it is skipped, so it does not hold the
            // messages after it
            msj.receiverOffset().acknowledge();
        }
        return Mono.just(msj);
    }

    /**
     * Whether retrying the handler may succeed. A message that cannot be read (it cannot be converted, or it does
     * not match its schema) fails the same way every time.
     */
    private static boolean isRetryable(Throwable err) {
        for (Throwable cause = err; cause != null; cause = cause.getCause()) {
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


    protected void logError(Throwable err, ReceiverRecord<String, byte[]> msj, FallbackStrategy strategy) {
        String messageID = msj.key();
        try {
            log.error("Error encounter while processing message {}", messageID, err);
            log.warn("Message {} Headers: {}", messageID, msj.headers());
            log.warn("Message {} Body: {}", messageID, new String(msj.value()));
        } catch (Exception e) {
            log.error("Error Login message Content!!", e);
        } finally {
            log.warn("{}", format(strategy.message, messageID));
        }
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


