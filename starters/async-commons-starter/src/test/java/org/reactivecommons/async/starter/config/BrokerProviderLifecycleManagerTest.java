package org.reactivecommons.async.starter.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

    /**
     * On shutdown the providers wait for the handlers of the deliveries in flight, which may call other services: the
     * HTTP clients of the application must still be open. In phase -7 Spring stopped them first, and the handlers
     * failed with {@code PrematureCloseException}. The requests the web server lets finish may still publish
     * messages, so the providers stop after it.
     */
    @Test
    void shouldStopAfterTheWebServerAndBeforeTheTaskExecutorAndTheHttpClients() {
        List<String> stopped = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> stopped.add("brokerProviders")).when(connectionManager).close();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            // the phases Spring Boot and Spring Framework give these beans
            registerStopRecorder(context, stopped, "webServerGracefulShutdown", SmartLifecycle.DEFAULT_PHASE - 1024);
            registerStopRecorder(context, stopped, "webServerStartStop", SmartLifecycle.DEFAULT_PHASE - 2048);
            registerStopRecorder(context, stopped, "applicationTaskExecutor", Integer.MAX_VALUE / 2);
            registerStopRecorder(context, stopped, "clientHttpConnector", 1);
            registerStopRecorder(context, stopped, "reactorResourceFactory", 0);
            context.registerBean("brokerProviderLifecycleManager", BrokerProviderLifecycleManager.class,
                    () -> manager);
            context.refresh();
        }

        assertThat(stopped).containsExactly("webServerGracefulShutdown", "webServerStartStop", "brokerProviders",
                "applicationTaskExecutor", "clientHttpConnector", "reactorResourceFactory");
    }

    private static void registerStopRecorder(GenericApplicationContext context, List<String> stopped, String name,
                                             int phase) {
        context.registerBean(name, SmartLifecycle.class, () -> new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                stopped.add(name);
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return phase;
            }
        });
    }
}
