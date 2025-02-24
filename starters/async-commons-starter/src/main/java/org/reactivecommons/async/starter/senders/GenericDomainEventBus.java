package org.reactivecommons.async.starter.senders;

import io.cloudevents.CloudEvent;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentMap;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@RequiredArgsConstructor
public class GenericDomainEventBus implements DomainEventBus {
    private final ConcurrentMap<String, DomainEventBus> domainEventBuses;


    @Override
    public <T> Publisher<Void> emit(DomainEvent<T> event) {
        return emit(DEFAULT_DOMAIN, event);
    }

    @Override
    public <T> Publisher<Void> emit(String domain, DomainEvent<T> event) {
        DomainEventBus domainEventBus = domainEventBuses.get(domain);
        if (domainEventBus == null) {
            return Mono.error(() -> new InvalidConfigurationException("Domain not found: " + domain));
        }
        return domainEventBus.emit(event);
    }

    @Override
    public Publisher<Void> emit(CloudEvent event) {
        return emit(DEFAULT_DOMAIN, event);
    }

    @Override
    public Publisher<Void> emit(String domain, CloudEvent event) {
        DomainEventBus domainEventBus = domainEventBuses.get(domain);
        if (domainEventBus == null) {
            return Mono.error(() -> new InvalidConfigurationException("Domain not found: " + domain));
        }
        return domainEventBus.emit(event);
    }

    @Override
    public Publisher<Void> emit(RawMessage event) {
        return emit(DEFAULT_DOMAIN, event);
    }

    @Override
    public Publisher<Void> emit(String domain, RawMessage event) {
        DomainEventBus domainEventBus = domainEventBuses.get(domain);
        if (domainEventBus == null) {
            return Mono.error(() -> new InvalidConfigurationException("Domain not found: " + domain));
        }
        return domainEventBus.emit(event);
    }
}
