package org.reactivecommons.async.starter.config;

import org.reactivecommons.async.starter.broker.BrokerProvider;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

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

    private BrokerProvider getChecked(String domain) {
        BrokerProvider domainProvider = connections.get(domain);
        if (domainProvider == null) {
            throw new RuntimeException("You are trying to use the domain " + domain
                    + " but this connection is not defined");
        }
        return domainProvider;
    }

    public Map<String, BrokerProvider> getProviders() {
        return connections;
    }
}
