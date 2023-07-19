package org.reactivecommons.api.domain;

import io.cloudevents.CloudEvent;
import org.reactivestreams.Publisher;

public interface DomainEventBus {
    <T> Publisher<Void> emit(DomainEvent<T> event);

    Publisher<Void> emit(CloudEvent event);

}
