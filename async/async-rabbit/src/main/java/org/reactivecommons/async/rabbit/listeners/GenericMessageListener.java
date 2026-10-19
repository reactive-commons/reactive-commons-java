package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import io.micrometer.core.instrument.Timer;
import lombok.extern.java.Log;
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
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final ConcurrentHashMap<String, Function<Message, Mono<Object>>> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);
    private final AtomicBoolean firstRestartWarning = new AtomicBoolean();
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
    private final ConsumerMetrics metrics;
    private final Duration handlerTimeout;
    private volatile Flux<AcknowledgableDelivery> messageFlux;
    private final AtomicReference<Disposable> activeSubscription = new AtomicReference<>();

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
        ConsumeOptions consumeOptions = new ConsumeOptions()
                .qos(messageListener.prefetchCount())
                .consumerTag(tag)
                .exceptionHandler(FailFastAcknowledgmentHandler.INSTANCE)
                .channelCallback(channel -> {
                    liveness.onSubscribed(tag);
                    channel.addShutdownListener(cause -> {
                        log.log(Level.WARNING, cause, () -> "Channel shutdown detected in queue " + queueName
                                + " channel open: " + channel.isOpen() +
                                " connection open: " + channel.getConnection().isOpen());
                        if (channel.getConnection().isOpen() && !channel.isOpen()) {
                            log.warning("Recovering listener for queue: " + queueName);
                            metrics.recordRestart(ConsumerMetrics.REASON_CHANNEL_SHUTDOWN);
                            scheduleRestart();
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
            final Instant init = Instant.now();
            return handle(msj, init)
                    .doOnSuccess(d -> {
                        d.ack();
                        metrics.recordOutcome(ConsumerMetrics.OUTCOME_ACK);
                    })
                    .onErrorResume(err -> requeueOrAck(msj, err, init));
        }, messageListener.maxConcurrency());
    }

    protected Mono<AcknowledgableDelivery> handle(AcknowledgableDelivery msj, Instant initTime) {
        try {
            final String executorPath = getExecutorPath(msj);
            final Function<Message, Mono<Object>> handler = getExecutor(executorPath);
            final Message message = RabbitMessage.fromDelivery(msj, executorPath);

            Mono<Object> flow = defer(() -> handler.apply(message))
                    .transform(enrichPostProcess(message));
            if (handlerTimeout != null) {
                flow = flow.timeout(handlerTimeout);
            }
            if (hasLocalRetries()) {
                flow = flow.retryWhen(Retry.fixedDelay(maxRetries, retryDelay))
                        .onErrorMap(err -> {
                            if (err.getMessage() != null && err.getMessage().contains("Retries exhausted")) {
                                log.warning(err.getMessage());
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
                    scheduleRestart();
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
    private void scheduleRestart() {
        if (stopped.get() || !restartScheduled.compareAndSet(false, true)) {
            return;
        }
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

    protected void logError(Throwable err, AcknowledgableDelivery msj, FallbackStrategy strategy) {
        String messageID = msj.getProperties().getMessageId();
        try {
            log.log(Level.SEVERE, format("Error encounter while processing message %s", messageID), err);
            log.warning(format("Message %s Headers: %s", messageID, msj.getProperties().getHeaders().toString()));
            log.warning(format("Message %s Body: %s", messageID, new String(msj.getBody())));
        } catch (Exception e) {
            log.log(Level.SEVERE, "Error Login message Content!!", e);
        } finally {
            log.warning(format(strategy.message, messageID));
        }
    }

    private Function<Message, Mono<Object>> getExecutor(String path) {
        return handlers.computeIfAbsent(path, this::rawMessageHandler);
    }

    protected abstract Function<Message, Mono<Object>> rawMessageHandler(String executorPath);

    protected abstract String getExecutorPath(AcknowledgableDelivery msj);

    protected Function<Mono<Object>, Mono<Object>> enrichPostProcess(Message msg) {
        return identity();
    }

    private Mono<AcknowledgableDelivery> requeueOrAck(AcknowledgableDelivery msj, Throwable err, Instant init) {
        final long retryNumber = getRetryNumber(msj);
        final Message rabbitMessage = RabbitMessage.fromDelivery(msj);
        final boolean redeliver = msj.getEnvelope().isRedeliver();
        reportErrorMetric(msj, init);
        metrics.recordOutcome(ConsumerMetrics.OUTCOME_HANDLER_ERROR);
        sendErrorToCustomReporter(err, rabbitMessage, redeliver || retryNumber > 0);
        // maxRetries == -1 without DLQ means infinite fast retries, never discard
        if (hasLocalRetries() || (maxRetries != -1 && retryNumber >= maxRetries)) { // Discard
            logError(err, msj, FallbackStrategy.DEFINITIVE_DISCARD);
            return discardNotifier
                    .notifyDiscard(rabbitMessage)
                    .doOnSuccess(_a -> {
                        msj.ack();
                        metrics.recordOutcome(ConsumerMetrics.OUTCOME_DISCARD);
                    })
                    .thenReturn(msj);
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

    private Long getRetryNumber(AcknowledgableDelivery delivery) {
        return Optional.ofNullable(delivery.getProperties())
                .map(AMQP.BasicProperties::getHeaders)
                .map(x -> (List<HashMap>) x.get("x-death"))
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .map(hashMap -> (Long) hashMap.get("count"))
                .orElse(0L);
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


