package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
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
import java.util.function.Function;
import java.util.logging.Level;

import static java.lang.String.format;
import static java.util.function.Function.identity;
import static reactor.core.publisher.Mono.defer;

@Log
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class GenericMessageListener {
    public static final int DEFAULT_RETRIES_DLQ = 10;
    private final ConcurrentHashMap<String, Function<Message, Mono<Object>>> handlers = new ConcurrentHashMap<>();
    private final Receiver receiver;
    private final ReactiveMessageListener messageListener;
    protected final String queueName;
    private final Scheduler scheduler = Schedulers.newParallel(getClass().getSimpleName(), 12);
    private final Scheduler errorReporterScheduler = Schedulers.newBoundedElastic(4, 256, "errorReporterScheduler");

    private final boolean useDLQRetries;
    private final boolean createTopology;
    private final long maxRetries;
    private final Duration retryDelay;
    private final DiscardNotifier discardNotifier;
    private final String objectType;
    private final CustomReporter customReporter;
    private volatile Flux<AcknowledgableDelivery> messageFlux;

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

        ConsumeOptions consumeOptions = new ConsumeOptions();
        consumeOptions.qos(messageListener.prefetchCount());
        consumeOptions.consumerTag(InstanceIdentifier.getInstanceId(getKind()));

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

        onTerminate();
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
            final Instant init = Instant.now();
            return handle(msj, init)
                    .doOnSuccess(AcknowledgableDelivery::ack)
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
            log.log(Level.SEVERE, format("ATTENTION !! Outer error protection reached for %s, in Async Consumer!! " +
                    "Severe Warning! ", msj.getProperties().getMessageId()));
            return Mono.error(e);
        }
    }

    private void onTerminate() {
        messageFlux
                .doOnTerminate(this::onTerminate)
                .subscribe(new LoggerSubscriber<>(getClass().getName()));
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
            log.log(Level.SEVERE,
                    format("Error encounter while processing message %s: %s", messageID, err.toString()), err
            );
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
        sendErrorToCustomReporter(err, rabbitMessage, redeliver || retryNumber > 0);
        if (hasLocalRetries() || retryNumber >= maxRetries) { // Discard
            logError(err, msj, FallbackStrategy.DEFINITIVE_DISCARD);
            return discardNotifier
                    .notifyDiscard(rabbitMessage)
                    .doOnSuccess(_a -> msj.ack()).thenReturn(msj);
        } else if (useDLQRetries) { // DLQ retries
            logError(err, msj, FallbackStrategy.RETRY_DLQ);
            msj.nack(false);
            return Mono.just(msj);
        } else { // infinity fast retries
            logError(err, msj, FallbackStrategy.FAST_RETRY);
            return Mono.just(msj).delayElement(retryDelay).doOnNext(m -> m.nack(true));
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
}


