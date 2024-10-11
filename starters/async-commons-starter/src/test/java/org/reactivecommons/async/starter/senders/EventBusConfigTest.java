package org.reactivecommons.async.starter.senders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.ConnectionManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventBusConfigTest {
    @Mock
    private DomainEventBus domainEventBus;
    @Mock
    private BrokerProvider<?> brokerProvider;

    private ConnectionManager manager;
    private EventBusConfig eventBusConfig;

    @BeforeEach
    void setUp() {
        eventBusConfig = new EventBusConfig();
        manager = new ConnectionManager();
        manager.addDomain("domain", brokerProvider);
        manager.addDomain("domain2", brokerProvider);
    }

    @Test
    void shouldCreateAllDomainEventBuses() {
        // Arrange
        when(brokerProvider.getDomainBus()).thenReturn(domainEventBus);
        // Act
        DomainEventBus genericDomainEventBus = eventBusConfig.genericDomainEventBus(manager);
        // Assert
        assertNotNull(genericDomainEventBus);
        verify(brokerProvider, times(2)).getDomainBus();
    }
}
