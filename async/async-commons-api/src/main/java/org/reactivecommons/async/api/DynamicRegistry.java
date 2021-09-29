package org.reactivecommons.async.api;

import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.QueryHandler;
import reactor.core.publisher.Mono;

public interface DynamicRegistry {

    @Deprecated
    <T> Mono<Void> listenEvent(String eventName, EventHandler<T> fn, Class<T> eventClass);

    <T, R> void serveQuery(String resource, QueryHandler<T, R> handler, Class<R> queryClass);

    Mono<Void> startListeningEvent(String eventName);

    Mono<Void> stopListeningEvent(String eventName);

}
