package org.reactivecommons.async.rabbit;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.utils.matcher.KeyMatcher;
import org.reactivecommons.async.commons.utils.matcher.Matcher;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

@Log
@RequiredArgsConstructor
public class HandlerResolver {

    private final Map<String, RegisteredQueryHandler<?, ?>> queryHandlers;
    private final Map<String, RegisteredEventListener<?, ?>> eventListeners;
    private final Map<String, RegisteredEventListener<?, ?>> eventsToBind;
    private final Map<String, RegisteredEventListener<?, ?>> eventNotificationListeners;
    private final Map<String, RegisteredCommandHandler<?, ?>> commandHandlers;

    private final Matcher matcher = new KeyMatcher();

    @SuppressWarnings("unchecked")
    public <T, M> RegisteredQueryHandler<T, M> getQueryHandler(String path) {
        return (RegisteredQueryHandler<T, M>) queryHandlers
                .computeIfAbsent(path, getMatchHandler(queryHandlers));
    }

    @SuppressWarnings("unchecked")
    public <T, D> RegisteredCommandHandler<T, D> getCommandHandler(String path) {
        return (RegisteredCommandHandler<T, D>) commandHandlers
                .computeIfAbsent(path, getMatchHandler(commandHandlers));
    }

    @SuppressWarnings("unchecked")
    public <T, D> RegisteredEventListener<T, D> getEventListener(String path) {
        if (eventListeners.containsKey(path)) {
            return (RegisteredEventListener<T, D>) eventListeners.get(path);
        }
        return (RegisteredEventListener<T, D>) getMatchHandler(eventListeners).apply(path);
    }

    public Collection<RegisteredEventListener<?, ?>> getNotificationListeners() {
        return eventNotificationListeners.values();
    }

    @SuppressWarnings("unchecked")
    public <T, D> RegisteredEventListener<T, D> getNotificationListener(String path) {
        return (RegisteredEventListener<T, D>) eventNotificationListeners
                .computeIfAbsent(path, getMatchHandler(eventNotificationListeners));
    }

    // Returns only the listenEvent not the handleDynamicEvents
    public Collection<RegisteredEventListener<?, ?>> getEventListeners() {
        return eventsToBind.values();
    }

    void addEventListener(RegisteredEventListener<?, ?> listener) {
        eventListeners.put(listener.getPath(), listener);
    }

    void addQueryHandler(RegisteredQueryHandler<?, ?> handler) {
        if (handler.getPath().contains("*") || handler.getPath().contains("#")) {
            throw new RuntimeException("avoid * or # in dynamic handlers, make sure you have no conflicts with cached patterns");
        }
        queryHandlers.put(handler.getPath(), handler);
    }

    private <T> Function<String, T> getMatchHandler(Map<String, T> handlers) {
        return name -> {
            String matched = matcher.match(handlers.keySet(), name);
            return handlers.get(matched);
        };
    }

}
