package org.reactivecommons.api.domain;

import org.reactivestreams.Publisher;

public interface DomainEventBus {
    <T> Publisher<Void> emit(DomainEvent<T> event);
}
