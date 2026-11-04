package org.reactivecommons.async.starter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the listeners of the broker providers once the application context has created and initialized every bean,
 * as Spring AMQP does with its listener containers, and closes the providers (listeners, senders and the threads they
 * own) when the application context closes.
 * <p>
 * Spring stops the lifecycle beans in descending phase. The providers stop in {@link #PHASE}:
 * <ul>
 *     <li>after the graceful shutdown of the web server ({@code DEFAULT_PHASE - 1024}): the requests it lets finish
 *     can still publish messages and wait for the replies of their queries;</li>
 *     <li>before the application task executor ({@code DEFAULT_PHASE / 2}) and the HTTP clients of the application
 *     (Spring stops {@code ReactorClientHttpConnector} and {@code ReactorResourceFactory} in phases 1 and 0): the
 *     handlers of the deliveries in flight, which the providers wait for, can still call other services;</li>
 *     <li>before the listener schedulers ({@code PHASE - 1}) and the connections/event loop group
 *     ({@code PHASE - 3}), which they start after.</li>
 * </ul>
 */
@Slf4j
public class BrokerProviderLifecycleManager implements SmartLifecycle {

    /**
     * The phase of the broker providers. The phases of the listener schedulers and of the connections follow it.
     */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

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
        return PHASE;
    }
}
