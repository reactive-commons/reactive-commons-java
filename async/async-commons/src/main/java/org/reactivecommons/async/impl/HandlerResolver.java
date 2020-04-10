package org.reactivecommons.async.impl;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;

import java.util.Collection;
import java.util.Map;

@RequiredArgsConstructor
public class HandlerResolver {

    private final Map<String, RegisteredQueryHandler> queryHandlers;
    private final Map<String, RegisteredEventListener> eventListeners;
    private final Map<String, RegisteredCommandHandler> commandHandlers;


    @SuppressWarnings("unchecked")
    public <T, R> RegisteredQueryHandler<T, R> getQueryHandler(String path) {
        return (RegisteredQueryHandler<T, R>) queryHandlers.get(path);
    }

    @SuppressWarnings("unchecked")
    public <T> RegisteredCommandHandler<T> getCommandHandler(String path) {
        return commandHandlers.get(path);
    }

    @SuppressWarnings("unchecked")
    public <T> RegisteredEventListener<T> getEventListener(String path) {
        return eventListeners.get(path);
    }

    public Collection<RegisteredEventListener> getEventListeners() {
        return eventListeners.values();
    }

    void addEventListener(RegisteredEventListener listener) {
        eventListeners.put(listener.getPath(), listener);
    }
}
