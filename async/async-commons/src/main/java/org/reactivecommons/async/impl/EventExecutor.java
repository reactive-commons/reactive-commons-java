package org.reactivecommons.async.impl;


import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.parent.communications.Message;
import reactor.core.publisher.Mono;

import java.util.function.Function;

public class EventExecutor<T> {
    private final EventHandler<T> eventHandler;
    private final Function<Message, DomainEvent<T>> converter;

    public EventExecutor(EventHandler<T> eventHandler, Function<Message, DomainEvent<T>> converter) {
        this.eventHandler = eventHandler;
        this.converter = converter;
    }

    public Mono<Void> execute(Message rawMessage){
        return eventHandler.handle(converter.apply(rawMessage));
    }
}
