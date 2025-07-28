package org.reactivecommons.async.api.handlers.registered;

import org.reactivecommons.async.api.handlers.EventHandler;

public record RegisteredEventListener<T, D>(String path, EventHandler<D> handler, Class<T> inputClass) {
}
