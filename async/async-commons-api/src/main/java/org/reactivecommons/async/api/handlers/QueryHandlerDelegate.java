package org.reactivecommons.async.api.handlers;

import org.reactivecommons.async.api.From;
import reactor.core.publisher.Mono;

public interface QueryHandlerDelegate<M> {
    Mono<Void> handle(From from, M message);
}
