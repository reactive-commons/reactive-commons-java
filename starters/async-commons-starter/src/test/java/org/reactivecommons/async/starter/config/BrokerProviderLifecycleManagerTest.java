package org.reactivecommons.async.starter.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.starter.broker.BrokerProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SuppressWarnings("rawtypes")
class BrokerProviderLifecycleManagerTest {

    private ConnectionManager connectionManager;
    private BrokerProviderLifecycleManager manager;

    @BeforeEach
    void setUp() {
        connectionManager = mock(ConnectionManager.class);
        manager = new BrokerProviderLifecycleManager(connectionManager);
    }

    @Test
    void shouldStartTheProvidersOnStart() {
        // Spring starts the lifecycle beans once every bean of the context is created and initialized
        manager.start();

        verify(connectionManager).start();
    }

    @Test
    void shouldCloseTheProvidersOnStop() {
        assertFalse(manager.isRunning());
        manager.start();
        assertTrue(manager.isRunning());

        manager.stop();

        verify(connectionManager).close();
        assertFalse(manager.isRunning());
    }

    @Test
    void shouldCloseOnlyOnceWhenStoppedTwice() {
        manager.start();
        manager.stop();
        manager.stop();

        verify(connectionManager, times(1)).close();
    }

    @Test
    void shouldNotCloseWhenStopIsCalledAndNotRunning() {
        manager.stop();

        verify(connectionManager, times(0)).close();
    }

    @Test
    void shouldStopAfterTheListenerSchedulersAndBeforeTheConnections() {
        // Spring stops lifecycle beans in descending phase order: -5 (listener schedulers), -7 (providers), -10
        // (event loop group / connections)
        assertEquals(-7, manager.getPhase());
    }
}
