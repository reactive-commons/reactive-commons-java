package org.reactivecommons.async.kafka.listeners;

import org.reactivecommons.async.api.handlers.CloudEventHandler;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
import org.reactivecommons.async.api.handlers.RawEventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.EventExecutor;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import java.util.function.Function;

public class ApplicationEventListener extends GenericMessageListener {

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;


    public ApplicationEventListener(ListenerContext<ReactiveMessageListener> context, String groupId) {
        super(context, "event", groupId, context.resolver().getEventNames());
        this.resolver = context.resolver();
        this.messageConverter = context.converter();
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredEventListener<Object, Object> handler = resolver.getEventListener(executorPath);

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
        if (registeredEventListener.handler() instanceof RawEventHandler) {
            return message -> message;
        }
        throw new RuntimeException("Unknown handler type");
    }
}


