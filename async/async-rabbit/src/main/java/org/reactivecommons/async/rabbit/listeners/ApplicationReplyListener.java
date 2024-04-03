package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.Delivery;
import lombok.extern.java.Log;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.commons.utils.LoggerSubscriber;
import org.reactivecommons.async.rabbit.RabbitMessage;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.Receiver;

import java.util.logging.Level;

import static org.reactivecommons.async.commons.Headers.COMPLETION_ONLY_SIGNAL;
import static org.reactivecommons.async.commons.Headers.CORRELATION_ID;
import static reactor.rabbitmq.ResourcesSpecification.binding;
import static reactor.rabbitmq.ResourcesSpecification.exchange;
import static reactor.rabbitmq.ResourcesSpecification.queue;

@Log
public class ApplicationReplyListener {

    private final ReactiveReplyRouter router;
    private final Receiver receiver;
    private final TopologyCreator creator;
    private final String queueName;
    private final String exchangeName;

    private final boolean createTopology;
    private volatile Flux<Delivery> deliveryFlux;

    public ApplicationReplyListener(ReactiveReplyRouter router, ReactiveMessageListener listener, String queueName,
                                    String exchangeName, boolean createTopology) {
        this.router = router;
        this.queueName = queueName;
        this.exchangeName = exchangeName;
        this.receiver = listener.getReceiver();
        this.creator = listener.getTopologyCreator();
        this.createTopology = createTopology;
    }

    public void startListening(String routeKey) {
        Mono<Void> flow = Mono.empty();
        if (createTopology) {
            flow = creator.declare(exchange(exchangeName).type("topic").durable(true)).then();
        }
        deliveryFlux = flow
                .then(creator.declare(queue(queueName).durable(false).autoDelete(true).exclusive(true)))
                .then(creator.bind(binding(exchangeName, routeKey, queueName)))
                .thenMany(receiver.consumeAutoAck(queueName).doOnNext(delivery -> {
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

        onTerminate();
    }

    private void onTerminate() {
        deliveryFlux.doOnTerminate(this::onTerminate)
                .subscribe(new LoggerSubscriber<>(getClass().getName()));
    }

}