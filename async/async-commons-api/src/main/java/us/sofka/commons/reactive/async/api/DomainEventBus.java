package us.sofka.commons.reactive.async.api;

import reactor.core.publisher.Mono;

public interface DomainEventBus {

    <T> Mono<Void> emit(DomainEvent<T> event);

}
