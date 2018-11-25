package org.reactivecommons.async.impl;

import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import reactor.core.publisher.Mono;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;

import java.util.Collections;

public class RabbitDomainEventBus implements DomainEventBus {

    private final ReactiveMessageSender sender;
    private final String exchange;

    public RabbitDomainEventBus(ReactiveMessageSender sender, String exchange) {
        this.sender = sender;
        this.exchange = exchange;
    }

    @Override
    public <T> Mono<Void> emit(DomainEvent<T> event) {
        return sender.sendWithConfirm(event, exchange, event.getName(), Collections.emptyMap())
            .onErrorMap(err -> new RuntimeException("Event send failure: " + event.getName(), err));
    }

}
