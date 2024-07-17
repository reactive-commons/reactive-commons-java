package org.reactivecommons.async.rabbit.config;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

public class ConnectionManager {
    private final Map<String, DomainConnections> connections = new TreeMap<>();

    @Builder
    @Getter
    public static class DomainConnections {
        private final ReactiveMessageListener listener;
        private final ReactiveMessageSender sender;
        private final ConnectionFactoryProvider provider;
        @Setter
        private DiscardNotifier discardNotifier;
    }

    public void forSender(BiConsumer<String, ReactiveMessageSender> consumer) {
        connections.forEach((key, conn) -> consumer.accept(key, conn.getSender()));
    }

    public void forListener(BiConsumer<String, ReactiveMessageListener> consumer) {
        connections.forEach((key, conn) -> consumer.accept(key, conn.getListener()));
    }

    public void setDiscardNotifier(String domain, DiscardNotifier discardNotifier) {
        getChecked(domain).setDiscardNotifier(discardNotifier);
    }

    public ConnectionManager addDomain(String domain, ReactiveMessageListener listener, ReactiveMessageSender sender,
                                       ConnectionFactoryProvider provider) {
        connections.put(domain, DomainConnections.builder()
                .listener(listener)
                .sender(sender)
                .provider(provider)
                .build());
        return this;
    }

    public ReactiveMessageSender getSender(String domain) {
        return getChecked(domain).getSender();
    }

    public ReactiveMessageListener getListener(String domain) {
        return getChecked(domain).getListener();
    }

    private DomainConnections getChecked(String domain) {
        DomainConnections domainConnections = connections.get(domain);
        if (domainConnections == null) {
            throw new RuntimeException("You are trying to use the domain " + domain
                    + " but this connection is not defined");
        }
        return domainConnections;
    }

    public DiscardNotifier getDiscardNotifier(String domain) {
        return getChecked(domain).getDiscardNotifier();
    }

    public Map<String, DomainConnections> getProviders() {
        return connections;
    }
}
