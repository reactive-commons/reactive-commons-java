package org.reactivecommons.async.starter.config;

import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.starter.broker.BrokerProvider;

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
     * Closes every provider, keeping on with the rest if one fails. Idempotent.
     */
    public void close() {
        connections.forEach((domain, provider) -> {
            try {
                provider.close();
            } catch (Exception e) {
                log.error("Error closing the broker provider of domain {}", domain, e);
            }
        });
        connections.clear();
    }
}
