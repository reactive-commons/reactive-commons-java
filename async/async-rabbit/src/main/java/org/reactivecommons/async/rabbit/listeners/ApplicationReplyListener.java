package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.Delivery;
import lombok.extern.slf4j.Slf4j;
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
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.reactivecommons.async.commons.Headers.COMPLETION_ONLY_SIGNAL;
import static org.reactivecommons.async.commons.Headers.CORRELATION_ID;
import static reactor.rabbitmq.BindingSpecification.binding;
import static reactor.rabbitmq.ExchangeSpecification.exchange;
import static reactor.rabbitmq.QueueSpecification.queue;


@Slf4j
public class ApplicationReplyListener {

    private final ReactiveReplyRouter router;
    private final Receiver receiver;
    private final TopologyCreator creator;
    private final String queueName;
    private final String exchangeName;

    private final boolean createTopology;
    private volatile Flux<Delivery> deliveryFlux;
    private final AtomicReference<Disposable> activeSubscription = new AtomicReference<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final ListenerLiveness liveness;
    private final ListenerRestarts restarts;
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
        this.restarts = new ListenerRestarts("reply listener for queue " + queueName, liveness, metrics,
                stopped::get, this::subscribe);
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
                        log.error("Error in reply reception", e);
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
        // the previous consumer is cancelled before the new one is registered, so they never overlap
        final Disposable previous = activeSubscription.getAndSet(null);
        if (previous != null) {
            previous.dispose();
        }
        final AtomicReference<Throwable> terminationCause = new AtomicReference<>();
        final Disposable subscription = deliveryFlux
                .doOnError(terminationCause::set)
                .doOnTerminate(() -> {
                    liveness.onTerminated(terminationCause.get());
                    failPendingReplies("its subscription terminated and the reply queue is being recreated");
                    restarts.schedule(terminationCause.get() != null
                            ? ConsumerMetrics.REASON_ERROR : ConsumerMetrics.REASON_CANCEL);
                })
                .subscribeWith(new LoggerSubscriber<>(getClass().getName()));
        final Disposable concurrent = activeSubscription.getAndSet(subscription);
        if (concurrent != null && concurrent != subscription) {
            concurrent.dispose();
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
            log.warn("Failed {} pending reply correlation(s) of reply queue {}: {}", lost, queueName, reason);
            ConsumerMetrics.recordRepliesLost(meterRegistry, domain, lost);
        }
    }
}
