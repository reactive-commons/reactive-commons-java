package org.reactivecommons.async.starter.senders;

import io.cloudevents.CloudEvent;
import lombok.AllArgsConstructor;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivestreams.Publisher;

import java.util.concurrent.ConcurrentMap;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@AllArgsConstructor
public class GenericDomainEventBus implements DomainEventBus {
    private final ConcurrentMap<String, DomainEventBus> domainEventBuses;

    @Override
    public <T> Publisher<Void> emit(DomainEvent<T> event) {
        return emit(DEFAULT_DOMAIN, event);
    }

    @Override
    public <T> Publisher<Void> emit(String domain, DomainEvent<T> event) {
        return domainEventBuses.get(domain).emit(event);
    }

    @Override
    public Publisher<Void> emit(CloudEvent event) {
        return emit(DEFAULT_DOMAIN, event);
    }

    @Override
    public Publisher<Void> emit(String domain, CloudEvent event) {
        return domainEventBuses.get(domain).emit(event);
    }
}
