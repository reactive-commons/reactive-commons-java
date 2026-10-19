package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.ShutdownSignalException;
import lombok.Getter;
import lombok.extern.java.Log;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.commons.exceptions.SendFailureReturnedException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.rabbitmq.ExceptionHandlers;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.RabbitFluxException;
import reactor.rabbitmq.RabbitFluxRetryTimeoutException;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

import static org.reactivecommons.async.api.DirectAsyncGateway.DELAYED;
import static org.reactivecommons.async.commons.Headers.SOURCE_APPLICATION;

@Log
public class ReactiveMessageSender implements AutoCloseable {
    static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final Duration RESUBSCRIBE_MIN_BACKOFF = Duration.ofMillis(200);
    private static final Duration RESUBSCRIBE_MAX_BACKOFF = Duration.ofSeconds(5);
    private static final Duration AVAILABILITY_POLL_INTERVAL = Duration.ofMillis(50);
    private static final int NUMBER_OF_SENDER_SUBSCRIPTIONS = 4;

    @Getter
    private final Sender sender;
    private final String sourceApplication;
    private final MessageConverter messageConverter;
    @Getter
    private final TopologyCreator topologyCreator;
    private final boolean isMandatory;
    private final UnroutableMessageNotifier unroutableMessageNotifier;
    @Getter
    private final Duration sendTimeout;

    private final AtomicReferenceArray<FluxSink<OutboundMessage>> confirmSinks =
            new AtomicReferenceArray<>(NUMBER_OF_SENDER_SUBSCRIPTIONS);
    private final AtomicReference<FluxSink<OutboundMessage>> noConfirmSink = new AtomicReference<>();
    private final AtomicInteger nextConfirmSink = new AtomicInteger();
    private final AtomicLong counter = new AtomicLong();

    private final AtomicInteger activeConfirmFlows = new AtomicInteger();
    private final AtomicInteger activeNoConfirmFlows = new AtomicInteger();
    private final AtomicReference<Instant> confirmFlowsDownSince = new AtomicReference<>();
    private final AtomicLong consecutiveConfirmFailures = new AtomicLong();
    private final AtomicReference<Instant> lastConfirmFailure = new AtomicReference<>();
    private final AtomicReference<String> lastConfirmFailureMessage = new AtomicReference<>();
    private final ConcurrentHashMap<String, AtomicBoolean> firstResubscribeWarning = new ConcurrentHashMap<>();

    private final List<Disposable> flowSubscriptions = new ArrayList<>(NUMBER_OF_SENDER_SUBSCRIPTIONS + 1);
    private final Set<PendingMessage> pendingMessages = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Completes the callers' Monos, so their continuations never run on the broker connection threads.
     */
    private final ExecutorService executorService = Executors.newFixedThreadPool(
            13, r -> new Thread(r, "RMessageSender1-" + counter.getAndIncrement())
    );
    /**
     * Publishes the messages (a publish can block, e.g. on a broker resource alarm).
     */
    private final ExecutorService executorService2 = Executors.newFixedThreadPool(
            13, r -> new Thread(r, "RMessageSender2-" + counter.getAndIncrement())
    );

    public ReactiveMessageSender(Sender sender, String sourceApplication,
                                 MessageConverter messageConverter, TopologyCreator topologyCreator,
                                 boolean isMandatory, UnroutableMessageNotifier unroutableMessageNotifier) {
        this(sender, sourceApplication, messageConverter, topologyCreator, isMandatory, unroutableMessageNotifier,
                DEFAULT_SEND_TIMEOUT);
    }

    public ReactiveMessageSender(Sender sender, String sourceApplication,
                                 MessageConverter messageConverter, TopologyCreator topologyCreator,
                                 boolean isMandatory, UnroutableMessageNotifier unroutableMessageNotifier,
                                 Duration sendTimeout) {
        this.sender = sender;
        this.sourceApplication = sourceApplication;
        this.messageConverter = messageConverter;
        this.topologyCreator = topologyCreator;
        this.isMandatory = isMandatory;
        this.unroutableMessageNotifier = unroutableMessageNotifier;
        this.sendTimeout = sendTimeout;
        initializeSenders();
    }

    private void initializeSenders() {
        for (int i = 0; i < NUMBER_OF_SENDER_SUBSCRIPTIONS; ++i) {
            final int index = i;
            final String flowName = "publisher-confirms flow #" + index;
            flowSubscriptions.add(Flux.defer(() -> sender.sendWithTypedPublishConfirms(
                            Flux.create(sink -> register(sink,
                                    () -> onConfirmFlowRegistered(index, sink),
                                    () -> onConfirmFlowDisposed(index, sink))),
                            confirmSendOptions()))
                    .doOnNext(this::onConfirmResult)
                    .doOnDiscard(PendingMessage.class, PendingMessage::retryOnAnotherFlow)
                    .retryWhen(resubscribe(flowName, true))
                    .subscribe(null, error -> log.log(Level.SEVERE, error, () -> "RabbitMQ " + flowName + " stopped")));
        }

        flowSubscriptions.add(Flux.defer(() -> sender.send(
                        Flux.create(sink -> register(sink,
                                () -> onNoConfirmFlowRegistered(sink),
                                () -> onNoConfirmFlowDisposed(sink))),
                        noConfirmSendOptions()))
                .doOnDiscard(PendingMessage.class, PendingMessage::retryOnAnotherFlow)
                .retryWhen(resubscribe("no-confirm flow", false))
                .subscribe(null, error -> log.log(Level.SEVERE, error, () -> "RabbitMQ no-confirm flow stopped")));
    }

    /**
     * Stops the publishing flows, fails the messages still waiting for an outcome and releases the sender threads.
     * <p>
     * A message whose hand-over races with the close may still be published after its caller was failed: delivery is
     * at-least-once, so re-sending after a failure can produce a duplicate.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pendingMessages.forEach(message -> message.fail(shutdownFailure(null)));
        pendingMessages.clear();
        // cancel the flows (and their re-subscription) before the executors: nothing must be re-subscribed
        flowSubscriptions.forEach(Disposable::dispose);
        flowSubscriptions.clear();
        shutdownAndAwait(executorService2);
        shutdownAndAwait(executorService);
        sender.close();
    }

    private static SendFailureNoAckException shutdownFailure(Throwable cause) {
        return new SendFailureNoAckException("Message not published: sender is shut down", cause);
    }

    private static void shutdownAndAwait(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void onConfirmFlowRegistered(int index, FluxSink<OutboundMessage> sink) {
        confirmSinks.set(index, sink);
        activeConfirmFlows.incrementAndGet();
        confirmFlowsDownSince.set(null);
    }

    private void onConfirmFlowDisposed(int index, FluxSink<OutboundMessage> sink) {
        confirmSinks.compareAndSet(index, sink, null);
        if (activeConfirmFlows.decrementAndGet() == 0) {
            confirmFlowsDownSince.compareAndSet(null, Instant.now());
        }
    }

    private void onNoConfirmFlowRegistered(FluxSink<OutboundMessage> sink) {
        noConfirmSink.set(sink);
        activeNoConfirmFlows.incrementAndGet();
    }

    private void onNoConfirmFlowDisposed(FluxSink<OutboundMessage> sink) {
        noConfirmSink.compareAndSet(sink, null);
        activeNoConfirmFlows.decrementAndGet();
    }

    /**
     * Makes the sink of a flow available for sending once the flow is subscribed, i.e. once it got a channel, and
     * removes it as soon as the flow terminates.
     */
    private static void register(FluxSink<OutboundMessage> sink, Runnable add, Runnable remove) {
        sink.onDispose(remove::run);
        add.run();
    }

    /**
     * Re-subscribes a terminated flow with backoff. The first failure of each flow is logged at WARNING so an outage
     * is visible; later attempts are logged at FINE, otherwise a sustained outage would flood the log.
     */
    private Retry resubscribe(String flowName, boolean confirmFlow) {
        return Retry.backoff(Long.MAX_VALUE, RESUBSCRIBE_MIN_BACKOFF)
                .maxBackoff(RESUBSCRIBE_MAX_BACKOFF)
                .transientErrors(true)
                .doBeforeRetry(signal -> {
                    if (confirmFlow) {
                        consecutiveConfirmFailures.incrementAndGet();
                        lastConfirmFailure.set(Instant.now());
                        lastConfirmFailureMessage.set(String.valueOf(signal.failure()));
                    }
                    log.log(resubscribeLogLevel(flowName), () -> "RabbitMQ " + flowName
                            + " terminated, re-subscribing with a new channel (attempt "
                            + (signal.totalRetriesInARow() + 1) + "): " + signal.failure());
                });
    }

    private Level resubscribeLogLevel(String flowName) {
        return firstResubscribeWarning.computeIfAbsent(flowName, key -> new AtomicBoolean())
                .compareAndSet(false, true) ? Level.WARNING : Level.FINE;
    }

    /**
     * In-memory liveness of the publishing flows, for health checks. A broken publishing path (e.g. flows endlessly
     * re-subscribing while the connection stays open) can thus be told apart from a healthy one without network I/O.
     */
    public PublisherStatus publisherStatus() {
        return new PublisherStatus(
                activeConfirmFlows.get() > 0,
                activeNoConfirmFlows.get() > 0,
                consecutiveConfirmFailures.get(),
                lastConfirmFailure.get(),
                lastConfirmFailureMessage.get(),
                confirmFlowsDownSince.get());
    }

    private SendOptions confirmSendOptions() {
        return new SendOptions()
                .trackReturned(isMandatory)
                .exceptionHandler(ReactiveMessageSender::onConfirmPublishFailure);
    }

    private static SendOptions noConfirmSendOptions() {
        return new SendOptions().exceptionHandler(ReactiveMessageSender::onNoConfirmPublishFailure);
    }

    /**
     * Fails fast instead of the default retry loop that sleeps (up to 10 seconds) on the publishing thread.
     * <ul>
     *     <li>Connection being recovered: the message is reported as not published (nack) and the flow keeps its
     *     channel, which the client re-opens.</li>
     *     <li>Any other error (e.g. closed channel): the message is reported as not published and the flow terminates,
     *     so it is re-subscribed with a new channel.</li>
     * </ul>
     */
    @SuppressWarnings("rawtypes")
    private static void onConfirmPublishFailure(Sender.SendContext context, Exception error) {
        if (isConnectionRecovering(context, error)) {
            throw new RabbitFluxRetryTimeoutException("Connection is being recovered, message not published", error);
        }
        throw new RabbitFluxException("Publish failed, the flow will be re-subscribed", error);
    }

    @SuppressWarnings("rawtypes")
    private static void onNoConfirmPublishFailure(Sender.SendContext context, Exception error) {
        if (context.getMessage() instanceof PendingMessage message) {
            message.fail(new SendFailureNoAckException("Message not published: " + error.getMessage(), error));
        }
        if (!isConnectionRecovering(context, error)) {
            throw new RabbitFluxException("Publish failed, the flow will be re-subscribed", error);
        }
    }

    @SuppressWarnings("rawtypes")
    private static boolean isConnectionRecovering(Sender.SendContext context, Exception error) {
        return context.getChannel() instanceof Recoverable
                && error instanceof ShutdownSignalException signal
                && signal.isHardError()
                && ExceptionHandlers.CONNECTION_RECOVERY_PREDICATE.test(signal);
    }

    private void onConfirmResult(OutboundMessageResult<OutboundMessage> result) {
        try {
            if (result.ack()) {
                // a successful confirmation proves the publishing path works
                consecutiveConfirmFailures.set(0);
            }
            if (result.returned() && unroutableMessageNotifier != null) {
                unroutableMessageNotifier.notifyUnroutableMessage(result);
            }
            if (result.outboundMessage() instanceof ConfirmableMessage message) {
                executorService.execute(() -> message.complete(result));
            }
        } catch (Exception e) {
            // never let an error here terminate the publishing flow
            log.log(Level.WARNING, e, () -> "Error while notifying the result of a published message");
        }
    }

    public <T> Mono<Void> sendWithConfirm(T message, String exchange, String routingKey,
                                          Map<String, Object> headers, boolean persistent) {
        return Mono.defer(() -> {
            final Message converted = messageConverter.toMessage(message);
            final AMQP.BasicProperties props = buildMessageProperties(converted, headers, persistent);
            final byte[] body = converted.getBody();
            return send(monoSink -> new ConfirmableMessage(exchange, routingKey, props, body, monoSink),
                    this::nextConfirmSink);
        });
    }

    /**
     * Publishes without publisher confirms. The returned Mono is lazy: the message is published when it is
     * subscribed. It completes once the message is written to the channel, and fails if it could not be.
     */
    public <T> Mono<Void> sendNoConfirm(T message, String exchange, String routingKey,
                                        Map<String, Object> headers, boolean persistent) {
        return Mono.defer(() -> {
            final Message converted = messageConverter.toMessage(message);
            final AMQP.BasicProperties props = buildMessageProperties(converted, headers, persistent);
            final byte[] body = converted.getBody();
            return send(monoSink -> new NoConfirmMessage(exchange, routingKey, props, body, monoSink,
                    executorService), this::availableNoConfirmSink);
        });
    }

    private Mono<Void> send(Function<MonoSink<Void>, PendingMessage> messageFactory,
                            Supplier<FluxSink<OutboundMessage>> sinkSelector) {
        final AtomicBoolean handedToAFlow = new AtomicBoolean();
        return Mono.<Void>create(monoSink -> {
                    final PendingMessage pending = messageFactory.apply(monoSink);
                    if (closed.get()) {
                        pending.fail(shutdownFailure(null));
                        return;
                    }
                    pendingMessages.add(pending);
                    pending.setOnFinished(() -> pendingMessages.remove(pending));
                    if (closed.get()) {
                        // close() may have collected the pending messages before this one was registered
                        pending.fail(shutdownFailure(null));
                        return;
                    }
                    try {
                        executorService2.execute(() -> handOver(pending, sinkSelector, handedToAFlow));
                    } catch (RejectedExecutionException e) {
                        pending.fail(shutdownFailure(e));
                    }
                })
                // no flow ready yet (start-up, re-subscription): wait for one
                .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, AVAILABILITY_POLL_INTERVAL)
                        .filter(NoFlowAvailableException.class::isInstance))
                .timeout(sendTimeout)
                .onErrorMap(TimeoutException.class, e -> handedToAFlow.get()
                        ? new SendConfirmUnknownException("No confirmation received within " + sendTimeout
                        + ": the message may or may not have reached the broker", e)
                        : new SendFailureNoAckException("Message not published: no RabbitMQ publishing flow "
                        + "available within " + sendTimeout, e));
    }

    private static void handOver(PendingMessage pending, Supplier<FluxSink<OutboundMessage>> sinkSelector,
                                 AtomicBoolean handedToAFlow) {
        if (pending.isFinished()) {
            return; // cancelled (e.g. timeout) before being published
        }
        final FluxSink<OutboundMessage> sink = sinkSelector.get();
        if (sink == null) {
            pending.retryOnAnotherFlow();
            return;
        }
        handedToAFlow.set(true);
        try {
            sink.next(pending);
        } catch (Exception e) {
            pending.fail(new SendFailureNoAckException("Message not published: " + e.getMessage(), e));
        }
    }

    private FluxSink<OutboundMessage> nextConfirmSink() {
        final int start = Math.floorMod(nextConfirmSink.getAndIncrement(), NUMBER_OF_SENDER_SUBSCRIPTIONS);
        for (int i = 0; i < NUMBER_OF_SENDER_SUBSCRIPTIONS; i++) {
            final FluxSink<OutboundMessage> sink = confirmSinks.get((start + i) % NUMBER_OF_SENDER_SUBSCRIPTIONS);
            if (sink != null && !sink.isCancelled()) {
                return sink;
            }
        }
        return null;
    }

    private FluxSink<OutboundMessage> availableNoConfirmSink() {
        final FluxSink<OutboundMessage> sink = noConfirmSink.get();
        return sink != null && !sink.isCancelled() ? sink : null;
    }

    public <T> Flux<OutboundMessageResult> sendWithConfirmBatch(Flux<T> messages, String exchange, String routingKey,
                                                                Map<String, Object> headers, boolean persistent) {
        return Flux.defer(() -> {
            if (closed.get()) {
                return Flux.<OutboundMessageResult>error(shutdownFailure(null));
            }
            return messages.map(message -> toOutboundMessage(message, exchange, routingKey, headers, persistent))
                    .as(outbound -> sender.sendWithPublishConfirms(outbound, new SendOptions()
                            .trackReturned(isMandatory)
                            .exceptionHandler(ReactiveMessageSender::onConfirmPublishFailure)))
                    .<OutboundMessageResult>flatMap(result -> {
                        // a returned message is also acked: it must be checked first
                        if (result.returned()) {
                            return Mono.error(new SendFailureReturnedException("Message returned by the broker: no "
                                    + "queue is bound to the routing key"));
                        }
                        if (result.ack()) {
                            return Mono.empty();
                        }
                        return Mono.error(result.unknown()
                                ? new SendConfirmUnknownException("Event in doubt: the channel or the connection "
                                + "failed before its confirmation")
                                : new SendFailureNoAckException("Event no ACK in communications"));
                    });
        })
                .timeout(sendTimeout)
                .onErrorMap(TimeoutException.class, e -> new SendConfirmUnknownException(
                        "No confirmation received within " + sendTimeout + ": some messages of the batch may or "
                        + "may not have reached the broker", e));
    }

    private <T> OutboundMessage toOutboundMessage(T object, String exchange, String routingKey,
                                                  Map<String, Object> headers, boolean persistent) {
        final Message message = messageConverter.toMessage(object);
        final AMQP.BasicProperties props = buildMessageProperties(message, headers, persistent);
        return new OutboundMessage(exchange, routingKey, props, message.getBody());
    }

    private AMQP.BasicProperties buildMessageProperties(Message message, Map<String, Object> headers,
                                                        boolean persistent) {
        final Message.Properties properties = message.getProperties();
        final Map<String, Object> baseHeaders = new HashMap<>(properties.getHeaders());
        baseHeaders.putAll(headers);
        baseHeaders.put(SOURCE_APPLICATION, sourceApplication);
        AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties.Builder()
                .contentType(properties.getContentType())
                .appId(sourceApplication)
                .contentEncoding(properties.getContentEncoding())
                .deliveryMode(persistent ? 2 : 1)
                .timestamp(Date.from(Instant.now()))
                .messageId(UUID.randomUUID().toString())
                .headers(baseHeaders);
        if (headers.containsKey(DELAYED)) {
            builder.expiration((String) headers.get(DELAYED));
        }
        return builder.build();
    }

    /**
     * A message whose caller is waiting for its outcome. It is completed at most once.
     */
    private abstract static class PendingMessage extends OutboundMessage {
        private final MonoSink<Void> monoSink;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Runnable onFinished = () -> { };

        PendingMessage(String exchange, String routingKey, AMQP.BasicProperties properties, byte[] body,
                       MonoSink<Void> monoSink) {
            super(exchange, routingKey, properties, body);
            this.monoSink = monoSink;
            monoSink.onCancel(this::discard);
        }

        /**
         * Registers a callback invoked exactly once when the message is finished (completed, failed or cancelled).
         */
        void setOnFinished(Runnable onFinished) {
            this.onFinished = onFinished;
            if (finished.get()) {
                onFinished.run(); // finished before the hook was set: still notify
            }
        }

        boolean isFinished() {
            return finished.get();
        }

        void succeed() {
            if (markFinished()) {
                monoSink.success();
            }
        }

        void fail(Throwable error) {
            if (markFinished()) {
                monoSink.error(error);
            }
        }

        private void discard() {
            markFinished();
        }

        private boolean markFinished() {
            if (finished.compareAndSet(false, true)) {
                onFinished.run();
                return true;
            }
            return false;
        }

        /**
         * The message was never written to a channel (no flow ready, or its flow terminated before publishing it):
         * it is handed to another flow, so it is not lost and it cannot be duplicated.
         */
        void retryOnAnotherFlow() {
            if (!isPublished()) {
                fail(NoFlowAvailableException.INSTANCE);
            }
        }
    }

    private static final class ConfirmableMessage extends PendingMessage {

        ConfirmableMessage(String exchange, String routingKey, AMQP.BasicProperties properties, byte[] body,
                           MonoSink<Void> monoSink) {
            super(exchange, routingKey, properties, body, monoSink);
        }

        void complete(OutboundMessageResult<OutboundMessage> result) {
            if (result.returned()) {
                fail(new SendFailureReturnedException("Message returned by the broker: no queue is bound to the "
                        + "routing key"));
            } else if (result.ack()) {
                succeed();
            } else if (result.unknown()) {
                fail(new SendConfirmUnknownException("The channel or the connection failed before the "
                        + "confirmation: the message may or may not have reached the broker"));
            } else if (!isPublished()) {
                fail(new SendFailureNoAckException("Message not published: the channel or the connection is "
                        + "not available"));
            } else {
                fail(new SendFailureNoAckException("No ACK when sending message"));
            }
        }
    }

    private static final class NoConfirmMessage extends PendingMessage {
        private final Executor completionExecutor;

        NoConfirmMessage(String exchange, String routingKey, AMQP.BasicProperties properties, byte[] body,
                         MonoSink<Void> monoSink, Executor completionExecutor) {
            super(exchange, routingKey, properties, body, monoSink);
            this.completionExecutor = completionExecutor;
        }

        @Override
        protected void published() {
            super.published();
            completionExecutor.execute(this::succeed);
        }
    }

    /**
     * Internal signal: no publishing flow can take the message right now. Never reaches the caller.
     */
    private static final class NoFlowAvailableException extends RuntimeException {
        private static final NoFlowAvailableException INSTANCE = new NoFlowAvailableException();

        private NoFlowAvailableException() {
            super("No RabbitMQ publishing flow available", null, false, false);
        }
    }
}
