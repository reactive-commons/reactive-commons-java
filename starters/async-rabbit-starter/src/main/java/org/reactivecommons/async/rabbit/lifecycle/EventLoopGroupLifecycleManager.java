package org.reactivecommons.async.rabbit.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.rabbit.RabbitMQSetupUtils;
import org.springframework.context.SmartLifecycle;

/**
 * Manages the lifecycle of the shared Netty EventLoopGroup used by RabbitMQ connections.
 * <p>
 * Each application context registers itself when it starts and unregisters when it closes. The EventLoopGroup is
 * shut down when the last context using it closes (it is re-created if a new context needs it), so closing one
 * context does not break the connections of another one living in the same JVM (e.g. cached test contexts).
 * <p>
 * Reference: <a href="https://www.rabbitmq.com/client-libraries/java-api-guide#netty">Use of Netty for Network I/O</a>
 */
@Slf4j
public class EventLoopGroupLifecycleManager implements SmartLifecycle {

    private volatile boolean running = false;

    @Override
    public synchronized void start() {
        if (!running) {
            RabbitMQSetupUtils.retainEventLoopGroup();
            running = true;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            log.warn("EventLoopGroup lifecycle manager stop() called but was not running");
            return;
        }

        try {
            running = false;
            RabbitMQSetupUtils.releaseEventLoopGroup();
        } catch (Exception e) {
            log.error("Error shutting down Netty EventLoopGroup", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return -10;
    }
}
