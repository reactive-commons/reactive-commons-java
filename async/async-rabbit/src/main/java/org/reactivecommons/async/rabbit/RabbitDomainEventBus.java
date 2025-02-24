package org.reactivecommons.async.rabbit;

import io.cloudevents.CloudEvent;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.Collections;

public class RabbitDomainEventBus implements DomainEventBus {

    private static final String EVENT_SEND_FAILURE = "Event send failure: ";
    private static final String NOT_IMPLEMENTED_YET = "Not implemented yet";
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
                .onErrorMap(err -> new RuntimeException(EVENT_SEND_FAILURE + event.getName(), err));
    }

    @Override
    public <T> Publisher<Void> emit(String domain, DomainEvent<T> event) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_YET);
    }

    @Override
    public Publisher<Void> emit(CloudEvent cloudEvent) {
        return sender.sendWithConfirm(cloudEvent, exchange, cloudEvent.getType(),
                        Collections.emptyMap(), persistentEvents)
                .onErrorMap(err -> new RuntimeException(EVENT_SEND_FAILURE + cloudEvent.getType(), err));
    }

    @Override
    public Publisher<Void> emit(String domain, CloudEvent event) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_YET);
    }

    @Override
    public Publisher<Void> emit(RawMessage rawEvent) {
        return sender.sendWithConfirm(rawEvent, exchange, rawEvent.getType(),
                        Collections.emptyMap(), persistentEvents)
                .onErrorMap(err -> new RuntimeException(EVENT_SEND_FAILURE + rawEvent.getType(), err));
    }

    @Override
    public Publisher<Void> emit(String domain, RawMessage event) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_YET);
    }
}
