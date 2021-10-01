package org.reactivecommons.async.api;

import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;
import reactor.core.publisher.Mono;

public interface DynamicRegistry {

    @Deprecated
    <T> Mono<Void> listenEvent(String eventName, EventHandler<T> fn, Class<T> eventClass);

    <T, R> void serveQuery(String resource, QueryHandler<T, R> handler, Class<R> queryClass);

    <R> void serveQuery(String resource, QueryHandlerDelegate<Void, R> handler, Class<R> queryClass);

    Mono<Void> startListeningEvent(String eventName);

    Mono<Void> stopListeningEvent(String eventName);

}
