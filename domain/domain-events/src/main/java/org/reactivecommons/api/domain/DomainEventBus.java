package org.reactivecommons.api.domain;

import reactor.core.publisher.Mono;

public interface DomainEventBus {

    <T> Mono<Void> emit(DomainEvent<T> event);

}
