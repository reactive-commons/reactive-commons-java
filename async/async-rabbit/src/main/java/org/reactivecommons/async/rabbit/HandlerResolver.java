package org.reactivecommons.async.rabbit;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.utils.matcher.KeyMatcher;
import org.reactivecommons.async.commons.utils.matcher.Matcher;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@RequiredArgsConstructor
public class HandlerResolver {

    private final Map<String, RegisteredQueryHandler<?, ?>> queryHandlers;
    private final Map<String, RegisteredEventListener<?>> eventListeners;
    private final Map<String, RegisteredEventListener<?>> eventNotificationListeners;
    private final Map<String, RegisteredEventListener<?>> dynamicEventsHandlers;
    private final Map<String, RegisteredCommandHandler<?>> commandHandlers;
    private final Matcher matcher = new KeyMatcher();

    @SuppressWarnings("unchecked")
    public <T, M> RegisteredQueryHandler<T, M> getQueryHandler(String path) {
        return (RegisteredQueryHandler<T, M>) queryHandlers
                .computeIfAbsent(path, getMatchHandler(queryHandlers));
    }

    @SuppressWarnings("unchecked")
    public <T> RegisteredCommandHandler<T> getCommandHandler(String path) {
        return (RegisteredCommandHandler<T>) commandHandlers
                .computeIfAbsent(path, getMatchHandler(commandHandlers));
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
        return (RegisteredEventListener<T>) eventNotificationListeners
                .computeIfAbsent(path, getMatchHandler(eventNotificationListeners));
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

    void addEventListener(RegisteredEventListener<?> listener) {
        eventListeners.put(listener.getPath(), listener);
    }

    private <T> Function<String, T> getMatchHandler(Map<String, T> handlers) {
        return name -> {
            String matched = matcher.match(handlers.keySet(), name);
            return handlers.get(matched);
        };
    }

}
