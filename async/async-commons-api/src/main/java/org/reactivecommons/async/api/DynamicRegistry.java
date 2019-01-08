package org.reactivecommons.async.api;

import org.reactivecommons.async.api.handlers.EventHandler;
import reactor.core.publisher.Mono;

public interface DynamicRegistry {
    <T> Mono<Void> listenEvent(String eventName, EventHandler<T> fn, Class<T> eventClass);
}
