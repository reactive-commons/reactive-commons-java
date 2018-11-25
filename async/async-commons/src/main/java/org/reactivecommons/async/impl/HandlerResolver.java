package org.reactivecommons.async.impl;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.HandlerRegistry;

import java.util.Collection;
import java.util.Map;

@RequiredArgsConstructor
public class HandlerResolver {

    private final Map<String, QueryHandler<?, ?>> queryHandlers;
    private final Map<String, HandlerRegistry.RegisteredEventListener> eventListeners;
    private final Map<String, HandlerRegistry.RegisteredCommandHandler> commandHandlers;


    @SuppressWarnings("unchecked")
    public <T, R> QueryHandler<T, R> getQueryHandler(String path) {
        return (QueryHandler<T, R>) queryHandlers.get(path);
    }

    @SuppressWarnings("unchecked")
    public <T> HandlerRegistry.RegisteredCommandHandler<T> getCommandHandler(String path) {
        return commandHandlers.get(path);
    }

    @SuppressWarnings("unchecked")
    public <T> HandlerRegistry.RegisteredEventListener<T> getEventListener(String path) {
        return eventListeners.get(path);
    }

    public Collection<HandlerRegistry.RegisteredEventListener> getEventListeners() {
        return eventListeners.values();
    }
}
