package org.reactivecommons.async.starter.config;

import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.starter.broker.BrokerProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

@Slf4j
@SuppressWarnings("rawtypes")
public class ConnectionManager {
    private final Map<String, BrokerProvider> connections = new TreeMap<>();

    public void forDomain(BiConsumer<String, BrokerProvider> consumer) {
        connections.forEach(consumer);
    }

    public ConnectionManager addDomain(String domain, BrokerProvider domainConn) {
        connections.put(domain, domainConn);
        return this;
    }

    public Map<String, BrokerProvider> getProviders() {
        return connections;
    }

    /**
     * Starts consuming with the listeners of every provider (see {@link BrokerProvider#start()}).
     */
    public void start() {
        connections.values().forEach(BrokerProvider::start);
    }

    /**
     * Closes every provider at the same time, keeping on with the rest if one fails, and returns once all of them are
     * closed. Idempotent. A provider waits for its deliveries in flight before closing (up to a drain timeout): closing
     * the providers one after another would add up the waits of every domain, while the domains not closed yet would
     * keep consuming.
     */
    public void close() {
        final List<Thread> closing = new ArrayList<>();
        connections.forEach((domain, provider) -> {
            final Thread thread = new Thread(() -> close(domain, provider), "rc-close-" + domain);
            thread.start();
            closing.add(thread);
        });
        awaitClosed(closing);
        connections.clear();
    }

    private static void close(String domain, BrokerProvider provider) {
        try {
            provider.close();
        } catch (Exception e) {
            log.error("Error closing the broker provider of domain {}", domain, e);
        }
    }

    private static void awaitClosed(List<Thread> closing) {
        for (Thread thread : closing) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for the broker providers to close");
                return;
            }
        }
    }
}
