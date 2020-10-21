package org.reactivecommons.async.api.handlers;

import reactor.core.publisher.Mono;

public interface QueryExecutor<R, M> {
    Mono<R> execute(M rawMessage);
}
