package org.reactivecommons.async.starter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Closes the broker providers (listeners, senders and the threads they own) when the application context closes.
 * <p>
 * It stops after the listener schedulers (phase {@code -5}) and before the connections/event loop group (phase
 * {@code -10}): handlers already running get a chance to finish, and the providers are stopped while the connections
 * are still usable.
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
        return -7; // descending stop order: after the listener schedulers (-5), before the connections (-10)
    }
}
