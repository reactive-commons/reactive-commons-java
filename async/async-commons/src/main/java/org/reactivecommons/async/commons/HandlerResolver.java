package org.reactivecommons.async.commons;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.utils.matcher.KeyMatcher;
import org.reactivecommons.async.commons.utils.matcher.Matcher;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@RequiredArgsConstructor
@SuppressWarnings("unchecked")
public class HandlerResolver {

    private final Map<String, RegisteredQueryHandler<?, ?>> queryHandlers;
    private final Map<String, RegisteredEventListener<?, ?>> eventListeners;
    private final Map<String, RegisteredEventListener<?, ?>> eventsToBind;
    private final Map<String, RegisteredEventListener<?, ?>> eventNotificationListeners;
    private final Map<String, RegisteredCommandHandler<?, ?>> commandHandlers;
    @Getter
    private final Map<String, RegisteredQueueListener> queueListeners;
    @Getter
    private final Map<String, RegisteredQueueListener> topicListeners;

    private final Matcher matcher = new KeyMatcher();
    /**
     * The events whose listening was stopped at runtime: their bindings are not declared again (e.g. when a listener
     * restarts and sets up its topology) until their listening is started again.
     */
    private final Set<String> stoppedEvents = ConcurrentHashMap.newKeySet();

    /**
     * Wildcard matches are cached apart from the registered handlers (which a lookup without match scans) and up to
     * a limit, since the routing keys matched by a wildcard are not bounded.
     */
    static final int MAX_CACHED_MATCHES = 1_000;
    private final Map<String, RegisteredQueryHandler<?, ?>> matchedQueryHandlers = new ConcurrentHashMap<>();
    private final Map<String, RegisteredCommandHandler<?, ?>> matchedCommandHandlers = new ConcurrentHashMap<>();
    private final Map<String, RegisteredEventListener<?, ?>> matchedNotificationListeners =
            new ConcurrentHashMap<>();

    public boolean hasNotificationListeners() {
        return !eventNotificationListeners.isEmpty();
    }

    public boolean hasCommandHandlers() {
        return !commandHandlers.isEmpty();
    }

    public boolean hasQueryHandlers() {
        return !queryHandlers.isEmpty();
    }

    public <T, M> RegisteredQueryHandler<T, M> getQueryHandler(String path) {
        return (RegisteredQueryHandler<T, M>) resolve(queryHandlers, matchedQueryHandlers, path);
    }

    public <T, D> RegisteredCommandHandler<T, D> getCommandHandler(String path) {
        return (RegisteredCommandHandler<T, D>) resolve(commandHandlers, matchedCommandHandlers, path);
    }

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
        return (RegisteredEventListener<T, D>) resolve(eventNotificationListeners, matchedNotificationListeners, path);
    }

    // Returns only the listenEvent not the handleDynamicEvents, nor the events whose listening was stopped
    public Collection<RegisteredEventListener<?, ?>> getEventListeners() {
        if (stoppedEvents.isEmpty()) {
            return eventsToBind.values();
        }
        return eventsToBind.values().stream().filter(listener -> !stoppedEvents.contains(listener.path())).toList();
    }

    /**
     * Stops binding the event: it is left out of {@link #getEventListeners()} until
     * {@link #startListeningEvent(String)}.
     */
    public void stopListeningEvent(String eventName) {
        stoppedEvents.add(eventName);
    }

    public void startListeningEvent(String eventName) {
        stoppedEvents.remove(eventName);
    }

    public List<String> getEventNames() {
        return List.copyOf(eventListeners.keySet());
    }

    public List<String> getNotificationNames() {
        return List.copyOf(eventNotificationListeners.keySet());
    }

    public void addEventListener(RegisteredEventListener<?, ?> listener) {
        eventListeners.put(listener.path(), listener);
    }

    public void addQueryHandler(RegisteredQueryHandler<?, ?> handler) {
        if (handler.path().contains("*") || handler.path().contains("#")) {
            throw new RuntimeException(
                    "avoid * or # in dynamic handlers, make sure you have no conflicts with cached patterns"
            );
        }
        queryHandlers.put(handler.path(), handler);
    }

    private <T> T resolve(Map<String, T> registered, Map<String, T> matched, String path) {
        final T exact = registered.get(path);
        if (exact != null) {
            return exact;
        }
        final T cached = matched.get(path);
        if (cached != null) {
            return cached;
        }
        final T match = getMatchHandler(registered).apply(path);
        if (match != null && matched.size() < MAX_CACHED_MATCHES) {
            matched.putIfAbsent(path, match);
        }
        return match;
    }

    private <T> Function<String, T> getMatchHandler(Map<String, T> handlers) {
        return name -> handlers.get(matcher.match(handlers.keySet(), name));
    }

}
