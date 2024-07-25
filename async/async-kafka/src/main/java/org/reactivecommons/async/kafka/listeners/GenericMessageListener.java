package org.reactivecommons.async.kafka.listeners;

import lombok.extern.java.Log;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.FallbackStrategy;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.utils.LoggerSubscriber;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
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
import java.util.logging.Level;

import static java.lang.String.format;
import static java.util.function.Function.identity;
import static reactor.core.publisher.Mono.defer;

@Log
public abstract class GenericMessageListener {
    public static final int DEFAULT_RETRIES_DLQ = 10;
    private final ConcurrentHashMap<String, Function<Message, Mono<Object>>> handlers = new ConcurrentHashMap<>();
    private final ReactiveMessageListener messageListener;
    private final Scheduler scheduler = Schedulers.newParallel(getClass().getSimpleName(), 12);
    private final Scheduler errorReporterScheduler = Schedulers.newBoundedElastic(4, 256, "errorReporterScheduler");

    private final List<String> topics;
    private final String groupId;
    private final boolean useDLQRetries;
    private final boolean createTopology;
    private final long maxRetries;
    private final Duration retryDelay;
    private final DiscardNotifier discardNotifier;
    private final String objectType;
    private final CustomReporter customReporter;
    private volatile Flux<ReceiverRecord<String, byte[]>> messageFlux;

    public GenericMessageListener(ReactiveMessageListener listener, boolean useDLQRetries,
                                  boolean createTopology, long maxRetries, long retryDelay, DiscardNotifier discardNotifier,
                                  String objectType, CustomReporter customReporter, String groupId, List<String> topics) {
        this.groupId = groupId;
        this.topics = topics;
        this.messageListener = listener;
        this.createTopology = createTopology;
        this.maxRetries = resolveRetries(useDLQRetries, maxRetries);
        this.retryDelay = Duration.ofMillis(retryDelay);
        this.useDLQRetries = useDLQRetries;
        this.discardNotifier = discardNotifier;
        this.objectType = objectType;
        this.customReporter = customReporter;
    }

    private static long resolveRetries(boolean useDLQRetries, long maxRetries) {
        return useDLQRetries && maxRetries == -1 ? DEFAULT_RETRIES_DLQ : maxRetries;
    }

    private boolean hasLocalRetries() {
        return !useDLQRetries && maxRetries != -1;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        return creator.createTopics(topics);
    }

    public void startListener(TopologyCreator creator) {
        log.log(Level.INFO, "Using max concurrency {0}, in receiver for topics: {1}", new Object[]{messageListener.getMaxConcurrency(), topics});
        if (useDLQRetries) {
            log.log(Level.INFO, "ATTENTION! Using DLQ Strategy for retries with {0} + 1 Max Retries configured!", new Object[]{maxRetries});
        } else {
            log.log(Level.INFO, "ATTENTION! Using infinite fast retries as Retry Strategy");
        }

        if (createTopology) {
            this.messageFlux = setUpBindings(creator)
                    .thenMany(this.messageListener.listen(groupId, topics)
                            .transform(this::consumeFaultTolerant));
        } else {
            this.messageFlux = this.messageListener.listen(groupId, topics)
                    .doOnError(err -> log.log(Level.SEVERE, "Error listening queue", err))
                    .transform(this::consumeFaultTolerant);
        }

        onTerminate();
    }

    private void onTerminate() {
        messageFlux.doOnTerminate(this::onTerminate)
                .subscribe(new LoggerSubscriber<>(getClass().getName()));
    }

    protected Mono<ReceiverRecord<String, byte[]>> handle(ReceiverRecord<String, byte[]> msj, Instant initTime) {
        try {
            final String executorPath = getExecutorPath(msj);
            final Function<Message, Mono<Object>> handler = getExecutor(executorPath);
            final Message message = KafkaMessage.fromDelivery(msj);

            Mono<Object> flow = defer(() -> handler.apply(message))
                    .transform(enrichPostProcess(message));
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
            return flow.doOnSuccess(o -> logExecution(executorPath, initTime, true))
                    .subscribeOn(scheduler).thenReturn(msj);
        } catch (Exception e) {
            log.log(Level.SEVERE, format("ATTENTION !! Outer error protection reached for %s, in Async Consumer!! Severe Warning! ", msj.key()));
            return Mono.error(e);
        }
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
        log.log(Level.FINE, String.format("%s with path %s handled, took %d ms",
                objectType, executorPath, timeElapsed));
    }

    private Flux<ReceiverRecord<String, byte[]>> consumeFaultTolerant(Flux<ReceiverRecord<String, byte[]>> messageFlux) {
        return messageFlux.flatMap(msj -> {
            final Instant init = Instant.now();
            return handle(msj, init)
                    .doOnSuccess(record -> record.receiverOffset().acknowledge())
                    .onErrorResume(err -> requeueOrAck(msj, err, init));
        }, messageListener.getMaxConcurrency());
    }


    protected void logError(Throwable err, ReceiverRecord<String, byte[]> msj, FallbackStrategy strategy) {
        String messageID = msj.key();
        try {
            log.log(Level.SEVERE, format("Error encounter while processing message %s: %s", messageID, err.toString()), err);
            log.warning(format("Message %s Headers: %s", messageID, msj.headers().toString()));
            log.warning(format("Message %s Body: %s", messageID, new String(msj.value())));
        } catch (Exception e) {
            log.log(Level.SEVERE, "Error Login message Content!!", e);
        } finally {
            log.warning(format(strategy.message, messageID));
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

    private Mono<ReceiverRecord<String, byte[]>> requeueOrAck(ReceiverRecord<String, byte[]> msj, Throwable err, Instant init) {
        final long retryNumber = getRetryNumber(msj);
        final Message message = KafkaMessage.fromDelivery(msj);
        reportErrorMetric(msj, init);
        sendErrorToCustomReporter(err, message, retryNumber > 0);
        if (hasLocalRetries() || retryNumber >= maxRetries) { // Discard // TODO: How manage retries
            logError(err, msj, FallbackStrategy.DEFINITIVE_DISCARD);
            return discardNotifier
                    .notifyDiscard(message)
                    .doOnSuccess(_a -> msj.receiverOffset().acknowledge()).thenReturn(msj);
        } else if (useDLQRetries) { // DLQ retries
            logError(err, msj, FallbackStrategy.RETRY_DLQ);
//            msj.nack(false); // TODO: Check if required
            return Mono.just(msj);
        } else { // infinity fast retries
            logError(err, msj, FallbackStrategy.FAST_RETRY);
            return Mono.just(msj).delayElement(retryDelay);
//                    .doOnNext(m -> m.nack(true)); // TODO: Check if required
        }
    }

    private void sendErrorToCustomReporter(final Throwable err, final Message message, final boolean redelivered) {
        try {
            customReporter.reportError(err, message, parseMessageForReporter(message), redelivered)
                    .subscribeOn(errorReporterScheduler)
                    .doOnError(t -> log.log(Level.WARNING, "Error sending error to external reporter", t))
                    .subscribe();
        } catch (Throwable t) {
            log.log(Level.WARNING, "Error in scheduler when sending error to external reporter", t);
        }
    }

    private Long getRetryNumber(ReceiverRecord<String, byte[]> delivery) {
//        return Optional.ofNullable(delivery.headers().lastHeader())
//                .map(AMQP.BasicProperties::getHeaders)
//                .map(x -> (List<HashMap>) x.get("x-death"))
//                .filter(list -> !list.isEmpty())
//                .map(list -> list.get(0))
//                .map(hashMap -> (Long) hashMap.get("count"))
//                .orElse(0L);
        return 0L;
    }

    protected abstract Object parseMessageForReporter(Message msj);
}


