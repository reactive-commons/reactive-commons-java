package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.Delivery;
import lombok.extern.java.Log;
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

    private static final Duration RESTART_DELAY = Duration.ofMillis(200);

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

    public ApplicationReplyListener(ReactiveReplyRouter router, ReactiveMessageListener listener, String queueName,
                                    String exchangeName, boolean createTopology) {
        this.router = router;
        this.queueName = queueName;
        this.exchangeName = exchangeName;
        this.receiver = listener.receiver();
        this.creator = listener.topologyCreator();
        this.createTopology = createTopology;
    }

    public void startListening(String routeKey) {
        Mono<Void> flow = Mono.empty();
        if (createTopology) {
            flow = creator.declare(exchange(exchangeName).type("topic").durable(true)).then();
        }
        ConsumeOptions consumeOptions = new ConsumeOptions()
                .exceptionHandler(FailFastAcknowledgmentHandler.INSTANCE);
        consumeOptions.consumerTag(InstanceIdentifier.getInstanceId("replies"));
        deliveryFlux = flow
                .then(creator.declare(queue(queueName).durable(false).autoDelete(true).exclusive(true)))
                .then(creator.bind(binding(exchangeName, routeKey, queueName)))
                .thenMany(receiver.consumeAutoAck(queueName, consumeOptions).doOnNext(delivery -> {
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
    }

    private void subscribe() {
        if (stopped.get()) {
            return;
        }
        final Disposable subscription = deliveryFlux.doOnTerminate(this::scheduleRestart)
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
     * Schedules a new subscription after the current one terminated. The restart is never done from within the
     * termination callback: subscribing there would recurse until the stack overflows when the new subscription fails
     * synchronously (e.g. the connection is already closed).
     */
    private void scheduleRestart() {
        if (stopped.get() || !restartScheduled.compareAndSet(false, true)) {
            return;
        }
        Schedulers.boundedElastic().schedule(() -> {
            restartScheduled.set(false);
            subscribe();
        }, RESTART_DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

}