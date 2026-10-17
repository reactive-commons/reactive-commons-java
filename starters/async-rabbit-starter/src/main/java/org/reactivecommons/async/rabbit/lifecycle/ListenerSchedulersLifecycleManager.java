package org.reactivecommons.async.rabbit.lifecycle;

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

    private volatile boolean running = false;

    @Override
    public synchronized void start() {
        if (!running) {
            ListenerSchedulers.retain();
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
            ListenerSchedulers.release();
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
        return -5; // lifecycle beans stop in descending phase: before the connections (phase -10)
    }
}
