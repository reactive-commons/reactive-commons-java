package org.reactivecommons.async.kafka.listeners;

import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.EventExecutor;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import java.util.UUID;
import java.util.function.Function;

public class ApplicationNotificationsListener extends GenericMessageListener {

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;


    public ApplicationNotificationsListener(ListenerContext<ReactiveMessageListener> context, String appName) {
        super(context, "event", appName + "-notification-" + UUID.randomUUID(),
                context.resolver().getNotificationNames());
        this.resolver = context.resolver();
        this.messageConverter = context.converter();
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredEventListener<Object, Object> handler = resolver.getNotificationListener(executorPath);

        final EventExecutor<Object> executor = EventExecutor.of(handler, messageConverter);

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
}


