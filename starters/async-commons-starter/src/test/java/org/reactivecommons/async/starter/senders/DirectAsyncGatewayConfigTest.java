package org.reactivecommons.async.starter.senders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.DomainHandlers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DirectAsyncGatewayConfigTest {
    @Mock
    private DirectAsyncGateway domainEventBus;
    @Mock
    private BrokerProvider<?> brokerProvider;
    @Mock
    private HandlerResolver resolver;

    private ConnectionManager manager;
    private DirectAsyncGatewayConfig directAsyncGatewayConfig;

    @BeforeEach
    void setUp() {
        directAsyncGatewayConfig = new DirectAsyncGatewayConfig();
        manager = new ConnectionManager();
        manager.addDomain("domain", brokerProvider);
        manager.addDomain("domain2", brokerProvider);
    }

    @Test
    void shouldCreateAllDomainEventBuses() {
        // Arrange
        when(brokerProvider.getDirectAsyncGateway(any())).thenReturn(domainEventBus);
        DomainHandlers handlers = new DomainHandlers();
        handlers.add("domain", resolver);
        handlers.add("domain2", resolver);
        // Act
        DirectAsyncGateway genericDomainEventBus = directAsyncGatewayConfig.genericDirectAsyncGateway(manager, handlers);
        // Assert
        assertNotNull(genericDomainEventBus);
        verify(brokerProvider, times(2)).getDirectAsyncGateway(resolver);
    }
}
