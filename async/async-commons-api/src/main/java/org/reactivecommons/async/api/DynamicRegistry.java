package org.reactivecommons.async.api;

import org.reactivecommons.async.api.handlers.EventHandler;
import reactor.core.publisher.Mono;

public interface DynamicRegistry {

    @Deprecated
    <T> Mono<Void> listenEvent(String eventName, EventHandler<T> fn, Class<T> eventClass);

    Mono<Void> startListeningEvent(String eventName);

    Mono<Void> stopListeningEvent(String eventName);

}
