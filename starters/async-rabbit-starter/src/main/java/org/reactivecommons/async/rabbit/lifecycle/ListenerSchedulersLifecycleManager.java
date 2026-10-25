package org.reactivecommons.async.rabbit.lifecycle;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.rabbit.listeners.ListenerSchedulers;
import org.springframework.context.SmartLifecycle;

/**
 * Manages the lifecycle of the schedulers shared by all the listeners of the application.
 * <p>
 * Each application context registers itself when it starts and unregisters when it closes. The schedulers are
 * disposed when the last context using them closes, letting the handlers already running finish and acknowledge their
 * messages. It stops before the connections are closed (see {@link EventLoopGroupLifecycleManager}).
 */
@Slf4j
public class ListenerSchedulersLifecycleManager implements SmartLifecycle {

    private final MeterRegistry meterRegistry;
    private volatile boolean running = false;

    public ListenerSchedulersLifecycleManager() {
        this(null);
    }

    /**
     * @param meterRegistry where the metrics of the shared schedulers are registered, {@code null} for none
     */
    public ListenerSchedulersLifecycleManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public synchronized void start() {
        if (!running) {
            ListenerSchedulers.retain(meterRegistry);
            running = true;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        try {
            running = false;
            ListenerSchedulers.release(meterRegistry);
        } catch (Exception e) {
            log.error("Error shutting down the listener schedulers", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // lifecycle beans stop in descending phase: after the broker providers (-7), which stop their listeners and
        // wait for the deliveries in flight, so no listener dispatches to the pools once they are released; before
        // the connections (-10)
        return -8;
    }
}
