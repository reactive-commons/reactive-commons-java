package org.reactivecommons.async.rabbit.listeners;

import lombok.extern.java.Log;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

import java.util.function.Function;

@Log
public class ApplicationQueueListener extends GenericMessageListener {

    private final RegisteredQueueListener registeredListener;

    public ApplicationQueueListener(ReactiveMessageListener listener,
                                    boolean withDLQRetry,
                                    long maxRetries,
                                    int retryDelay,
                                    RegisteredQueueListener registeredListener,
                                    DiscardNotifier discardNotifier,
                                    CustomReporter errorReporter) {
        super(registeredListener.queueName(), listener, withDLQRetry, true, maxRetries, retryDelay,
                discardNotifier, "queue", errorReporter);
        this.registeredListener = registeredListener;
    }

    @Override
    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        return registeredListener.topologyHandlerSetup().setup(creator);
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        return message -> registeredListener.handler().handle(message).cast(Object.class);
    }

    protected String getExecutorPath(AcknowledgableDelivery msj) {
        return registeredListener.queueName();
    }

    @Override
    protected Object parseMessageForReporter(Message message) {
        return message;
    }

    @Override
    protected String getKind() {
        return "queue";
    }
}
