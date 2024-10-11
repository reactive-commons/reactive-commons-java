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

import static org.mockito.Mockito.verify;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
class CommandsListenerConfigTest {
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
        new CommandsListenerConfig(manager, handlers);
    }

    @Test
    void shouldListen() {
        // Arrange
        // Act
        // Assert
        verify(provider).listenCommands(resolver);
    }

}
