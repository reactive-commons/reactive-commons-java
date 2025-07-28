package org.reactivecommons.async.kafka.listeners;

import lombok.extern.java.Log;
import org.reactivecommons.async.api.handlers.CloudEventHandler;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.EventExecutor;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import java.util.UUID;
import java.util.function.Function;

@Log
public class ApplicationNotificationsListener extends GenericMessageListener {

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;


    public ApplicationNotificationsListener(ReactiveMessageListener receiver,
                                            HandlerResolver resolver,
                                            MessageConverter messageConverter,
                                            boolean withDLQRetry,
                                            boolean createTopology,
                                            long maxRetries,
                                            int retryDelay,
                                            DiscardNotifier discardNotifier,
                                            CustomReporter errorReporter,
                                            String appName) {
        super(receiver, withDLQRetry, createTopology, maxRetries, retryDelay, discardNotifier,
                "event", errorReporter, appName + "-notification-" + UUID.randomUUID(),
                resolver.getNotificationNames());
        this.resolver = resolver;
        this.messageConverter = messageConverter;
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredEventListener<Object, Object> handler = resolver.getNotificationListener(executorPath);

        Function<Message, Object> converter = resolveConverter(handler);
        final EventExecutor<Object> executor = new EventExecutor<>(handler.handler(), converter);

        return msj -> executor
                .execute(msj)
                .cast(Object.class);
    }

    protected String getExecutorPath(ReceiverRecord<String, byte[]> msj) {
        return msj.topic();
    }

    @Override
    protected Object parseMessageForReporter(Message msj) {
        return messageConverter.readDomainEventStructure(msj);
    }

    private <T, D> Function<Message, Object> resolveConverter(RegisteredEventListener<T, D> registeredEventListener) {
        if (registeredEventListener.handler() instanceof DomainEventHandler) {
            final Class<T> eventClass = registeredEventListener.inputClass();
            return msj -> messageConverter.readDomainEvent(msj, eventClass);
        }
        if (registeredEventListener.handler() instanceof CloudEventHandler) {
            return messageConverter::readCloudEvent;
        }
        throw new RuntimeException("Unknown handler type");
    }
}


