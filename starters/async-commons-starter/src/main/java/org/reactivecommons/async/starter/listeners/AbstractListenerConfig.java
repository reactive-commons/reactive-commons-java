package org.reactivecommons.async.starter.listeners;

import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.DomainHandlers;

public abstract class AbstractListenerConfig {

    protected AbstractListenerConfig(ConnectionManager manager, DomainHandlers handlers) {
        manager.forDomain((domain, provider) -> listen(domain, provider, handlers.get(domain)));
    }

    @SuppressWarnings("rawtypes")
    abstract void listen(String domain, BrokerProvider provider, HandlerResolver resolver);
}
