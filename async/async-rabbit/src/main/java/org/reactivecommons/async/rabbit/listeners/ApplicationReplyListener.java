package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.UnblockedCallback;
import lombok.extern.java.Log;
import org.reactivecommons.async.rabbit.RabbitMessage;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.rabbitmq.Receiver;

import java.util.logging.Level;

import static org.reactivecommons.async.commons.Headers.*;
import static reactor.core.publisher.Signal.subscribe;
import static reactor.rabbitmq.ResourcesSpecification.*;

@Log
public class ApplicationReplyListener {

    private final ReactiveReplyRouter router;
    private final Receiver receiver;
    private final TopologyCreator creator;
    private final String queueName;
    private volatile Flux<Delivery> deliveryFlux;

    public ApplicationReplyListener(ReactiveReplyRouter router, ReactiveMessageListener listener, String queueName) {
        this.router = router;
        this.queueName = queueName;
        this.receiver = listener.getReceiver();
        this.creator = listener.getTopologyCreator();
    }

    public void startListening(String routeKey) {
        deliveryFlux = creator.declare(exchange("globalReply").type("topic").durable(true))
            .then(creator.declare(queue(queueName).durable(false).autoDelete(true).exclusive(true)))
            .then(creator.bind(binding("globalReply", routeKey, queueName)))
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
        //deliveryFlux.subscribe();
        onTerminate();
    }


    private void onTerminate() {
        deliveryFlux.doOnTerminate(this::onTerminate).subscribe(new BaseSubscriber<Delivery>() {
            @Override
            protected void hookOnNext(Delivery value) {
                log.info("#On NextHook");
            }

            @Override
            protected void hookOnComplete() {
                log.warning("#On Complete Hook!!");
            }

            @Override
            protected void hookOnError(Throwable throwable) {
                log.log(Level.SEVERE, "#Hook On Error!!", throwable);
            }

            @Override
            protected void hookOnCancel() {
                log.warning("#On Cancel Hook!!");
            }

            @Override
            protected void hookFinally(SignalType type) {
                log.warning("#On Finally Hook!! " + type.name());
            }
        });
    }
}