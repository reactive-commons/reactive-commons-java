package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.Delivery;
import lombok.extern.java.Log;
import org.reactivecommons.async.commons.exceptions.ReplyLostOnReconnectException;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.commons.utils.LoggerSubscriber;
import org.reactivecommons.async.rabbit.InstanceIdentifier;
import org.reactivecommons.async.rabbit.RabbitMessage;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.reactivecommons.async.commons.Headers.COMPLETION_ONLY_SIGNAL;
import static org.reactivecommons.async.commons.Headers.CORRELATION_ID;
import static reactor.rabbitmq.BindingSpecification.binding;
import static reactor.rabbitmq.ExchangeSpecification.exchange;
import static reactor.rabbitmq.QueueSpecification.queue;


@Log
public class ApplicationReplyListener {

    static final Duration RESTART_MIN_BACKOFF = Duration.ofMillis(200);
    static final Duration RESTART_MAX_BACKOFF = Duration.ofSeconds(5);

    private final ReactiveReplyRouter router;
    private final Receiver receiver;
    private final TopologyCreator creator;
    private final String queueName;
    private final String exchangeName;

    private final boolean createTopology;
    private volatile Flux<Delivery> deliveryFlux;
    private final AtomicReference<Disposable> activeSubscription = new AtomicReference<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);
    private final AtomicBoolean firstRestartWarning = new AtomicBoolean();
    private final ListenerLiveness liveness;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final ConsumerMetrics metrics;
    private final SubscriberRegistry subscriberRegistry;
    private final String domain;

    public ApplicationReplyListener(ReactiveReplyRouter router, ReactiveMessageListener listener, String queueName,
                                    String exchangeName, boolean createTopology) {
        this.router = router;
        this.queueName = queueName;
        this.exchangeName = exchangeName;
        this.receiver = listener.receiver();
        this.creator = listener.topologyCreator();
        this.createTopology = createTopology;
        this.liveness = listener.subscriberRegistry() != null
                ? listener.subscriberRegistry().register(queueName)
                : new ListenerLiveness(queueName);
        this.meterRegistry = listener.meterRegistry();
        this.metrics = ConsumerMetrics.forReplyListener(meterRegistry, listener.domain(), queueName, liveness);
        this.subscriberRegistry = listener.subscriberRegistry();
        this.domain = listener.domain();
    }

    public void startListening(String routeKey) {
        Mono<Void> flow = Mono.empty();
        if (createTopology) {
            flow = creator.declare(exchange(exchangeName).type("topic").durable(true)).then();
        }
        final String tag = InstanceIdentifier.getInstanceId("replies");
        ConsumeOptions consumeOptions = new ConsumeOptions()
                .exceptionHandler(FailFastAcknowledgmentHandler.INSTANCE)
                .consumerRegisteredCallback(liveness::onSubscribed)
                .channelCallback(this::watchConnectionLoss);
        consumeOptions.consumerTag(tag);
        deliveryFlux = flow
                .then(creator.declare(queue(queueName).durable(false).autoDelete(true).exclusive(true)))
                .then(creator.bind(binding(exchangeName, routeKey, queueName)))
                .thenMany(receiver.consumeAutoAck(queueName, consumeOptions).doOnNext(delivery -> {
                    liveness.onDelivery();
                    try {
                        final String correlationID = delivery.getProperties().getHeaders().get(CORRELATION_ID).toString();
                        final boolean isEmpty = delivery.getProperties().getHeaders().get(COMPLETION_ONLY_SIGNAL) != null;
                        if (isEmpty) {
                            router.routeEmpty(correlationID);
                        } else {
                            router.routeReply(correlationID, RabbitMessage.fromDelivery(delivery));
                        }
                    } catch (Exception e) {
                        log.log(Level.SEVERE, "Error in reply reception", e);
                    }
                }));

        subscribe();
    }

    /**
     * Stops listening for replies. The current subscription is cancelled and the listener is not restarted.
     */
    public void stop() {
        stopped.set(true);
        final Disposable subscription = activeSubscription.getAndSet(null);
        if (subscription != null) {
            subscription.dispose();
        }
        if (subscriberRegistry != null) {
            subscriberRegistry.unregister(queueName);
        }
        metrics.close();
    }

    /**
     * The reply queue is exclusive, so the broker deletes it as soon as its connection is lost, and the replies
     * routed to it until the connection is recovered are dropped. The client recovers the connection without
     * terminating the consumer, so the termination of the subscription cannot detect it. The shutdown listeners of a
     * recoverable channel are kept across recoveries (unlike those of {@code channel.getConnection()}, which is the
     * connection of the moment), and a connection loss reaches them as a hard error.
     */
    private void watchConnectionLoss(com.rabbitmq.client.Channel channel) {
        channel.addShutdownListener(cause -> {
            if (cause.isHardError() && !cause.isInitiatedByApplication()) {
                failPendingReplies("its connection was lost: " + cause.getMessage());
            }
        });
    }

    private void subscribe() {
        if (stopped.get()) {
            return;
        }
        final AtomicReference<Throwable> terminationCause = new AtomicReference<>();
        final Disposable subscription = deliveryFlux
                .doOnError(terminationCause::set)
                .doOnTerminate(() -> {
                    liveness.onTerminated(terminationCause.get());
                    failPendingReplies("its subscription terminated and the reply queue is being recreated");
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
     * Fails the pending correlations of this domain: their replies cannot reach the lost reply queue, so callers see
     * the failure immediately instead of waiting for the reply timeout. The other domains have their own reply queue.
     */
    private void failPendingReplies(String reason) {
        if (stopped.get()) {
            return;
        }
        int lost = router.failAll(domain, new ReplyLostOnReconnectException("Reply lost: the reply listener of "
                + queueName + " is not consuming, " + reason));
        if (lost > 0) {
            log.log(Level.WARNING, () -> "Failed " + lost + " pending reply correlation(s) of reply queue "
                    + queueName + ": " + reason);
            ConsumerMetrics.recordRepliesLost(meterRegistry, domain, lost);
        }
    }

    /**
     * Schedules a new subscription after the current one terminated. Uses an exponential backoff with the same
     * bounds as the sender's confirm-flow re-subscription (200 ms → 5 s). The first restart is logged at WARNING
     * so an outage is visible; subsequent attempts log at FINE to avoid flooding when the broker is continuously
     * unavailable. The restart runs on a separate scheduler so it never recurses from the termination callback.
     */
    private void scheduleRestart(String reason) {
        if (stopped.get() || !restartScheduled.compareAndSet(false, true)) {
            return;
        }
        metrics.recordRestart(reason);
        long consecutive = liveness.consecutiveRestartFailures();
        long delayMs = backoffMillis(consecutive);
        Level level = consecutive <= 1 && firstRestartWarning.compareAndSet(false, true)
                ? Level.WARNING : Level.FINE;
        log.log(level, () -> "Re-subscribing reply listener for queue " + queueName + " in " + delayMs
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
}
