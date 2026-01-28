package org.reactivecommons.async.rabbit.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.rabbit.RabbitMQSetupUtils;
import org.springframework.context.SmartLifecycle;

/**
 * Manages the lifecycle of the shared Netty EventLoopGroup used by RabbitMQ connections.
 * This component ensures that the EventLoopGroup is properly shutdown when the Spring application context closes.
 * <p>
 * According to RabbitMQ documentation, it is important to dispose of the event loop group after closing all connections
 * to prevent resource leaks and ensure clean application shutdown.
 * <p>
 * Reference: <a href="https://www.rabbitmq.com/client-libraries/java-api-guide#netty">Use of Netty for Network I/O</a>
 */
@Slf4j
public class EventLoopGroupLifecycleManager implements SmartLifecycle {

    private volatile boolean running = false;

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            log.warn("EventLoopGroup lifecycle manager stop() called but was not running");
            return;
        }

        try {
            RabbitMQSetupUtils.shutdownEventLoopGroup();
            running = false;
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