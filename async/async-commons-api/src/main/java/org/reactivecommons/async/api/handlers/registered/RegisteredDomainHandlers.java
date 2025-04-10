package org.reactivecommons.async.api.handlers.registered;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class RegisteredDomainHandlers<T> extends ConcurrentHashMap<String, List<T>> {
    private static final String DEFAULT_DOMAIN = "app";

    public RegisteredDomainHandlers() {
        super();
        put(DEFAULT_DOMAIN, new CopyOnWriteArrayList<>());
    }

    public void add(String domain, T handler) {
        computeIfAbsent(domain, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }
}
