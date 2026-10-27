package org.reactivecommons.async.starter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the listeners of the broker providers once the application context has created and initialized every bean,
 * as Spring AMQP does with its listener containers, and closes the providers (listeners, senders and the threads they
 * own) when the application context closes.
 * <p>
 * It starts after the connections/event loop group (phase {@code -10}) and the listener schedulers (phase
 * {@code -8}) are retained.
 * <p>
 * It stops before the listener schedulers (phase {@code -8}) and the connections/event loop group (phase {@code -10}):
 * the providers stop their listeners and wait for the deliveries in flight while the handler pools and the
 * connections are still usable.
 */
@Slf4j
public class BrokerProviderLifecycleManager implements SmartLifecycle {

    private final ConnectionManager connectionManager;
    private volatile boolean running = false;

    public BrokerProviderLifecycleManager(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public synchronized void start() {
        connectionManager.start();
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        try {
            running = false;
            connectionManager.close();
        } catch (Exception e) {
            log.error("Error shutting down the broker providers", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return -7; // descending stop order: before the listener schedulers (-8) and the connections (-10)
    }
}
