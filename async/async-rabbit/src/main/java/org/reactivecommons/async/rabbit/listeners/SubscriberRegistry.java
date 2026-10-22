package org.reactivecommons.async.rabbit.listeners;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Per-domain registry of listener livenesses. Owned by the broker provider: each listener registers its
 * {@link ListenerLiveness} on {@code startListener()} and unregisters on {@code stop()}. The health indicator
 * reads {@link #snapshot()} to surface a broken consumer.
 *
 * <p>Instances are safe for concurrent use.
 */
public final class SubscriberRegistry {

    private final ConcurrentHashMap<String, ListenerLiveness> livenesses = new ConcurrentHashMap<>();

    public ListenerLiveness register(String queueName) {
        return livenesses.computeIfAbsent(queueName, ListenerLiveness::new);
    }

    public void unregister(String queueName) {
        livenesses.remove(queueName);
    }

    public Map<String, SubscriberStatus> snapshot() {
        if (livenesses.isEmpty()) {
            return Collections.emptyMap();
        }
        return livenesses.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().snapshot()));
    }

    public int size() {
        return livenesses.size();
    }
}
