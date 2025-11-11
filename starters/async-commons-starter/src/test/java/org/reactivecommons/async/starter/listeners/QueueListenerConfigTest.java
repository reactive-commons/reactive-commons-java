package org.reactivecommons.async.starter.listeners;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.DomainHandlers;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
class QueueListenerConfigTest {
    @Mock
    private BrokerProvider<?> provider;
    @Mock
    private HandlerResolver resolver;

    @BeforeEach
    void setUp() {
        ConnectionManager manager = new ConnectionManager();
        manager.addDomain(DEFAULT_DOMAIN, provider);
        DomainHandlers handlers = new DomainHandlers();
        handlers.add(DEFAULT_DOMAIN, resolver);
        new QueueListenerConfig(manager, handlers);
    }

    @Test
    void shouldListen() {
        verify(provider).listenQueues(resolver);
    }

    @Test
    void shouldListenMultipleDomains() {
        BrokerProvider<?> provider1 = mock(BrokerProvider.class);
        BrokerProvider<?> provider2 = mock(BrokerProvider.class);
        HandlerResolver resolver1 = mock(HandlerResolver.class);
        HandlerResolver resolver2 = mock(HandlerResolver.class);

        ConnectionManager manager = new ConnectionManager();
        manager.addDomain(DEFAULT_DOMAIN, provider1);
        manager.addDomain("customDomain", provider2);

        DomainHandlers handlers = new DomainHandlers();
        handlers.add(DEFAULT_DOMAIN, resolver1);
        handlers.add("customDomain", resolver2);

        new QueueListenerConfig(manager, handlers);

        verify(provider1).listenQueues(resolver1);
        verify(provider2).listenQueues(resolver2);
    }
}