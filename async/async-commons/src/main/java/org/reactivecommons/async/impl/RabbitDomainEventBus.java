package org.reactivecommons.async.impl;

import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.config.BrokerConfig;
import reactor.core.publisher.Mono;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;

import java.util.Collections;

public class RabbitDomainEventBus implements DomainEventBus {

    private final ReactiveMessageSender sender;
    private final String exchange;
    private final boolean persistentEvents;

    public RabbitDomainEventBus(ReactiveMessageSender sender, String exchange) {
        this(sender, exchange, new BrokerConfig());
    }

    public RabbitDomainEventBus(ReactiveMessageSender sender, String exchange, BrokerConfig config) {
        this.sender = sender;
        this.exchange = exchange;
        persistentEvents = config.isPersistentEvents();
    }

    @Override
    public <T> Mono<Void> emit(DomainEvent<T> event) {
        return sender.sendWithConfirm(event, exchange, event.getName(), Collections.emptyMap(), persistentEvents)
            .onErrorMap(err -> new RuntimeException("Event send failure: " + event.getName(), err));
    }

}
