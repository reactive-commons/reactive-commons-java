package org.reactivecommons.async.impl.listeners;

import lombok.extern.java.Log;
import org.reactivecommons.api.domain.Headers;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.reply.ReactiveReplyRouter;
import reactor.rabbitmq.Receiver;

import java.util.logging.Level;

import static reactor.rabbitmq.ResourcesSpecification.*;

@Log
public class ApplicationReplyListener {

    private final ReactiveReplyRouter router;
    private final Receiver receiver;
    private final TopologyCreator creator;
    private final String queueName;

    public ApplicationReplyListener(ReactiveReplyRouter router, ReactiveMessageListener listener, String queueName) {
        this.router = router;
        this.queueName = queueName;
        this.receiver = listener.getReceiver();
        this.creator = listener.getTopologyCreator();
    }

    public void startListening(String routeKey) {
        creator.declare(exchange("globalReply").type("topic").durable(true))
                .then(creator.declare(queue(queueName).durable(false).autoDelete(true).exclusive(true)))
                .then(creator.bind(binding("globalReply", routeKey, queueName)))
                .thenMany(receiver.consumeAutoAck(queueName).doOnNext(delivery -> {
                    try {
                        final String correlationID = delivery.getProperties().getHeaders().get(Headers.CORRELATION_ID).toString();
                        final String signalType = delivery.getProperties().getHeaders().get(Headers.SIGNAL_TYPE).toString();

                        if (signalType.equals("dataful")) {
                            router.routeReply(correlationID, new String(delivery.getBody()));
                        } else {
                            router.routeEmpty(correlationID);
                        }
                    } catch (Exception e) {
                        log.log(Level.SEVERE, "Error in reply reception", e);
                    }
                })).subscribe();
    }
}