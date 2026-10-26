package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Timer;
import lombok.extern.java.Log;
import org.reactivecommons.async.commons.exceptions.MissingHandlerException;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.FallbackStrategy;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.utils.LoggerSubscriber;
import org.reactivecommons.async.rabbit.InstanceIdentifier;
import org.reactivecommons.async.rabbit.RabbitMessage;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;

import static java.lang.String.format;
import static java.util.function.Function.identity;
import static reactor.core.publisher.Mono.defer;

@Log
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class GenericMessageListener {
    public static final int DEFAULT_RETRIES_DLQ = 10;
    static final Duration RESTART_MIN_BACKOFF = Duration.ofMillis(200);
    static final Duration RESTART_MAX_BACKOFF = Duration.ofSeconds(5);
    /**
     * The handlers are resolved per routing key, which wildcard subscriptions do not bound: only this many are cached,
     * the others are resolved on each message.
     */
    static final int MAX_CACHED_HANDLERS = 1_000;
    /**
     * A delivery waiting this long for a handler thread is reported: the handler pool is shared by every listener
     * and the broker closes the channel of a delivery not acknowledged within its consumer_timeout (30 minutes by
     * default), requeueing every delivery of the channel.
     */
    static final Duration HANDLER_WAIT_WARNING = Duration.ofMinutes(1);
    private static final long DRAIN_POLL_MILLIS = 20;
    private final ConcurrentHashMap<String, Function<Message, Mono<Object>>> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);
    private final AtomicBoolean firstRestartWarning = new AtomicBoolean();
    private final AtomicLong lastHandlerWaitWarning = new AtomicLong();
    private final Receiver receiver;
    private final ReactiveMessageListener messageListener;
    protected final String queueName;

    private final boolean useDLQRetries;
    private final boolean createTopology;
    private final long maxRetries;
    private final Duration retryDelay;
    private final DiscardNotifier discardNotifier;
    private final String objectType;
    private final CustomReporter customReporter;
    private final ListenerLiveness liveness;
    protected final ConsumerMetrics metrics;
    private final Duration handlerTimeout;
    private volatile Flux<AcknowledgableDelivery> messageFlux;
    private final AtomicReference<Disposable> activeSubscription = new AtomicReference<>();
    /**
     * The channel the consumer of the current subscription is registered on, to cancel it on shutdown.
     */
    private final AtomicReference<Channel> consumingChannel = new AtomicReference<>();
    private volatile String consumerTag;

    protected GenericMessageListener(String queueName, ReactiveMessageListener listener, boolean useDLQRetries,
                                     boolean createTopology, long maxRetries, long retryDelay,
                                     DiscardNotifier discardNotifier, String objectType,
                                     CustomReporter customReporter) {
        this.receiver = listener.receiver();
        this.queueName = queueName;
        this.messageListener = listener;
        this.createTopology = createTopology;
        this.maxRetries = resolveRetries(useDLQRetries, maxRetries);
        this.retryDelay = Duration.ofMillis(retryDelay);
        this.useDLQRetries = useDLQRetries;
        this.discardNotifier = discardNotifier;
        this.objectType = objectType;
        this.customReporter = customReporter;
        this.liveness = listener.subscriberRegistry() != null
                ? listener.subscriberRegistry().register(queueName)
                : new ListenerLiveness(queueName);
        this.metrics = new ConsumerMetrics(listener.meterRegistry(), listener.domain(), queueName, liveness);
        this.handlerTimeout = listener.handlerTimeout();
    }

    private static long resolveRetries(boolean useDLQRetries, long maxRetries) {
        return useDLQRetries && maxRetries == -1 ? DEFAULT_RETRIES_DLQ : maxRetries;
    }

    private boolean hasLocalRetries() {
        return !useDLQRetries && maxRetries != -1;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        return Mono.empty();
    }

    public void startListener() {
        log.log(Level.INFO, "Using max concurrency {0}, in queue: {1}",
                new Object[]{messageListener.maxConcurrency(), queueName}
        );
        if (useDLQRetries) {
            log.log(Level.INFO, "ATTENTION! Using DLQ Strategy for retries with {0} + 1 Max Retries configured!",
                    new Object[]{maxRetries}
            );
        } else {
            log.log(Level.INFO, "ATTENTION! Using infinite fast retries as Retry Strategy");
        }

        final String tag = consumerTag();
        this.consumerTag = tag;
        ConsumeOptions consumeOptions = new ConsumeOptions()
                .qos(messageListener.prefetchCount())
                .consumerTag(tag)
                .exceptionHandler(FailFastAcknowledgmentHandler.INSTANCE)
                // active only once the broker registered the consumer, not when its channel opens
                .consumerRegisteredCallback(liveness::onSubscribed)
                .channelCallback(channel -> {
                    consumingChannel.set(channel);
                    channel.addShutdownListener(cause -> {
                        log.log(Level.WARNING, cause, () -> "Channel shutdown detected in queue " + queueName
                                + " channel open: " + channel.isOpen() +
                                " connection open: " + channel.getConnection().isOpen());
                        if (channel.getConnection().isOpen() && !channel.isOpen()) {
                            log.warning("Recovering listener for queue: " + queueName);
                            scheduleRestart(ConsumerMetrics.REASON_CHANNEL_SHUTDOWN);
                        }
                    });
                });

        if (createTopology) {
            this.messageFlux = setUpBindings(messageListener.topologyCreator())
                    .thenMany(receiver.consumeManualAck(queueName, consumeOptions)
                            .doOnError(err -> log.log(Level.SEVERE, "Error listening queue " + getRootCauseMessage(err), err))
                            .transform(this::consumeFaultTolerant));
        } else {
            this.messageFlux = receiver.consumeManualAck(queueName, consumeOptions)
                    .doOnError(err -> log.log(Level.SEVERE, "Error listening queue " + getRootCauseMessage(err), err))
                    .transform(this::consumeFaultTolerant);
        }

        subscribe();
    }

    private String getRootCauseMessage(Throwable err) {
        Throwable root = err;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private Flux<AcknowledgableDelivery> consumeFaultTolerant(Flux<AcknowledgableDelivery> messageFlux) {
        return messageFlux.flatMap(msj -> {
            liveness.onDelivery();
            metrics.recordMessageSize(msj.getBody().length);
            if (isRedelivered(msj)) {
                metrics.recordRedelivered();
            }
            final Instant init = Instant.now();
            metrics.recordDeliveryStarted();
            return handle(msj, init)
                    // handle may complete empty (e.g. a query discarded by timeout): acknowledge the delivery itself
                    .doOnSuccess(ignored -> {
                        msj.ack();
                        metrics.recordOutcome(ConsumerMetrics.OUTCOME_ACK);
                    })
                    .onErrorResume(err -> requeueOrAck(msj, err, init))
                    // after the ack/nack (a requeue waits retryDelay first), or cancelled when the listener stops
                    .doFinally(signal -> metrics.recordDeliveryFinished());
        }, messageListener.maxConcurrency());
    }

    protected Mono<AcknowledgableDelivery> handle(AcknowledgableDelivery msj, Instant initTime) {
        try {
            final String executorPath = getExecutorPath(msj);
            final Function<Message, Mono<Object>> handler = getExecutor(executorPath);
            final Message message = RabbitMessage.fromDelivery(msj, executorPath);
            // only a resolved handler gets here (an unregistered path fails in getExecutor), and the handlers are
            // cached up to MAX_CACHED_HANDLERS: the ones beyond the cache bucket to "unknown", bounding the cardinality
            final String handlerTag = handlers.containsKey(executorPath)
                    ? executorPath : ConsumerMetrics.UNKNOWN_HANDLER;

            final AtomicBoolean started = new AtomicBoolean();
            final AtomicReference<Instant> attemptStart = new AtomicReference<>();
            Mono<Object> flow = defer(() -> {
                attemptStart.set(Instant.now()); // each local retry re-runs the defer: fresh per-attempt timing
                if (started.compareAndSet(false, true)) { // runs on the handler thread; local retries run it again
                    onHandlerStarted(Duration.between(initTime, Instant.now()));
                }
                return handler.apply(message);
            })
                    // on the defer, not on the handler's Mono: a handler that throws instead of returning a Mono (e.g.
                    // the message cannot be converted) is an error too. A handlerTimeout cancels this upstream, so
                    // neither fires then: the timeout is recorded below
                    .doOnSuccess(ignored -> metrics.recordHandlerExecution(handlerTag,
                            ConsumerMetrics.EXECUTION_SUCCESS, Duration.between(attemptStart.get(), Instant.now())))
                    .doOnError(err -> metrics.recordHandlerExecution(handlerTag,
                            ConsumerMetrics.EXECUTION_ERROR, Duration.between(attemptStart.get(), Instant.now())))
                    .transform(enrichPostProcess(message));
            if (handlerTimeout != null) {
                // a dedicated type, so a TimeoutException thrown by the handler itself is not counted as one
                flow = flow.timeout(handlerTimeout, Mono.error(() -> new HandlerTimeoutException(handlerTimeout)))
                        .doOnError(HandlerTimeoutException.class, err -> metrics.recordHandlerExecution(
                                handlerTag, ConsumerMetrics.EXECUTION_TIMEOUT,
                                Duration.between(attemptStart.get(), Instant.now())));
            }
            if (hasLocalRetries()) {
                flow = flow.retryWhen(Retry.fixedDelay(maxRetries, retryDelay)
                                .doBeforeRetry(signal -> metrics.recordHandlerRetry()))
                        .onErrorMap(err -> {
                            if (err.getMessage() != null && err.getMessage().contains("Retries exhausted")) {
                                log.warning(err.getMessage());
                                metrics.recordRetriesExhausted();
                                return err.getCause();
                            }
                            return err;
                        });
            }
            final Timer latency = metrics.handlerLatency();
            if (latency != null) {
                flow = flow.doFinally(signal -> latency.record(Duration.between(initTime, Instant.now())));
            }
            return flow.doOnSuccess(o -> logExecution(executorPath, initTime, true))
                    .subscribeOn(ListenerSchedulers.handlers()).thenReturn(msj);
        } catch (Exception e) {
            log.log(Level.SEVERE, format("ATTENTION !! Outer error protection reached for %s, in Async Consumer!! " +
                    "Severe Warning! ", msj.getProperties().getMessageId()));
            return Mono.error(e);
        }
    }

    /**
     * First step of a graceful shutdown: the broker stops delivering to this listener ({@code basic.cancel}), while
     * the deliveries already received keep being handled and acknowledged on their channel. The listener is not
     * restarted anymore. Follow with {@link #awaitInFlight(Duration)} and {@link #stop()}.
     */
    public void stopConsuming() {
        stopped.set(true);
        final Channel channel = consumingChannel.get();
        if (channel == null || !channel.isOpen()) {
            return;
        }
        try {
            channel.basicCancel(consumerTag);
        } catch (Exception e) {
            // not registered (yet) or the channel is closing: stop() closes the channel anyway
            log.log(Level.FINE, e, () -> "Consumer of queue " + queueName + " not cancelled");
        }
    }

    /**
     * Waits until every delivery received by this listener has been acknowledged (or nacked).
     *
     * @param timeout how long to wait at most
     * @return whether no delivery is in flight anymore
     */
    public boolean awaitInFlight(Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (metrics.inFlight() > 0) {
            if (System.nanoTime() - deadline >= 0) {
                log.warning(() -> metrics.inFlight() + " deliveries of queue " + queueName + " still in flight after "
                        + timeout + ": the broker redelivers them");
                return false;
            }
            try {
                Thread.sleep(DRAIN_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Stops consuming from the queue. The current subscription is cancelled and the listener is not restarted.
     */
    public void stop() {
        stopped.set(true);
        final Disposable subscription = activeSubscription.getAndSet(null);
        if (subscription != null) {
            subscription.dispose();
        }
        if (messageListener.subscriberRegistry() != null) {
            messageListener.subscriberRegistry().unregister(queueName);
        }
    }

    private void subscribe() {
        if (stopped.get()) {
            return;
        }
        final AtomicReference<Throwable> terminationCause = new AtomicReference<>();
        final Disposable subscription = messageFlux
                .doOnError(terminationCause::set)
                .doOnTerminate(() -> {
                    liveness.onTerminated(terminationCause.get());
                    // completed without error: the broker cancelled the consumer (e.g. its queue was deleted)
                    scheduleRestart(terminationCause.get() != null
                            ? ConsumerMetrics.REASON_ERROR : ConsumerMetrics.REASON_CANCEL);
                })
                .subscribeWith(new LoggerSubscriber<>(getClass().getName()));
        final Disposable previous = activeSubscription.getAndSet(subscription);
        if (previous != null && previous != subscription) {
            previous.dispose();
        }
        if (stopped.get() && activeSubscription.compareAndSet(subscription, null)) {
            // stop() ran while this subscription was being created
            subscription.dispose();
        }
    }

    /**
     * Schedules a new subscription after the current one terminated. Uses an exponential backoff with the same
     * bounds as the sender's confirm-flow re-subscription (200 ms → 5 s). The first restart per listener is logged
     * at WARNING so an outage is visible; subsequent attempts log at FINE to avoid flooding when the broker is
     * continuously unavailable. The restart runs on a separate scheduler so it never recurses from the termination
     * callback and overflows the stack if the new subscription fails synchronously.
     */
    private void scheduleRestart(String reason) {
        if (stopped.get() || !restartScheduled.compareAndSet(false, true)) {
            return; // the restart already scheduled covers this trigger too: counted once, with the first reason
        }
        metrics.recordRestart(reason);
        long consecutive = liveness.consecutiveRestartFailures();
        long delayMs = backoffMillis(consecutive);
        Level level = consecutive <= 1 && firstRestartWarning.compareAndSet(false, true)
                ? Level.WARNING : Level.FINE;
        log.log(level, () -> "Re-subscribing listener for queue " + queueName + " in " + delayMs
                + " ms (attempt " + Math.max(1, consecutive) + ")");
        Schedulers.boundedElastic().schedule(() -> {
            restartScheduled.set(false);
            subscribe();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static long backoffMillis(long consecutive) {
        if (consecutive <= 0) {
            return RESTART_MIN_BACKOFF.toMillis();
        }
        long shift = Math.min(consecutive - 1, 30);
        long millis = RESTART_MIN_BACKOFF.toMillis() << shift;
        if (millis < 0) {
            return RESTART_MAX_BACKOFF.toMillis();
        }
        return Math.min(millis, RESTART_MAX_BACKOFF.toMillis());
    }

    private void logExecution(String executorPath, Instant initTime, boolean success) {
        try {
            final Instant afterExecutionTime = Instant.now();
            final long timeElapsed = Duration.between(initTime, afterExecutionTime).toMillis();
            doLogExecution(executorPath, timeElapsed);
            customReporter.reportMetric(objectType, executorPath, timeElapsed, success);
        } catch (Exception e) {
            log.log(Level.WARNING, "Unable to send execution metrics!", e);
        }

    }

    private void reportErrorMetric(AcknowledgableDelivery msj, Instant initTime) {
        String executorPath;
        try {
            executorPath = getExecutorPath(msj);
        } catch (Exception e) {
            executorPath = "unknown";
        }
        logExecution(executorPath, initTime, false);
    }

    private void doLogExecution(String executorPath, long timeElapsed) {
        log.log(Level.FINE, String.format("%s with path %s handled, took %d ms",
                objectType, executorPath, timeElapsed));
    }

    /**
     * Logs a failed message, which happens again on every retry: only the beginning of its body is written.
     */
    protected void logError(Throwable err, AcknowledgableDelivery msj, FallbackStrategy strategy) {
        final String messageID = msj.getProperties().getMessageId();
        try {
            log.log(Level.SEVERE, err, () -> format("Error encounter while processing message %s of queue %s "
                    + "(%d bytes)", messageID, queueName, msj.getBody().length));
            log.warning(() -> format("Message %s Headers: %s Body: %s", messageID, msj.getProperties().getHeaders(),
                    RabbitMessage.bodyPreview(msj.getBody())));
        } catch (Exception e) {
            log.log(Level.SEVERE, "Error Login message Content!!", e);
        } finally {
            log.warning(() -> format(strategy.message, messageID));
        }
    }

    private Function<Message, Mono<Object>> getExecutor(String path) {
        final Function<Message, Mono<Object>> cached = handlers.get(path);
        if (cached != null) {
            return cached;
        }
        final Function<Message, Mono<Object>> handler = rawMessageHandler(path);
        if (handlers.size() < MAX_CACHED_HANDLERS) {
            handlers.putIfAbsent(path, handler);
        }
        return handler;
    }

    private void onHandlerStarted(Duration wait) {
        metrics.recordHandlerWait(wait);
        if (wait.compareTo(HANDLER_WAIT_WARNING) < 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long last = lastHandlerWaitWarning.get();
        if (now - last >= HANDLER_WAIT_WARNING.toMillis() && lastHandlerWaitWarning.compareAndSet(last, now)) {
            log.warning(() -> "A delivery of queue " + queueName + " waited " + wait.toSeconds() + " s for a handler"
                    + " thread: the handlers are busy. A delivery not acknowledged within the broker's"
                    + " consumer_timeout gets its channel closed and every delivery of the channel requeued;"
                    + " lower prefetchCount, or set handlerTimeout below consumer_timeout.");
        }
    }

    protected abstract Function<Message, Mono<Object>> rawMessageHandler(String executorPath);

    protected abstract String getExecutorPath(AcknowledgableDelivery msj);

    protected Function<Mono<Object>, Mono<Object>> enrichPostProcess(Message msg) {
        return identity();
    }

    /**
     * Whether retrying a failed message can no longer be of any use, e.g. a query whose caller stopped waiting for
     * its reply. Such a message is discarded instead of retried.
     */
    protected boolean retryIsUseless(AcknowledgableDelivery msj) {
        return false;
    }

    private Mono<AcknowledgableDelivery> requeueOrAck(AcknowledgableDelivery msj, Throwable err, Instant init) {
        if (err instanceof HandlerTimeoutException) {
            metrics.recordHandlerTimeout();
        }
        final long retryNumber = getRetryNumber(msj);
        final Message rabbitMessage = RabbitMessage.fromDelivery(msj);
        reportErrorMetric(msj, init);
        metrics.recordOutcome(ConsumerMetrics.OUTCOME_HANDLER_ERROR);
        sendErrorToCustomReporter(err, rabbitMessage, isRedelivered(msj));
        // maxRetries == -1 without DLQ means infinite fast retries, never discard (unless retrying cannot help)
        if (err instanceof MissingHandlerException || retryIsUseless(msj)
                || hasLocalRetries() || (maxRetries != -1 && retryNumber >= maxRetries)) { // Discard
            logError(err, msj, FallbackStrategy.DEFINITIVE_DISCARD);
            return discard(msj, rabbitMessage);
        } else if (useDLQRetries) { // DLQ retries
            logError(err, msj, FallbackStrategy.RETRY_DLQ);
            msj.nack(false);
            metrics.recordOutcome(ConsumerMetrics.OUTCOME_DLQ);
            return Mono.just(msj);
        } else { // infinity fast retries
            logError(err, msj, FallbackStrategy.FAST_RETRY);
            return Mono.just(msj).delayElement(retryDelay).doOnNext(m -> {
                m.nack(true);
                metrics.recordOutcome(ConsumerMetrics.OUTCOME_NACK);
            });
        }
    }

    /**
     * Acknowledges a discarded message only once its discard is notified: if the notification cannot be published
     * (e.g. the broker blocks the publishing connection) the message is requeued and discarded again later, instead
     * of being acknowledged and lost.
     */
    private Mono<AcknowledgableDelivery> discard(AcknowledgableDelivery msj, Message rabbitMessage) {
        return discardNotifier.tryNotifyDiscard(rabbitMessage)
                .then(Mono.fromCallable(() -> {
                    msj.ack();
                    metrics.recordOutcome(ConsumerMetrics.OUTCOME_DISCARD);
                    return msj;
                }))
                .onErrorResume(notificationError -> {
                    metrics.recordDiscardNotificationFailed();
                    log.log(Level.WARNING, notificationError, () -> "Discard of message "
                            + msj.getProperties().getMessageId() + " in queue " + queueName
                            + " could not be notified: requeued to discard it again");
                    return Mono.just(msj).delayElement(retryDelay).doOnNext(m -> {
                        m.nack(true);
                        metrics.recordOutcome(ConsumerMetrics.OUTCOME_NACK);
                    });
                });
    }

    private void sendErrorToCustomReporter(final Throwable err, final Message message, final boolean redelivered) {
        try {
            customReporter.reportError(err, message, parseMessageForReporter(message), redelivered)
                    .subscribeOn(ListenerSchedulers.errorReporter())
                    .doOnError(t -> log.log(Level.WARNING, "Error sending error to external reporter", t))
                    .subscribe();
        } catch (Throwable t) {
            log.log(Level.WARNING, "Error in scheduler when sending error to external reporter", t);
        }
    }

    /**
     * The times the message was dead-lettered, from the {@code count} of the first entry of its {@code x-death}
     * header. Read without assuming its shape: the header may come from another client (e.g. with a 32-bit
     * {@code count}), and a failure here would terminate the whole listener and redeliver the message in a loop.
     */
    private static long getRetryNumber(AcknowledgableDelivery delivery) {
        final AMQP.BasicProperties properties = delivery.getProperties();
        final Map<String, Object> headers = properties != null ? properties.getHeaders() : null;
        if (headers != null && headers.get("x-death") instanceof List<?> deaths && !deaths.isEmpty()
                && deaths.get(0) instanceof Map<?, ?> lastDeath && lastDeath.get("count") instanceof Number count) {
            return count.longValue();
        }
        return 0L;
    }

    /**
     * Whether the message was delivered before: requeued (the AMQP {@code redelivered} flag, e.g. a fast retry or a
     * channel failure) or dead-lettered back from the retry queue ({@code x-death}).
     */
    private boolean isRedelivered(AcknowledgableDelivery delivery) {
        return (delivery.getEnvelope() != null && delivery.getEnvelope().isRedeliver())
                || getRetryNumber(delivery) > 0;
    }

    /**
     * The handler did not complete within the listener's {@code handlerTimeout}. Still a {@link TimeoutException}
     * for the error reporters, but distinguishable from a timeout raised by the handler itself.
     */
    static final class HandlerTimeoutException extends TimeoutException {
        HandlerTimeoutException(Duration handlerTimeout) {
            super("The handler did not complete within the handlerTimeout of " + handlerTimeout);
        }
    }

    protected abstract Object parseMessageForReporter(Message msj);

    protected abstract String getKind();

    /**
     * Consumer tag of this listener. With automatic connection recovery, consumer tags must be unique across all the
     * channels of a connection: the client records the consumers to recover by tag, so listeners sharing a tag on
     * the same connection overwrite each other and only the last one is recovered.
     *
     * @see <a href="https://www.rabbitmq.com/client-libraries/java-api-guide#automatic-recovery-limitations">Failure
     * Detection and Recovery Limitations</a>
     */
    protected String consumerTag() {
        return InstanceIdentifier.getInstanceId(getKind());
    }
}


