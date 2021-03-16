package org.reactivecommons.async.parent;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
public class HandlerResolver {

    private final Map<String, RegisteredQueryHandler<?, ?>> queryHandlers;
    private final Map<String, RegisteredEventListener<?>> eventListeners;
    private final Map<String, RegisteredEventListener<?>> eventNotificationListeners;
    private final Map<String, RegisteredEventListener<?>> dynamicEventsHandlers;
    private final Map<String, RegisteredCommandHandler<?>> commandHandlers;

    @SuppressWarnings("unchecked")
    public <T, M> RegisteredQueryHandler<T, M> getQueryHandler(String path) {
        return (RegisteredQueryHandler<T, M>) queryHandlers.get(path);
    }

    @SuppressWarnings("unchecked")
    public <T> RegisteredCommandHandler<T> getCommandHandler(String path) {
        return (RegisteredCommandHandler<T>) commandHandlers.get(path);
    }

    @SuppressWarnings("unchecked")
    public <T> RegisteredEventListener<T> getEventListener(String path) {
        return (RegisteredEventListener<T>) eventListeners.get(path);
    }

    @SuppressWarnings("unchecked")
    public <T> RegisteredEventListener<T> getDynamicEventsHandler(String path) {
        return (RegisteredEventListener<T>) dynamicEventsHandlers.get(path);
    }

    public Collection<RegisteredEventListener<?>> getNotificationListeners() {
        return eventNotificationListeners.values();
    }

    @SuppressWarnings("unchecked")
    public <T> RegisteredEventListener<T> getNotificationListener(String path) {
        return (RegisteredEventListener<T>) eventNotificationListeners.get(path);
    }

    public Collection<RegisteredEventListener<?>> getEventListeners() {
        return eventListeners.values();
    }

    public Set<String> getToListenEventNames() {
        Set<String> toListenEventNames = new HashSet<>(eventListeners.size() +
                dynamicEventsHandlers.size());

        toListenEventNames.addAll(eventListeners.keySet());
        toListenEventNames.addAll(dynamicEventsHandlers.keySet());

        return toListenEventNames;
    }

    void addEventListener(RegisteredEventListener listener) {
        eventListeners.put(listener.getPath(), listener);
    }
}
