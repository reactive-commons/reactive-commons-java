package org.reactivecommons.api.domain;

import io.cloudevents.CloudEvent;
import org.reactivestreams.Publisher;

public interface DomainEventBus {
    <T> Publisher<Void> emit(DomainEvent<T> event);

    <T> Publisher<Void> emit(String domain, DomainEvent<T> event);

    Publisher<Void> emit(CloudEvent event);

    Publisher<Void> emit(String domain, CloudEvent event);

    Publisher<Void> emit(RawMessage event);
    Publisher<Void> emit(String domain, RawMessage event);
}
