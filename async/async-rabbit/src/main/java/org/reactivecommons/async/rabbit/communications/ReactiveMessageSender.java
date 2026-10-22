package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.ShutdownSignalException;
import io.cloudevents.CloudEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.BaseUnits;
import lombok.Getter;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureBrokerBlockedException;
import org.reactivecommons.async.commons.exceptions.SendFailureHandOverRejectedException;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.commons.exceptions.SendFailureReturnedException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.SignalType;
import reactor.rabbitmq.ChannelCloseHandlers;
import reactor.rabbitmq.ExceptionHandlers;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.RabbitFluxException;
import reactor.rabbitmq.RabbitFluxRetryTimeoutException;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

import static org.reactivecommons.async.api.DirectAsyncGateway.DELAYED;
import static org.reactivecommons.async.commons.Headers.SOURCE_APPLICATION;

@Log
public class ReactiveMessageSender implements AutoCloseable {
    static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_HAND_OVER_POOL_SIZE = 13;
    /**
     * About a second of a 15000 messages per second peak: a short pause (GC, scheduling, the broker flushing to disk)
     * must not reject messages, only a connection the broker stopped draining.
     */
    static final int DEFAULT_HAND_OVER_QUEUE_CAPACITY = 16_384;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final Duration RESUBSCRIBE_MIN_BACKOFF = Duration.ofMillis(200);
    private static final Duration RESUBSCRIBE_MAX_BACKOFF = Duration.ofSeconds(5);
    private static final Duration AVAILABILITY_POLL_INTERVAL = Duration.ofMillis(50);
    private static final int NUMBER_OF_SENDER_SUBSCRIPTIONS = 4;
    /**
     * How long closing the sender waits for the broker before forcing a blocked connection closed.
     */
    private static final int BLOCKED_CONNECTION_ABORT_TIMEOUT_MILLIS = 1_000;
    /**
     * The AMQP message-id is a short string.
     */
    private static final int MAX_MESSAGE_ID_BYTES = 255;

    private static final String METRIC_PUBLISH_OUTCOME = "reactive_commons.publish.outcome";
    private static final String METRIC_FLOW_RESUBSCRIBE = "reactive_commons.publish.flow.resubscribe";
    private static final String METRIC_CONNECTION_BLOCKED = "reactive_commons.publish.connection.blocked";
    private static final String METRIC_PUBLISH_LATENCY = "reactive_commons.publish.latency";
    private static final String METRIC_PUBLISH_MESSAGE_SIZE = "reactive_commons.publish.message.size";
    static final String OUTCOME_ACK = "ack";
    static final String OUTCOME_NACK = "nack";
    static final String OUTCOME_UNKNOWN = "unknown";
    static final String OUTCOME_RETURNED = "returned";
    static final String OUTCOME_REJECTED = "rejected";
    static final String OUTCOME_TIMEOUT = "timeout";
    static final String OUTCOME_BLOCKED = "blocked";
    /**
     * No-confirm outcomes: the message was written to the channel (not confirmed by the broker), or it could not be.
     */
    static final String OUTCOME_PUBLISHED = "published";
    static final String OUTCOME_FAILED = "failed";

    /**
     * Outcomes that may be reported through the confirm flow. Pre-registered at 0 so Prometheus has a prior sample
     * for every known series and {@code increase()} / {@code rate()} count the very first incident.
     */
    private static final List<String> CONFIRM_OUTCOMES = List.of(
            OUTCOME_ACK, OUTCOME_NACK, OUTCOME_UNKNOWN, OUTCOME_RETURNED,
            OUTCOME_REJECTED, OUTCOME_TIMEOUT, OUTCOME_BLOCKED, OUTCOME_FAILED);
    private static final List<String> NO_CONFIRM_OUTCOMES = List.of(
            OUTCOME_PUBLISHED, OUTCOME_FAILED, OUTCOME_REJECTED, OUTCOME_TIMEOUT, OUTCOME_BLOCKED);

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
    private final ConnectionBlockedState blockedState;
    private final PublishMetrics metrics;

    private final AtomicReferenceArray<FluxSink<OutboundMessage>> confirmSinks =
            new AtomicReferenceArray<>(NUMBER_OF_SENDER_SUBSCRIPTIONS);
    private final AtomicReference<FluxSink<OutboundMessage>> noConfirmSink = new AtomicReference<>();
    private final AtomicInteger nextConfirmSink = new AtomicInteger();
    private final AtomicLong counter = new AtomicLong();

    private final AtomicInteger activeConfirmFlows = new AtomicInteger();
    private final AtomicInteger activeNoConfirmFlows = new AtomicInteger();
    /**
     * Messages handed to a flow and not yet written to its channel. They wait in the (unbounded) buffer of the flow
     * while its channel is busy, e.g. stalled in {@code basicPublish} because the broker blocks the connection.
     */
    private final AtomicInteger bufferedMessages = new AtomicInteger();
    private final int maxBufferedMessages;
    /**
     * The flows release their channels concurrently when the sender is closed: only the first one aborts a blocked
     * connection, the others' channels die with it.
     */
    private final AtomicBoolean blockedConnectionAborted = new AtomicBoolean();
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
    private final ExecutorService executorService;
    /**
     * Publishes the messages (a publish can block, e.g. on a broker resource alarm). Bounded on purpose: a sustained
     * block fills this queue and surfaces as {@link SendFailureHandOverRejectedException} to the caller instead of
     * silently growing in memory.
     */
    private final ThreadPoolExecutor executorService2;

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
        this(sender, sourceApplication, messageConverter, topologyCreator, isMandatory, unroutableMessageNotifier,
                sendTimeout, DEFAULT_HAND_OVER_POOL_SIZE, DEFAULT_HAND_OVER_QUEUE_CAPACITY,
                new ConnectionBlockedState(), null, null);
    }

    public ReactiveMessageSender(Sender sender, String sourceApplication,
                                 MessageConverter messageConverter, TopologyCreator topologyCreator,
                                 boolean isMandatory, UnroutableMessageNotifier unroutableMessageNotifier,
                                 Duration sendTimeout, int handOverPoolSize, int handOverQueueCapacity,
                                 ConnectionBlockedState blockedState,
                                 MeterRegistry meterRegistry, String domain) {
        this.sender = sender;
        this.sourceApplication = sourceApplication;
        this.messageConverter = messageConverter;
        this.topologyCreator = topologyCreator;
        this.isMandatory = isMandatory;
        this.unroutableMessageNotifier = unroutableMessageNotifier;
        this.sendTimeout = sendTimeout;
        this.blockedState = blockedState != null ? blockedState : new ConnectionBlockedState();
        this.maxBufferedMessages = Math.max(1, handOverQueueCapacity);
        this.executorService = Executors.newFixedThreadPool(
                handOverPoolSize,
                r -> new Thread(r, "RMessageSender1-" + counter.getAndIncrement())
        );
        this.executorService2 = new ThreadPoolExecutor(
                handOverPoolSize, handOverPoolSize,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, handOverQueueCapacity)),
                r -> new Thread(r, "RMessageSender2-" + counter.getAndIncrement()),
                new ThreadPoolExecutor.AbortPolicy());
        this.metrics = new PublishMetrics(meterRegistry, domain, this.blockedState,
                activeConfirmFlows, activeNoConfirmFlows, this.executorService2);
        initializeSenders();
    }

    private void initializeSenders() {
        for (int i = 0; i < NUMBER_OF_SENDER_SUBSCRIPTIONS; ++i) {
            final int index = i;
            final String flowName = "publisher-confirms flow #" + index;
            flowSubscriptions.add(Flux.defer(() -> sender.sendWithTypedPublishConfirms(
                            Flux.<OutboundMessage>create(sink -> register(sink,
                                    () -> onConfirmFlowRegistered(index, sink),
                                    () -> onConfirmFlowDisposed(index, sink)))
                                    .filter(ReactiveMessageSender::claimForWrite),
                            confirmSendOptions()))
                    .doOnNext(this::onConfirmResult)
                    .doOnDiscard(PendingMessage.class, PendingMessage::retryOnAnotherFlow)
                    .retryWhen(resubscribe(flowName, true))
                    .subscribe(null, error -> log.log(Level.SEVERE, error, () -> "RabbitMQ " + flowName + " stopped")));
        }

        flowSubscriptions.add(Flux.defer(() -> sender.send(
                        Flux.<OutboundMessage>create(sink -> register(sink,
                                () -> onNoConfirmFlowRegistered(sink),
                                () -> onNoConfirmFlowDisposed(sink)))
                                .filter(ReactiveMessageSender::claimForWrite),
                        noConfirmSendOptions()))
                .doOnDiscard(PendingMessage.class, PendingMessage::retryOnAnotherFlow)
                .retryWhen(resubscribe("no-confirm flow", false))
                .subscribe(null, error -> log.log(Level.SEVERE, error, () -> "RabbitMQ no-confirm flow stopped")));
    }

    /**
     * A message leaves the buffer of its flow to be written: it is written only if its caller is still waiting for
     * it. One that timed out (or was cancelled) while buffered is dropped, so it is never published late.
     */
    private static boolean claimForWrite(OutboundMessage message) {
        return !(message instanceof PendingMessage pending) || pending.claimForWrite();
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
                    metrics.recordResubscribe(confirmFlow);
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
                confirmFlowsDownSince.get(),
                blockedState.isBlocked(),
                blockedState.blockedSince(),
                blockedState.blockedReason());
    }

    public ConnectionBlockedState blockedState() {
        return blockedState;
    }

    private SendOptions confirmSendOptions() {
        return new SendOptions()
                .trackReturned(isMandatory)
                .exceptionHandler(ReactiveMessageSender::onConfirmPublishFailure)
                .channelCloseHandler(this::releaseChannel);
    }

    private SendOptions noConfirmSendOptions() {
        return new SendOptions()
                .exceptionHandler(this::onNoConfirmPublishFailure)
                .channelCloseHandler(this::releaseChannel);
    }

    /**
     * Releases the channel of a terminated flow. While the broker blocks the connection it does not read from it,
     * so a graceful {@code channel.close} would wait for the channel RPC timeout (10 minutes by default): when the
     * sender is being closed, the blocked connection is aborted instead, which closes it within a bounded time.
     */
    private void releaseChannel(SignalType signal, Channel channel) {
        if (closed.get() && blockedState.isBlocked()) {
            if (blockedConnectionAborted.compareAndSet(false, true)) {
                abortBlockedConnection(channel.getConnection());
            }
            return;
        }
        final BiConsumer<SignalType, Channel> defaultHandler = sender.getChannelCloseHandler();
        (defaultHandler != null ? defaultHandler : ChannelCloseHandlers.SENDER_CHANNEL_CLOSE_HANDLER_INSTANCE)
                .accept(signal, channel);
    }

    private static void abortBlockedConnection(Connection connection) {
        if (connection.isOpen()) {
            log.warning(() -> "Closing the sender while the broker blocks its connection: aborting the connection");
            connection.abort(BLOCKED_CONNECTION_ABORT_TIMEOUT_MILLIS);
        }
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
    private void onNoConfirmPublishFailure(Sender.SendContext context, Exception error) {
        if (context.getMessage() instanceof PendingMessage message) {
            message.fail(new SendFailureNoAckException("Message not published: " + error.getMessage(), error));
            metrics.recordOutcome(OUTCOME_FAILED, false);
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
            metrics.recordOutcome(outcomeOf(result), true);
            if (result.outboundMessage() instanceof ConfirmableMessage message) {
                executorService.execute(() -> message.complete(result));
            }
        } catch (Exception e) {
            // never let an error here terminate the publishing flow
            log.log(Level.WARNING, e, () -> "Error while notifying the result of a published message");
        }
    }

    private static String outcomeOf(OutboundMessageResult<OutboundMessage> result) {
        if (result.returned()) {
            return OUTCOME_RETURNED;
        }
        if (result.ack()) {
            return OUTCOME_ACK;
        }
        if (result.unknown()) {
            return OUTCOME_UNKNOWN;
        }
        // a negative result for a message never written to the channel is not a broker nack
        final OutboundMessage message = result.outboundMessage();
        return message != null && !message.isPublished() ? OUTCOME_FAILED : OUTCOME_NACK;
    }

    public <T> Mono<Void> sendWithConfirm(T message, String exchange, String routingKey,
                                          Map<String, Object> headers, boolean persistent) {
        return Mono.defer(() -> {
            final Message converted = messageConverter.toMessage(message);
            final AMQP.BasicProperties props = buildMessageProperties(message, converted, headers, persistent);
            final byte[] body = converted.getBody();
            metrics.recordMessageSize(true, body.length);
            return send(monoSink -> new ConfirmableMessage(exchange, routingKey, props, body, monoSink),
                    this::nextConfirmSink, true);
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
            final AMQP.BasicProperties props = buildMessageProperties(message, converted, headers, persistent);
            final byte[] body = converted.getBody();
            metrics.recordMessageSize(false, body.length);
            final Runnable onPublished = () -> metrics.recordOutcome(OUTCOME_PUBLISHED, false);
            return send(monoSink -> new NoConfirmMessage(exchange, routingKey, props, body, monoSink,
                    executorService, onPublished), this::availableNoConfirmSink, false);
        });
    }

    private Mono<Void> send(Function<MonoSink<Void>, PendingMessage> messageFactory,
                            Supplier<FluxSink<OutboundMessage>> sinkSelector, boolean confirmFlow) {
        final AtomicBoolean handedToAFlow = new AtomicBoolean();
        final AtomicReference<PendingMessage> current = new AtomicReference<>();
        final AtomicBoolean timedOut = new AtomicBoolean();
        final long startNanos = System.nanoTime();
        return Mono.<Void>create(monoSink -> {
                    final PendingMessage pending = messageFactory.apply(monoSink);
                    current.set(pending);
                    if (closed.get()) {
                        pending.fail(shutdownFailure(null));
                        return;
                    }
                    // the broker stopped reading the connection: waiting for sendTimeout could only end in an
                    // ambiguous outcome, while failing now guarantees the message was not published
                    if (blockedState.isBlocked()) {
                        pending.fail(new SendFailureBrokerBlockedException("Message not published: broker signalled"
                                + " connection.blocked (" + blockedState.blockedReason() + ")"));
                        metrics.recordOutcome(OUTCOME_BLOCKED, confirmFlow);
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
                        executorService2.execute(() -> handOver(pending, sinkSelector, handedToAFlow, confirmFlow));
                    } catch (RejectedExecutionException e) {
                        if (closed.get() || executorService2.isShutdown()) {
                            pending.fail(shutdownFailure(e));
                        } else {
                            pending.fail(new SendFailureHandOverRejectedException("Message not published: publish"
                                    + " hand-over queue is full (capacity reached while the broker is not draining)",
                                    e));
                        }
                        metrics.recordOutcome(OUTCOME_REJECTED, confirmFlow);
                    }
                })
                // no flow ready yet (start-up, re-subscription): wait for one
                .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, AVAILABILITY_POLL_INTERVAL)
                        .filter(NoFlowAvailableException.class::isInstance))
                .timeout(sendTimeout)
                .onErrorMap(TimeoutException.class, e -> {
                    timedOut.set(true);
                    metrics.recordOutcome(OUTCOME_TIMEOUT, confirmFlow);
                    final PendingMessage pending = current.get();
                    if (!handedToAFlow.get()) {
                        return new SendFailureNoAckException("Message not published: no RabbitMQ publishing flow "
                                + "available within " + sendTimeout, e);
                    }
                    if (pending != null && pending.wasDropped()) {
                        return new SendFailureNoAckException("Message not published within " + sendTimeout
                                + ": its publishing flow was busy (e.g. the broker is blocking the connection)", e);
                    }
                    return new SendConfirmUnknownException("No confirmation received within " + sendTimeout
                            + ": the message may or may not have reached the broker", e);
                })
                // recorded before the terminal signal reaches the caller; a cancelled subscription records nothing:
                // the caller stopped waiting, but the message may still be published
                .doOnSuccess(ignored -> metrics.recordPublishLatency(confirmFlow,
                        confirmFlow ? OUTCOME_ACK : OUTCOME_PUBLISHED,
                        Duration.ofNanos(System.nanoTime() - startNanos)))
                .doOnError(error -> metrics.recordPublishLatency(confirmFlow,
                        latencyOutcome(error, timedOut.get(), current.get()),
                        Duration.ofNanos(System.nanoTime() - startNanos)));
    }

    private static String latencyOutcome(Throwable error, boolean timedOut, PendingMessage pending) {
        if (timedOut) {
            return OUTCOME_TIMEOUT;
        }
        if (error instanceof SendFailureReturnedException) {
            return OUTCOME_RETURNED;
        }
        if (error instanceof SendFailureBrokerBlockedException) {
            return OUTCOME_BLOCKED;
        }
        if (error instanceof SendFailureHandOverRejectedException) {
            return OUTCOME_REJECTED;
        }
        if (error instanceof SendConfirmUnknownException) {
            return OUTCOME_UNKNOWN;
        }
        // the same exception reports a broker nack and a message that was never written (e.g. no channel, shut down)
        if (error instanceof SendFailureNoAckException && pending != null && pending.isPublished()) {
            return OUTCOME_NACK;
        }
        return OUTCOME_FAILED;
    }

    private void handOver(PendingMessage pending, Supplier<FluxSink<OutboundMessage>> sinkSelector,
                          AtomicBoolean handedToAFlow, boolean confirmFlow) {
        if (pending.isFinished()) {
            return; // cancelled (e.g. timeout) before being published
        }
        final FluxSink<OutboundMessage> sink = sinkSelector.get();
        if (sink == null) {
            pending.retryOnAnotherFlow();
            return;
        }
        if (!pending.enterBuffer(bufferedMessages, maxBufferedMessages)) {
            pending.fail(new SendFailureHandOverRejectedException("Message not published: " + maxBufferedMessages
                    + " messages are already waiting to be written (the broker is not draining the connection)"));
            metrics.recordOutcome(OUTCOME_REJECTED, confirmFlow);
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
                return Flux.error(shutdownFailure(null));
            }
            if (blockedState.isBlocked()) {
                metrics.recordOutcome(OUTCOME_BLOCKED, true);
                return Flux.error(new SendFailureBrokerBlockedException(
                        "Batch not published: broker signalled connection.blocked ("
                                + blockedState.blockedReason() + ")"));
            }
            return messages.map(message -> toOutboundMessage(message, exchange, routingKey, headers, persistent))
                    .as(outbound -> sender.sendWithPublishConfirms(outbound, new SendOptions()
                            .trackReturned(isMandatory)
                            .exceptionHandler(ReactiveMessageSender::onConfirmPublishFailure)))
                    .<OutboundMessageResult>flatMap(result -> {
                        metrics.recordOutcome(outcomeOf(result), true);
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
                .onErrorMap(TimeoutException.class, e -> {
                    metrics.recordOutcome(OUTCOME_TIMEOUT, true);
                    return new SendConfirmUnknownException(
                            "No confirmation received within " + sendTimeout + ": some messages of the batch may or "
                                    + "may not have reached the broker", e);
                });
    }

    private <T> OutboundMessage toOutboundMessage(T object, String exchange, String routingKey,
                                                  Map<String, Object> headers, boolean persistent) {
        final Message message = messageConverter.toMessage(object);
        final AMQP.BasicProperties props = buildMessageProperties(object, message, headers, persistent);
        return new OutboundMessage(exchange, routingKey, props, message.getBody());
    }

    private AMQP.BasicProperties buildMessageProperties(Object source, Message message, Map<String, Object> headers,
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
                .messageId(messageIdOf(source))
                .headers(baseHeaders);
        if (headers.containsKey(DELAYED)) {
            builder.expiration((String) headers.get(DELAYED));
        }
        return builder.build();
    }

    /**
     * The AMQP message-id is the business id of the message (the id of an event, a command or a CloudEvent), so a
     * copy re-sent after an unknown outcome keeps the id of the original and consumers can deduplicate it. A message
     * without one (e.g. a query or a reply), or with one that does not fit an AMQP short string, gets a unique id.
     */
    static String messageIdOf(Object message) {
        String id = null;
        if (message instanceof DomainEvent<?> event) {
            id = event.getEventId();
        } else if (message instanceof Command<?> command) {
            id = command.getCommandId();
        } else if (message instanceof CloudEvent cloudEvent) {
            id = cloudEvent.getId();
        }
        if (id == null || id.isBlank() || id.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_ID_BYTES) {
            return UUID.randomUUID().toString();
        }
        return id;
    }

    /**
     * A message whose caller is waiting for its outcome. It is completed at most once.
     */
    private abstract static class PendingMessage extends OutboundMessage {
        private static final int NEW = 0;
        private static final int BUFFERED = 1;
        private static final int CLAIMED = 2;
        private static final int DROPPED = 3;

        private final MonoSink<Void> monoSink;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Runnable onFinished = () -> { };
        /**
         * NEW until handed to a flow, BUFFERED in the flow until it is either CLAIMED to be written or DROPPED
         * because its caller stopped waiting first. The transitions are atomic, so a message is never both.
         */
        private final AtomicInteger bufferState = new AtomicInteger(NEW);
        private volatile AtomicInteger bufferCounter;

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

        /**
         * Accounts for the message in the buffer of its flow, unless the buffer already holds {@code max} messages.
         */
        boolean enterBuffer(AtomicInteger counter, int max) {
            if (counter.incrementAndGet() > max) {
                counter.decrementAndGet();
                return false;
            }
            bufferCounter = counter;
            if (!bufferState.compareAndSet(NEW, BUFFERED)) {
                counter.decrementAndGet();
                return false;
            }
            if (isFinished()) {
                leaveBuffer(DROPPED); // finished while being handed over
            }
            return true;
        }

        /**
         * The flow is about to write the message: allowed only if its caller has not stopped waiting.
         */
        boolean claimForWrite() {
            if (bufferState.get() == NEW) {
                return !isFinished();
            }
            return leaveBuffer(CLAIMED);
        }

        boolean wasDropped() {
            return bufferState.get() == DROPPED;
        }

        private boolean leaveBuffer(int state) {
            if (bufferState.compareAndSet(BUFFERED, state)) {
                bufferCounter.decrementAndGet();
                return true;
            }
            return false;
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
                leaveBuffer(DROPPED); // no-op unless still waiting in the buffer of a flow
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
        private final Runnable onPublished;

        NoConfirmMessage(String exchange, String routingKey, AMQP.BasicProperties properties, byte[] body,
                         MonoSink<Void> monoSink, Executor completionExecutor, Runnable onPublished) {
            super(exchange, routingKey, properties, body, monoSink);
            this.completionExecutor = completionExecutor;
            this.onPublished = onPublished;
        }

        @Override
        protected void published() {
            super.published();
            completionExecutor.execute(() -> {
                onPublished.run();
                succeed();
            });
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

    /**
     * Micrometer counters/gauges on the publish path. All operations are no-ops when no {@link MeterRegistry} is
     * provided, so the sender can be instantiated in tests without one. The counters are registered once and cached:
     * looking a meter up in the registry on every message would cost far more than incrementing it.
     *
     * <p>Besides the outcome/flow/connection meters, it records {@code reactive_commons.publish.latency} (Timer,
     * tags {@code outcome} + {@code confirm}) and {@code reactive_commons.publish.message.size}
     * (DistributionSummary, tag {@code confirm}).
     */
    private static final class PublishMetrics {
        private final MeterRegistry registry;
        private final Tags baseTags;
        private final Map<String, Counter> confirmOutcomes = new ConcurrentHashMap<>();
        private final Map<String, Counter> noConfirmOutcomes = new ConcurrentHashMap<>();
        private final Map<Boolean, Counter> resubscribes = new ConcurrentHashMap<>();
        private final Map<String, Timer> latencyTimers = new ConcurrentHashMap<>();
        private final Map<Boolean, DistributionSummary> messageSizes = new ConcurrentHashMap<>();

        PublishMetrics(MeterRegistry registry, String domain, ConnectionBlockedState blockedState,
                       AtomicInteger activeConfirmFlows, AtomicInteger activeNoConfirmFlows,
                       ThreadPoolExecutor handOverExecutor) {
            this.registry = registry;
            this.baseTags = domain != null ? Tags.of(Tag.of("domain", domain)) : Tags.empty();
            if (registry != null) {
                try {
                    registry.gauge("reactive_commons.publish.flow.live", baseTags.and("kind", "confirm"),
                            activeConfirmFlows);
                    registry.gauge("reactive_commons.publish.flow.live", baseTags.and("kind", "noConfirm"),
                            activeNoConfirmFlows);
                    registry.gauge(METRIC_CONNECTION_BLOCKED + ".state", baseTags, blockedState,
                            s -> s.isBlocked() ? 1.0 : 0.0);
                    registry.gauge("reactive_commons.publish.hand_over.queue.size", baseTags, handOverExecutor,
                            e -> e.getQueue().size());
                    FunctionCounter.builder(METRIC_CONNECTION_BLOCKED, blockedState,
                                    ConnectionBlockedState::blockCount)
                            .tags(baseTags)
                            .register(registry);
                    // register every known outcome at 0 so increase()/rate() can see the first incident
                    CONFIRM_OUTCOMES.forEach(outcome -> outcomeCounter(outcome, true));
                    NO_CONFIRM_OUTCOMES.forEach(outcome -> outcomeCounter(outcome, false));
                } catch (Exception e) {
                    // never let a mis-wired registry break the sender; metrics are best-effort
                    log.log(Level.WARNING, e, () -> "Could not register publish metrics");
                }
            }
        }

        void recordOutcome(String outcome, boolean confirmFlow) {
            if (registry == null) {
                return;
            }
            outcomeCounter(outcome, confirmFlow).increment();
        }

        private Counter outcomeCounter(String outcome, boolean confirmFlow) {
            return (confirmFlow ? confirmOutcomes : noConfirmOutcomes).computeIfAbsent(outcome, key ->
                    Counter.builder(METRIC_PUBLISH_OUTCOME)
                            .tags(baseTags)
                            .tag("outcome", key)
                            .tag("confirm", Boolean.toString(confirmFlow))
                            .register(registry));
        }

        void recordResubscribe(boolean confirmFlow) {
            if (registry == null) {
                return;
            }
            resubscribes.computeIfAbsent(confirmFlow, key ->
                    Counter.builder(METRIC_FLOW_RESUBSCRIBE)
                            .tags(baseTags)
                            .tag("kind", key ? "confirm" : "noConfirm")
                            .register(registry)).increment();
        }

        /**
         * Caller-visible latency of a publish: hand-over wait + flow buffer wait + broker confirm round-trip.
         */
        void recordPublishLatency(boolean confirmFlow, String outcome, Duration elapsed) {
            if (registry == null) {
                return;
            }
            // recorded inside the caller's Mono: a failure here must never turn a publish into an error
            try {
                latencyTimers.computeIfAbsent(confirmFlow + ":" + outcome, key ->
                        Timer.builder(METRIC_PUBLISH_LATENCY)
                                .tags(baseTags)
                                .tag("outcome", outcome)
                                .tag("confirm", Boolean.toString(confirmFlow))
                                .register(registry)).record(elapsed);
            } catch (Exception e) {
                log.log(Level.WARNING, e, () -> "Could not record " + METRIC_PUBLISH_LATENCY);
            }
        }

        void recordMessageSize(boolean confirmFlow, long bytes) {
            if (registry == null) {
                return;
            }
            try {
                messageSizes.computeIfAbsent(confirmFlow, key ->
                        DistributionSummary.builder(METRIC_PUBLISH_MESSAGE_SIZE)
                                .baseUnit(BaseUnits.BYTES)
                                .tags(baseTags)
                                .tag("confirm", key.toString())
                                .register(registry)).record(bytes);
            } catch (Exception e) {
                log.log(Level.WARNING, e, () -> "Could not record " + METRIC_PUBLISH_MESSAGE_SIZE);
            }
        }
    }
}
