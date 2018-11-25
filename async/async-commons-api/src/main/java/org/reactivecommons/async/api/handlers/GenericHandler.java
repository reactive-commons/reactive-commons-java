package org.reactivecommons.async.api.handlers;

import reactor.core.publisher.Mono;

public interface GenericHandler<T, M> {
    Mono<T> handle(M message);
}
